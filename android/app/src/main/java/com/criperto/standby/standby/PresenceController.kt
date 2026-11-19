package com.criperto.standby.standby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.FaceDetector
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp

data class PresenceState(
    val faceDetected: Boolean? = null,
    val lastPresenceEpochMs: Long = System.currentTimeMillis(),
    val cameraActive: Boolean = false,
)

class PresenceController(private val context: Context) {
    private val _state = MutableStateFlow(PresenceState())
    val state: StateFlow<PresenceState> = _state.asStateFlow()

    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var isBound = false

    @Volatile
    private var lastAnalysisMs: Long = 0L

    @Volatile
    private var pollIntervalMs: Long = 5_000L

    private var ortEnv: OrtEnvironment? = null
    private var yunetSession: OrtSession? = null

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun markTouchPresence() {
        _state.value = _state.value.copy(
            lastPresenceEpochMs = System.currentTimeMillis(),
        )
    }

    fun updatePolling(
        lifecycleOwner: LifecycleOwner,
        shouldPollCamera: Boolean,
        intervalSec: Int,
    ) {
        pollIntervalMs = (intervalSec.coerceIn(2, 60) * 1000L)
        if (!shouldPollCamera || !hasCameraPermission()) {
            stopCamera()
            return
        }
        if (isBound) return
        startCamera(lifecycleOwner)
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analyzerExecutor) { image ->
                    processFrame(image)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                isBound = true
                _state.value = _state.value.copy(cameraActive = true)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        if (!isBound && cameraProvider == null) return
        runCatching {
            ContextCompat.getMainExecutor(context).execute {
                runCatching { cameraProvider?.unbindAll() }
            }
        }
        isBound = false
        _state.value = _state.value.copy(cameraActive = false)
    }

    fun shutdown() {
        stopCamera()
        runCatching { yunetSession?.close() }
        yunetSession = null
        analyzerExecutor.shutdownNow()
    }

    private fun processFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < pollIntervalMs) return
            lastAnalysisMs = now

            val rawBmp = image.toBitmap()
            val rotDeg = image.imageInfo.rotationDegrees
            val bmp = if (rotDeg != 0) {
                val m = Matrix().apply { postRotate(rotDeg.toFloat()) }
                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true).also {
                    if (it !== rawBmp) rawBmp.recycle()
                }
            } else {
                rawBmp
            }

            val detected = detectWithYunetIfAvailable(bmp) ?: detectWithAndroidFaceDetector(bmp)
            bmp.recycle()

            val prev = _state.value
            _state.value = prev.copy(
                faceDetected = detected,
                lastPresenceEpochMs = if (detected) now else prev.lastPresenceEpochMs,
            )
        } catch (_: Throwable) {
            // Keep running reliably on sensor edge cases
        } finally {
            image.close()
        }
    }

    private fun detectWithAndroidFaceDetector(bmp: Bitmap): Boolean {
        // Android FaceDetector requires even width and RGB_565
        val targetW = if (bmp.width % 2 == 0) bmp.width else bmp.width - 1
        val targetH = bmp.height
        if (targetW < 16 || targetH < 16) return false
        val rgb565 = Bitmap.createScaledBitmap(bmp, targetW, targetH, false)
            .copy(Bitmap.Config.RGB_565, false)
        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val count = FaceDetector(targetW, targetH, 1).findFaces(rgb565, faces)
            count > 0 && (faces[0]?.confidence() ?: 0f) >= 0.35f
        } finally {
            rgb565.recycle()
        }
    }

    private fun detectWithYunetIfAvailable(bmp: Bitmap): Boolean? {
        val modelFile = File(context.filesDir, "models/face_detection_yunet.onnx")
        if (!modelFile.exists()) return null

        return runCatching {
            val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
            val session = yunetSession ?: env.createSession(
                modelFile.absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
            ).also { yunetSession = it }

            val inputW = 320
            val inputH = 240
            val scaled = Bitmap.createScaledBitmap(bmp, inputW, inputH, true)
            val pixels = IntArray(inputW * inputH)
            scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            if (scaled !== bmp) scaled.recycle()

            val chw = FloatArray(3 * inputW * inputH)
            val plane = inputW * inputH
            for (i in 0 until plane) {
                val p = pixels[i]
                // YuNet expects BGR uint8-range float [0..255]
                chw[i] = (p and 0xFF).toFloat()
                chw[plane + i] = ((p shr 8) and 0xFF).toFloat()
                chw[2 * plane + i] = ((p shr 16) and 0xFF).toFloat()
            }

            val inputName = session.inputNames.first()
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(chw),
                longArrayOf(1, 3, inputH.toLong(), inputW.toLong()),
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { results ->
                    for (entry in results) {
                        val name = entry.key.lowercase()
                        if (name.contains("cls") || name.contains("obj")) {
                            val arr = (entry.value.value as? Array<*>)?.firstOrNull() as? Array<*> ?: continue
                            for (row in arr) {
                                val floats = row as? FloatArray ?: continue
                                for (logit in floats) {
                                    val prob = 1f / (1f + exp(-logit))
                                    if (prob >= 0.65f) return@runCatching true
                                }
                            }
                        }
                    }
                    false
                }
            }
        }.getOrNull()
    }
}
