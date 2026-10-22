package com.android.bsmc.bassic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.bsmc.bassic.ui.theme.BassicTheme

private val Ink = Color(0xFF000000)
private val Panel = Color(0xFF111111)
private val Line = Color(0xFF2A2A2A)
private val Paper = Color(0xFFF2F2F2)
private val Muted = Color(0xFF8A8A8A)
private val Signal = Color(0xFFD71921) // Nothing red

class MainActivity : ComponentActivity() {

    private var permissionDenied by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            permissionDenied = false
            startHapticService()
        } else {
            permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("bassic", Context.MODE_PRIVATE)
        HapticSyncService.sensitivity = prefs.getFloat("sensitivity", 0.5f)
        HapticSyncService.intensity = prefs.getFloat("intensity", 0.8f)
        HapticSyncService.flow = prefs.getFloat("flow", 0.6f)
        HapticSyncService.autoPlay = prefs.getBoolean("autoPlay", true)

        setContent {
            BassicTheme {
                BassicScreen(
                    permissionDenied = permissionDenied,
                    initialSensitivity = HapticSyncService.sensitivity,
                    initialIntensity = HapticSyncService.intensity,
                    initialFlow = HapticSyncService.flow,
                    initialAutoPlay = HapticSyncService.autoPlay,
                    onToggle = { running -> if (running) stopHapticService() else requestPermissionsAndStart() },
                    onSensitivity = {
                        HapticSyncService.sensitivity = it
                        prefs.edit().putFloat("sensitivity", it).apply()
                    },
                    onIntensity = {
                        HapticSyncService.intensity = it
                        prefs.edit().putFloat("intensity", it).apply()
                    },
                    onFlow = {
                        HapticSyncService.flow = it
                        prefs.edit().putFloat("flow", it).apply()
                    },
                    onAutoPlay = {
                        HapticSyncService.autoPlay = it
                        prefs.edit().putBoolean("autoPlay", it).apply()
                    },
                    onOpenSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        )
                    }
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (audioGranted && notifGranted) {
            startHapticService()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            )
        }
    }

    private fun startHapticService() {
        startForegroundService(Intent(this, HapticSyncService::class.java))
    }

    private fun stopHapticService() {
        stopService(Intent(this, HapticSyncService::class.java))
    }
}

@Composable
private fun BassicScreen(
    permissionDenied: Boolean,
    initialSensitivity: Float,
    initialIntensity: Float,
    initialFlow: Float,
    initialAutoPlay: Boolean,
    onToggle: (running: Boolean) -> Unit,
    onSensitivity: (Float) -> Unit,
    onIntensity: (Float) -> Unit,
    onFlow: (Float) -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val running by HapticSyncService.running.collectAsState()
    val level by HapticSyncService.level.collectAsState()
    val beats by HapticSyncService.beats.collectAsState()

    var sensitivity by remember { mutableFloatStateOf(initialSensitivity) }
    var intensity by remember { mutableFloatStateOf(initialIntensity) }
    var flow by remember { mutableFloatStateOf(initialFlow) }
    var autoPlay by remember { mutableStateOf(initialAutoPlay) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(running)
            Spacer(Modifier.height(36.dp))
            PulseRing(running = running, level = level, beats = beats)
            Spacer(Modifier.height(32.dp))
            DotMeter(level = if (running) level else 0f)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (running) "$beats HITS" else "PLAY ANY SONG, IN ANY APP",
                color = Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(32.dp))

            SettingSlider("SENSITIVITY", sensitivity) {
                sensitivity = it
                onSensitivity(it)
            }
            Spacer(Modifier.height(12.dp))
            SettingSlider("STRENGTH", intensity) {
                intensity = it
                onIntensity(it)
            }
            Spacer(Modifier.height(12.dp))
            SettingSlider("FLOW", flow, hint = if (flow < 0.3f) "TIGHT" else if (flow < 0.7f) "SMOOTH" else "ROLLING") {
                flow = it
                onFlow(it)
            }
            Spacer(Modifier.height(12.dp))
            SettingSwitch("AUTO-PLAY ON START", autoPlay) {
                autoPlay = it
                onAutoPlay(it)
            }

            if (permissionDenied) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Bassic needs the audio permission to hear what's playing. " +
                            "It never uses your microphone. Tap to open settings.",
                    color = Signal,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onOpenSettings)
                )
            }

            Spacer(Modifier.height(28.dp))
            ToggleButton(running = running, onClick = { onToggle(running) })
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No screen sharing. Runs in the background — stop it from the notification.",
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun Header(running: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "BASSIC",
            color = Paper,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            letterSpacing = 6.sp
        )
        Spacer(Modifier.weight(1f))
        val dot by animateColorAsState(if (running) Signal else Line, label = "status")
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (running) "LIVE" else "IDLE",
            color = if (running) Paper else Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            letterSpacing = 2.sp
        )
    }
}

/** Logo inside a ring that breathes with the bass and throws a red ripple on every hit. */
@Composable
private fun PulseRing(running: Boolean, level: Float, beats: Int) {
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(beats) {
        if (beats > 0) {
            ripple.snapTo(1f)
            ripple.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
        }
    }
    val breathe by animateFloatAsState(
        targetValue = if (running) level else 0f,
        animationSpec = tween(80),
        label = "breathe"
    )

    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension / 2 * 0.72f
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Line, radius = base, center = c, style = Stroke(1.5.dp.toPx()))
            drawCircle(
                Paper.copy(alpha = 0.25f + 0.6f * breathe),
                radius = base + breathe * base * 0.18f,
                center = c,
                style = Stroke(2.dp.toPx())
            )
            val r = ripple.value
            if (r > 0f) {
                drawCircle(
                    Signal.copy(alpha = r),
                    radius = base + (1f - r) * base * 0.38f,
                    center = c,
                    style = Stroke((2f + 6f * r).dp.toPx())
                )
            }
        }
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "Bassic",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(150.dp)
                .scale(1f + 0.05f * ripple.value)
                .clip(CircleShape)
        )
    }
}

/** Dot-matrix level meter, Nothing-style. */
@Composable
private fun DotMeter(level: Float, dots: Int = 20) {
    val lit = (level * dots).toInt()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(dots) { i ->
            val color = when {
                i >= lit -> Line
                i >= dots * 0.8 -> Signal
                else -> Paper
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, hint: String? = null, onChange: (Float) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(
                hint ?: "${(value * 100).toInt()}%",
                color = Paper,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = Paper,
                activeTrackColor = Paper,
                inactiveTrackColor = Line,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Paper,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Ink,
                uncheckedBorderColor = Line
            )
        )
    }
}

@Composable
private fun ToggleButton(running: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (running) Ink else Paper, label = "btnBg")
    val fg by animateColorAsState(if (running) Signal else Ink, label = "btnFg")
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(bg)
            .border(1.5.dp, if (running) Signal else Paper, RoundedCornerShape(30.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (running) "STOP" else "START",
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 4.sp
        )
    }
}
