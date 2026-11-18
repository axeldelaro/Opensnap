package io.github.axeldelaro.opensnap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow

/**
 * Native CameraX Photo Snap Studio:
 * - Instant Front / Back Camera flip
 * - Flash toggle (Off / On / Auto)
 * - Mode switch: "🔥 Vue unique" vs "⏱ Chat 24h"
 * - Captures raw image bytes strictly in RAM (zero MediaStore leak) before EXIF/GPS scrubbing & XChaCha20 encryption
 */
@Composable
fun SnapCameraScreen(
    recipientDisplayName: String,
    onClose: () -> Unit,
    onSendSnapBytes: (rawBytes: ByteArray, caption: String, isViewOnce: Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCamPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCamPermission) {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isViewOnce by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("") }
    var capturedRawBytes by remember { mutableStateOf<ByteArray?>(null) }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(hasCamPermission, lensFacing) {
        if (!hasCamPermission) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(context))
    }

    if (!hasCamPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Permission Caméra requise pour capturer un Snap chiffré", color = Color.White)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = SnapElectricYellow, contentColor = ObsidianBg)
                ) {
                    Text("Autoriser la caméra", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose) { Text("Retour") }
            }
        }
        return
    }

    val previewBytes = capturedRawBytes
    if (previewBytes != null) {
        val bmp = remember(previewBytes) {
            BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Aperçu Snap",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = { capturedRawBytes = null },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Reprendre", tint = Color.White)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("🛡️ EXIF & GPS purgés", color = CyberCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ObsidianBg.copy(alpha = 0.88f))
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = isViewOnce,
                        onClick = { isViewOnce = true },
                        leadingIcon = { Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = FlameCoral) },
                        label = { Text("Vue unique") }
                    )
                    FilterChip(
                        selected = !isViewOnce,
                        onClick = { isViewOnce = false },
                        leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, tint = CyberCyan) },
                        label = { Text("Conserver 24h") }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        placeholder = { Text("Ajouter une légende chiffrée...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            onSendSnapBytes(previewBytes, caption, isViewOnce)
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SnapElectricYellow,
                            contentColor = ObsidianBg
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(recipientDisplayName.take(10), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    } else {
        // Live CameraX Viewfinder
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Top Camera Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                            imageCapture.flashMode = flashMode
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        val icon = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        }
                        Icon(icon, contentDescription = "Flash", tint = SnapElectricYellow)
                    }

                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Retourner caméra", tint = Color.White)
                    }
                }
            }

            // Bottom Retention Selector & Shutter Button
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(ObsidianCard.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "🔥 Vue unique",
                        color = if (isViewOnce) FlameCoral else Color.LightGray,
                        fontWeight = if (isViewOnce) FontWeight.ExtraBold else FontWeight.Normal,
                        modifier = Modifier.clickable { isViewOnce = true }
                    )
                    Text("•", color = Color.Gray)
                    Text(
                        text = "⏱ Chat 24h",
                        color = if (!isViewOnce) CyberCyan else Color.LightGray,
                        fontWeight = if (!isViewOnce) FontWeight.ExtraBold else FontWeight.Normal,
                        modifier = Modifier.clickable { isViewOnce = false }
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Snap Shutter Ring
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(5.dp, SnapElectricYellow, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            imageCapture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val buf = image.planes.firstOrNull()?.buffer
                                        if (buf != null) {
                                            val bytes = ByteArray(buf.remaining())
                                            buf.get(bytes)
                                            capturedRawBytes = bytes
                                        }
                                        image.close()
                                    }

                                    override fun onError(exception: ImageCaptureException) {}
                                }
                            )
                        }
                )
            }
        }
    }
}
