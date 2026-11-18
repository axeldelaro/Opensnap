package io.github.axeldelaro.opensnap.ui.components

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axeldelaro.opensnap.data.media.EncryptedMediaVault
import io.github.axeldelaro.opensnap.data.media.SecureFileShredder
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

data class CompletedVoiceRecording(
    val encodedBytes: ByteArray,
    val durationMs: Long,
    val waveformCsv: String
)

/**
 * Low-bitrate AAC/Opus Voice Recorder Controller with real-time RMS waveform capture
 * and immediate cryptographic shredding of temporary capture buffer upon completion or cancel.
 */
class VoiceCaptureEngine(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var startedAtMs: Long = 0L
    private val amplitudeSamples = mutableListOf<Int>()

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        stopAndShredTemp()
        return try {
            val out = File(context.cacheDir, "ptt_capture_${System.nanoTime()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(24_000)
            mr.setAudioEncodingBitRate(32_000) // Ultra-compact ~4 KB/sec for instant relay/P2P transit
            mr.setOutputFile(out.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            tempFile = out
            startedAtMs = System.currentTimeMillis()
            amplitudeSamples.clear()
            true
        } catch (_: Exception) {
            stopAndShredTemp()
            false
        }
    }

    fun pollNormalizedAmplitude(): Float {
        val amp = try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
        val norm = (amp / 18_000f).coerceIn(0.08f, 1.0f)
        amplitudeSamples.add((norm * 100).toInt())
        return norm
    }

    fun finishAndRead(): CompletedVoiceRecording? {
        val file = tempFile ?: return null
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(400L)
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            if (!file.exists() || file.length() < 128L) {
                SecureFileShredder.shredFile(file)
                tempFile = null
                return null
            }
            val bytes = file.readBytes()
            SecureFileShredder.shredFile(file)
            tempFile = null

            val normalized48 = resampleWaveform(amplitudeSamples, 36)
            CompletedVoiceRecording(
                encodedBytes = bytes,
                durationMs = elapsed,
                waveformCsv = normalized48.joinToString(",")
            )
        } catch (_: Exception) {
            stopAndShredTemp()
            null
        }
    }

    fun cancelRecording() {
        stopAndShredTemp()
    }

    private fun stopAndShredTemp() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        tempFile?.let { SecureFileShredder.shredFile(it) }
        tempFile = null
    }

    private fun resampleWaveform(raw: List<Int>, targetBars: Int): List<Int> {
        if (raw.isEmpty()) return List(targetBars) { 20 }
        return List(targetBars) { idx ->
            val srcIndex = ((idx.toFloat() / targetBars.toFloat()) * raw.size).toInt()
                .coerceIn(0, raw.lastIndex)
            raw[srcIndex].coerceIn(10, 100)
        }
    }
}

/**
 * Push-to-Talk Microphone Button + Hands-Free Padlock + Slide-to-Cancel HUD.
 */
@Composable
fun PushToTalkMicControl(
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onRecordingStateChanged: (Boolean) -> Unit,
    onVoiceNoteReady: (CompletedVoiceRecording) -> Unit
) {
    val context = LocalContext.current
    val captureEngine = remember { VoiceCaptureEngine(context) }

    var isRecording by remember { mutableStateOf(false) }
    var isHandsFreeLocked by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val liveBars = remember { mutableStateListOf<Float>() }

    DisposableEffect(Unit) {
        onDispose {
            captureEngine.cancelRecording()
        }
    }

    LaunchedEffect(isRecording) {
        onRecordingStateChanged(isRecording)
        if (isRecording) {
            val startTs = System.currentTimeMillis()
            liveBars.clear()
            while (isActive && isRecording) {
                elapsedMs = System.currentTimeMillis() - startTs
                val amp = captureEngine.pollNormalizedAmplitude()
                liveBars.add(amp)
                if (liveBars.size > 28) liveBars.removeAt(0)
                delay(80L)
            }
        }
    }

    if (isRecording && isHandsFreeLocked) {
        // Hands-Free Locked Recording Bar with Live Waveform + Trash + Send
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(ObsidianCard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    captureEngine.cancelRecording()
                    isRecording = false
                    isHandsFreeLocked = false
                }
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Annuler", tint = FlameCoral)
            }

            Icon(Icons.Default.Lock, contentDescription = null, tint = SnapElectricYellow, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = formatDurationMmSs(elapsedMs),
                color = FlameCoral,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.width(10.dp))

            WaveformCanvas(
                bars = liveBars.toList().ifEmpty { listOf(0.2f, 0.4f, 0.3f) },
                progress = 1f,
                activeColor = CyberCyan,
                inactiveColor = CyberCyan.copy(alpha = 0.3f),
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
            )

            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val rec = captureEngine.finishAndRead()
                    isRecording = false
                    isHandsFreeLocked = false
                    if (rec != null) onVoiceNoteReady(rec)
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SnapElectricYellow)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer vocal", tint = ObsidianBg)
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(ObsidianCard)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "● ${formatDurationMmSs(elapsedMs)}",
                        color = FlameCoral,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "◀ Glisser annuler  •  ▲ Cadenas",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            val pulse = rememberInfiniteTransition(label = "ptt_pulse")
            val scale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = if (isRecording) 1.18f else 1f,
                animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
                label = "ptt_scale"
            )

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .scale(if (isRecording) scale else 1f)
                    .clip(CircleShape)
                    .background(if (isRecording) FlameCoral else CyberCyan)
                    .clickable {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else if (!isRecording) {
                            // Single tap starts hands-free locked mode directly for effortless usability
                            if (captureEngine.start()) {
                                isRecording = true
                                isHandsFreeLocked = true
                            }
                        }
                    }
                    .pointerInput(hasMicPermission) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (!hasMicPermission) {
                                    onRequestMicPermission()
                                    return@detectDragGesturesAfterLongPress
                                }
                                dragX = 0f
                                dragY = 0f
                                if (captureEngine.start()) {
                                    isRecording = true
                                    isHandsFreeLocked = false
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (!isRecording || isHandsFreeLocked) return@detectDragGesturesAfterLongPress
                                dragX += dragAmount.x
                                dragY += dragAmount.y
                                if (dragX < -130f) {
                                    // Slide Left -> Cancel immediately
                                    captureEngine.cancelRecording()
                                    isRecording = false
                                } else if (dragY < -100f) {
                                    // Slide Up -> Engage Hands-Free Padlock!
                                    isHandsFreeLocked = true
                                }
                            },
                            onDragEnd = {
                                if (isRecording && !isHandsFreeLocked) {
                                    val rec = captureEngine.finishAndRead()
                                    isRecording = false
                                    if (rec != null) onVoiceNoteReady(rec)
                                }
                            },
                            onDragCancel = {
                                if (isRecording && !isHandsFreeLocked) {
                                    captureEngine.cancelRecording()
                                    isRecording = false
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Maintenir pour parler",
                    tint = ObsidianBg
                )
            }
        }
    }
}

/**
 * Animated E2EE Voice Note Player Bubble streaming directly from RAM `MediaDataSource`
 * with **1x / 1.5x / 2x** variable speed toggle.
 */
@Composable
fun VoiceMessagePlayerContent(
    messageId: String,
    encryptedMediaPath: String?,
    mediaKeyHex: String?,
    durationMs: Long,
    waveformCsv: String?,
    accentColor: Color
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speedIndex by remember { mutableIntStateOf(0) }
    val speeds = listOf(1.0f, 1.5f, 2.0f)
    val currentSpeed = speeds[speedIndex]

    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }

    val bars = remember(waveformCsv) {
        waveformCsv?.split(',')
            ?.mapNotNull { it.toIntOrNull()?.let { v -> (v / 100f).coerceIn(0.12f, 1f) } }
            ?.takeIf { it.isNotEmpty() }
            ?: List(32) { idx -> 0.25f + ((idx * 17) % 60) / 100f }
    }

    DisposableEffect(messageId) {
        onDispose {
            try {
                playerRef.value?.stop()
            } catch (_: Exception) {
            }
            playerRef.value?.release()
            playerRef.value = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            val mp = playerRef.value
            if (mp != null && mp.isPlaying) {
                val total = mp.duration.coerceAtLeast(1)
                progress = (mp.currentPosition.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
            delay(60L)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accentColor)
                .clickable {
                    val existing = playerRef.value
                    if (existing != null && isPlaying) {
                        existing.pause()
                        isPlaying = false
                    } else if (existing != null) {
                        applyPlaybackSpeed(existing, currentSpeed)
                        existing.start()
                        isPlaying = true
                    } else {
                        val decryptedBytes = EncryptedMediaVault.readDecryptedMediaBytes(
                            messageId = messageId,
                            encryptedPath = encryptedMediaPath,
                            mediaKeyHex = mediaKeyHex
                        ) ?: return@clickable

                        try {
                            val mp = MediaPlayer()
                            mp.setDataSource(EncryptedMediaVault.createRamAudioDataSource(decryptedBytes))
                            mp.setOnCompletionListener {
                                isPlaying = false
                                progress = 0f
                            }
                            mp.prepare()
                            applyPlaybackSpeed(mp, currentSpeed)
                            mp.start()
                            playerRef.value = mp
                            isPlaying = true
                        } catch (_: Exception) {
                            isPlaying = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Lire message vocal",
                tint = ObsidianBg
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            WaveformCanvas(
                bars = bars,
                progress = progress,
                activeColor = accentColor,
                inactiveColor = accentColor.copy(alpha = 0.32f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatDurationMmSs(durationMs),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(8.dp))

        // 1x / 1.5x / 2x Speed Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianBg.copy(alpha = 0.55f))
                .clickable {
                    val nextIdx = (speedIndex + 1) % speeds.size
                    speedIndex = nextIdx
                    playerRef.value?.let { mp ->
                        if (mp.isPlaying) {
                            applyPlaybackSpeed(mp, speeds[nextIdx])
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val speedLabel = if (currentSpeed == 1.5f) "1.5x" else "${currentSpeed.toInt()}x"
            Text(
                text = speedLabel,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

private fun applyPlaybackSpeed(mp: MediaPlayer, speed: Float) {
    try {
        mp.playbackParams = PlaybackParams().setSpeed(speed)
    } catch (_: Exception) {
    }
}

@Composable
fun WaveformCanvas(
    bars: List<Float>,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (bars.isEmpty()) return@Canvas
        val count = bars.size
        val spacing = 3.dp.toPx()
        val barWidth = ((size.width - spacing * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
        val activeThreshold = (progress * count).toInt()

        bars.forEachIndexed { i, norm ->
            val h = (size.height * norm.coerceIn(0.14f, 1f))
            val x = i * (barWidth + spacing)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = if (i <= activeThreshold) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

fun formatDurationMmSs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
