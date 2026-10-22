package com.android.bsmc.bassic

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns bass hits into vibrations.
 *
 * Motors with amplitude control get a real envelope: a solid punch that fades out.
 *
 * Many phones (including the Nothing Phone 4a Pro) expose an on/off-only motor to apps, so
 * every non-zero amplitude plays at full strength. Chopping the motor on and off to fake a
 * fade feels like a buzz, so on those we never chop: each hit is one unbroken push whose
 * length carries the strength (a short push never lets the motor reach full speed, so it
 * feels lighter), and sustained bass holds the motor on for as long as the note lasts.
 */
object HapticShaper {

    const val FRAME_MS = 10

    /** On/off motors: length of the single push for a hit, 10 ms (light) to 50 ms (heavy). */
    fun pulseMillis(strength: Double): Long =
        (10 + 40 * strength.coerceIn(0.0, 1.0).pow(1.2)).roundToLong()

    /** On/off motors: how long one hit may be held on by sustained bass, 0 to 350 ms. */
    fun sustainCapMillis(flow: Double): Long = (350 * flow.coerceIn(0.0, 1.0)).roundToLong()

    /**
     * On/off motors: keep the motor running while the bass stays close to the level of the hit
     * that started it. More flow tolerates a deeper dip before letting go.
     */
    fun shouldSustain(energy: Double, hitEnergy: Double, avgEnergy: Double, flow: Double): Boolean =
        energy >= hitEnergy * (0.8 - 0.3 * flow.coerceIn(0.0, 1.0)) && energy > avgEnergy * 1.05

    class Waveform(val timings: LongArray, val amplitudes: IntArray)

    /**
     * Envelope for one hit, one level (0..1) per [FRAME_MS].
     *
     * @param strength 0..1, how hard the hit is — sets body length and where the tail starts.
     * @param flow 0..1, 0 = tight single tap, 1 = long rolling tail.
     */
    fun envelope(strength: Double, flow: Double): DoubleArray {
        val s = strength.coerceIn(0.0, 1.0)
        val f = flow.coerceIn(0.0, 1.0)
        val bodyFrames = 1 + (s * 3).roundToInt() // 10..40 ms solid punch
        val tailFrames = ((20 + 260 * f * (0.4 + 0.6 * s)) / FRAME_MS).roundToInt()
        val tailStart = 0.35 + 0.5 * s
        val tau = tailFrames / 3.0 // ~95% faded by the end of the tail
        return DoubleArray(bodyFrames + tailFrames) { i ->
            if (i < bodyFrames) 1.0 else tailStart * exp(-(i - bodyFrames + 1) / tau)
        }
    }

    /** For motors with amplitude control: one step per frame, scaled by [scale]. */
    fun toAmplitudeWaveform(env: DoubleArray, scale: Double): Waveform {
        val amps = env.map { (it * scale * 255).roundToInt().coerceIn(0, 255) }
            .dropLastWhile { it == 0 }
        return Waveform(
            LongArray(amps.size) { FRAME_MS.toLong() },
            amps.toIntArray()
        )
    }
}
