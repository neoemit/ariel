package com.thomaslamendola.ariel

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
fun QRScannerView(
    onCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val latestOnCodeScanned = rememberUpdatedState(onCodeScanned)
    val qrReader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }
    var handledResult by remember { mutableStateOf(false) }

    DisposableEffect(cameraExecutor) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(
                        reader = qrReader,
                        imageProxy = imageProxy,
                        handledResult = handledResult,
                        onCodeScanned = { code ->
                            if (!handledResult) {
                                handledResult = true
                                latestOnCodeScanned.value(code)
                            }
                        }
                    )
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    reader: MultiFormatReader,
    imageProxy: ImageProxy,
    handledResult: Boolean,
    onCodeScanned: (String) -> Unit
) {
    if (handledResult) {
        imageProxy.close()
        return
    }

    val image = imageProxy.image
    if (image == null || image.format != ImageFormat.YUV_420_888) {
        imageProxy.close()
        return
    }

    val width = imageProxy.width
    val height = imageProxy.height
    val yPlane = imageProxy.planes.firstOrNull()
    if (yPlane == null) {
        imageProxy.close()
        return
    }

    val yBuffer = yPlane.buffer
    val rowStride = yPlane.rowStride
    val yBytes = ByteArray(yBuffer.remaining())
    yBuffer.get(yBytes)

    val lumaBytes = if (rowStride == width) {
        yBytes
    } else {
        val resized = ByteArray(width * height)
        for (row in 0 until height) {
            System.arraycopy(yBytes, row * rowStride, resized, row * width, width)
        }
        resized
    }

    val cropRect: Rect = imageProxy.cropRect
    val source = PlanarYUVLuminanceSource(
        lumaBytes,
        width,
        height,
        cropRect.left,
        cropRect.top,
        cropRect.width(),
        cropRect.height(),
        false
    )

    val candidates = buildList {
        add(source)
        if (source.isRotateSupported) {
            add(source.rotateCounterClockwise())
            add(source.rotateCounterClockwise().rotateCounterClockwise())
            add(source.rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise())
        }
    }

    var decodedValue: String? = null
    for (candidate in candidates) {
        try {
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate)))
            decodedValue = result.text
            reader.reset()
            break
        } catch (_: NotFoundException) {
            reader.reset()
        } catch (_: Exception) {
            reader.reset()
        }
    }

    if (!decodedValue.isNullOrBlank()) {
        onCodeScanned(decodedValue)
    }

    imageProxy.close()
}
