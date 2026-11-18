package io.github.axeldelaro.opensnap.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a crisp high-contrast QR Code Bitmap for the user's signed OpenSnap invite URI.
 */
@Composable
fun OpenSnapQrCodeImage(
    content: String,
    size: Dp = 220.dp
) {
    val qrBitmap = remember(content) {
        generateQrBitmap(content, 640)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "QR Code OpenSnap",
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun generateQrBitmap(payload: String, dimensionPx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1
    )
    val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, dimensionPx, dimensionPx, hints)
    val pixels = IntArray(dimensionPx * dimensionPx)
    for (y in 0 until dimensionPx) {
        val offset = y * dimensionPx
        for (x in 0 until dimensionPx) {
            pixels[offset + x] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(dimensionPx, dimensionPx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, dimensionPx, 0, 0, dimensionPx, dimensionPx)
    }
}

/**
 * Live 60fps CameraX + ZXing QR Code Scanner (100% Google Play Services free).
 */
@Composable
fun CameraXQrScannerView(
    modifier: Modifier = Modifier,
    onQrCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, SnapElectricYellow, RoundedCornerShape(20.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val reader = MultiFormatReader().apply {
                        setHints(
                            mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                DecodeHintType.TRY_HARDER to true
                            )
                        )
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                decodeQrFromImageProxy(reader, imageProxy)?.let { rawText ->
                                    if (delivered.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            onQrCodeScanned(rawText)
                                        }
                                    }
                                }
                            }
                        }

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
    }
}

private fun decodeQrFromImageProxy(reader: MultiFormatReader, image: ImageProxy): String? {
    return try {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val yBytes = ByteArray(buffer.remaining())
        buffer.get(yBytes)

        // Normalize rowStride padding if camera hardware pads scanlines
        val tightLuminance = if (rowStride == width) {
            yBytes
        } else {
            val compact = ByteArray(width * height)
            for (row in 0 until height) {
                val srcOffset = row * rowStride
                if (srcOffset + width <= yBytes.size) {
                    System.arraycopy(yBytes, srcOffset, compact, row * width, width)
                }
            }
            compact
        }

        val source = PlanarYUVLuminanceSource(
            tightLuminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        reader.decodeWithState(bitmap).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
        image.close()
    }
}
