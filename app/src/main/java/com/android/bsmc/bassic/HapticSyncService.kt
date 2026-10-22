package com.android.bsmc.bassic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Listens to whatever the phone is playing (YouTube Music, Spotify, ...) and turns kick/bass
 * hits into haptic pulses.
 *
 * Audio comes from a [Visualizer] attached to the global output mix (session 0). Unlike
 * MediaProjection playback capture this needs no screen-share consent, and it still sees
 * apps such as YouTube Music that opt out of playback capture.
 */
class HapticSyncService : Service() {

    companion object {
        private const val TAG = "HapticSync"
        private const val CHANNEL_ID = "HapticSyncChannel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.android.bsmc.bassic.STOP"

        private val _running = MutableStateFlow(false)
        /** Whether the service is live; the UI follows this instead of guessing. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _level = MutableStateFlow(0f)
        /** Current bass level, 0..1, for the meter. */
        val level: StateFlow<Float> = _level.asStateFlow()

        private val _beats = MutableStateFlow(0)
        /** Increments on every haptic pulse, so the UI can flash in sync. */
        val beats: StateFlow<Int> = _beats.asStateFlow()

        /** 0..1 — higher fires on softer hits. Set from the UI, read on the analysis thread. */
        @Volatile
        var sensitivity = 0.5f

        /** 0..1 — scales how hard each pulse is. */
        @Volatile
        var intensity = 0.8f

        /** 0..1 — 0 is a tight tap per hit, 1 lets each hit roll out into a long fade. */
        @Volatile
        var flow = 0.6f

        /** Press play on whatever player owns the media notification when Bassic starts. */
        @Volatile
        var autoPlay = true

        // Poll faster than Visualizer's ~20 Hz callback rate so hits land closer to the beat.
        private const val POLL_INTERVAL_MS = 12L
        private const val BASS_LOW_HZ = 30
        private const val BASS_HIGH_HZ = 160
        private const val MIN_GAP_MS = 90L
        private const val SUSTAIN_STEP_MS = 40L
    }

    private lateinit var vibrator: Vibrator
    private lateinit var audioManager: AudioManager
    private var visualizer: Visualizer? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    private lateinit var fft: ByteArray
    private var bassBins = 1..3

    // Running statistics of bass energy, used for an adaptive onset threshold.
    private var avgEnergy = 0.0
    private var varEnergy = 0.0
    private var prevEnergy = 0.0
    private var peakEnergy = 0.0
    private var lastPulseAt = 0L

    // On/off motors: the push in flight, so sustained bass can keep extending it.
    private var hasAmplitudeControl = false
    private var vibeEndAt = 0L
    private var sustainLimitAt = 0L
    private var hitEnergy = 0.0

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        audioManager = getSystemService(AudioManager::class.java)
        hasAmplitudeControl = vibrator.hasAmplitudeControl()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_running.value) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        if (!startVisualizer()) {
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        if (autoPlay && !audioManager.isMusicActive) pressPlay()
        // Not sticky: a microphone-type foreground service cannot be restarted from the
        // background, so the user restarts it from the app instead.
        return START_NOT_STICKY
    }

    private fun startVisualizer(): Boolean {
        return try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1] // largest = finest bass resolution
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED // independent of volume level
            v.enabled = true
            visualizer = v

            fft = ByteArray(v.captureSize)
            val binHz = (v.samplingRate / 1000.0) / v.captureSize // samplingRate is in mHz
            val lo = max(1, (BASS_LOW_HZ / binHz).roundToInt())
            val hi = max(lo, min(v.captureSize / 2 - 1, (BASS_HIGH_HZ / binHz).roundToInt()))
            bassBins = lo..hi
            Log.i(TAG, "Visualizer up: size=${v.captureSize} binHz=$binHz bins=$bassBins")

            worker = HandlerThread("BassicAnalysis").also { it.start() }
            handler = Handler(worker!!.looper).also { it.post(pollTask) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not attach Visualizer to the output mix", e)
            false
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            val v = visualizer ?: return
            if (audioManager.isMusicActive && v.getFft(fft) == Visualizer.SUCCESS) {
                analyze()
            } else {
                prevEnergy = 0.0
                _level.value *= 0.8f
            }
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Visualizer FFT layout: [0]=DC real, [1]=Nyquist real, then (re, im) pairs for bins
     * 1..n/2-1. We sum the magnitude of the bass bins and fire when it jumps clearly above
     * its recent average.
     */
    private fun analyze() {
        var energy = 0.0
        for (k in bassBins) {
            energy += hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
        }

        // Same block twice in a row means Visualizer has not produced a new window yet.
        if (energy == prevEnergy) return

        val now = SystemClock.uptimeMillis()
        val std = sqrt(varEnergy)
        val rising = energy > prevEnergy
        val s = sensitivity
        val isHit = rising &&
                energy > avgEnergy + (2.0 - 1.4 * s) * std &&
                energy > avgEnergy * (1.45 - 0.4 * s) &&
                energy > 25.0 && // ignore near-silence
                now - lastPulseAt > MIN_GAP_MS

        // Slowly decaying loudest-recent-hit, so strength is relative to this song's dynamics.
        peakEnergy = max(energy, peakEnergy * 0.997)

        if (isHit) {
            val relative = ((energy - avgEnergy) / max(peakEnergy - avgEnergy, 1.0)).coerceIn(0.0, 1.0)
            val strength = (0.25 + 0.75 * relative.pow(0.7)) * (0.3 + 0.7 * intensity)
            pulse(strength.coerceIn(0.0, 1.0), now)
            hitEnergy = energy
            lastPulseAt = now
            _beats.value++
        } else if (!hasAmplitudeControl &&
            now < vibeEndAt && vibeEndAt - now <= 20 && now < sustainLimitAt &&
            HapticShaper.shouldSustain(energy, hitEnergy, avgEnergy, flow.toDouble())
        ) {
            // Bass is still holding: re-arm the motor just before it stops, so it never gaps.
            vibrate(VibrationEffect.createOneShot(SUSTAIN_STEP_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            vibeEndAt = now + SUSTAIN_STEP_MS
        }
        _level.value = (energy / max(avgEnergy * 2.0, 50.0)).toFloat().coerceIn(0f, 1f)

        // Exponential moving average/variance, ~1 s memory.
        val alpha = 0.05
        val diff = energy - avgEnergy
        avgEnergy += alpha * diff
        varEnergy = (1 - alpha) * (varEnergy + alpha * diff * diff)
        prevEnergy = energy
    }

    /** A new hit replaces whatever is still playing, so fast beats stay tight. */
    private fun pulse(strength: Double, now: Long) {
        if (hasAmplitudeControl) {
            val env = HapticShaper.envelope(strength, flow.toDouble())
            val wave = HapticShaper.toAmplitudeWaveform(env, scale = 0.35 + 0.65 * strength)
            if (wave.timings.isEmpty()) return
            vibrate(VibrationEffect.createWaveform(wave.timings, wave.amplitudes, -1))
        } else {
            val ms = HapticShaper.pulseMillis(strength)
            vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            vibeEndAt = now + ms
            sustainLimitAt = vibeEndAt + HapticShaper.sustainCapMillis(flow.toDouble())
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            vibrator.vibrate(effect)
        }
    }

    /**
     * Sends a media Play key. Android routes it to the active media session — the player in
     * the notification shade (YouTube Music) — or, if none is live, to the last app that played.
     */
    private fun pressPlay() {
        val t = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Haptic Sync Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, HapticSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bassic is Active")
            .setContentText("Feeling the bass of whatever is playing")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        _running.value = false
        _level.value = 0f
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        visualizer?.run {
            enabled = false
            release()
        }
        visualizer = null
        vibrator.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
