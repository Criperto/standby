package com.criperto.standby.standby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.FaceDetector
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null

    /** True while the periodic "snapshot" loop is scheduled (camera itself is only open for a split second). */
    @Volatile
    private var pollingEnabled = false

    @Volatile
    private var captureInFlight = false

    @Volatile
    private var pollIntervalMs: Long = 1_000L

    private var lifecycleOwnerRef: LifecycleOwner? = null
    private var captureSeq = 0L

    private var ortEnv: OrtEnvironment? = null
    private var yunetSession: OrtSession? = null

    private val captureTick = Runnable { runCaptureCycle() }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun markTouchPresence() {
        _state.value = _state.value.copy(
            lastPresenceEpochMs = System.currentTimeMillis(),
        )
    }

    /** Seeds the "last presence" so a restarted controller does not fake a recent presence. */
    fun seedLastPresence(epochMs: Long) {
        _state.value = _state.value.copy(lastPresenceEpochMs = epochMs)
    }

    fun updatePolling(
        lifecycleOwner: LifecycleOwner,
        shouldPollCamera: Boolean,
        intervalSec: Int,
    ) {
        pollIntervalMs = (intervalSec.coerceIn(1, 60) * 1000L)
        lifecycleOwnerRef = lifecycleOwner
        if (!shouldPollCamera || !hasCameraPermission()) {
            stopCamera()
            return
        }
        if (pollingEnabled) return
        pollingEnabled = true
        mainHandler.removeCallbacks(captureTick)
        mainHandler.post(captureTick)
    }

    /**
     * Opens the camera, grabs ONE analysed frame (after a short AE warm-up) and releases it right away.
     * Keeping the camera open 24/7 gets it revoked by Android (privacy indicator disappears), so we only
     * hold it for a few hundred ms per cycle.
     */
    private fun runCaptureCycle() {
        if (!pollingEnabled) return
        val owner = lifecycleOwnerRef
        if (owner == null || captureInFlight) {
            scheduleNextCycle()
            return
        }
        captureInFlight = true
        val seq = ++captureSeq
        val finished = AtomicBoolean(false)
        val frameCounter = AtomicInteger(0)

        fun finishCycle() {
            if (!finished.compareAndSet(false, true)) return
            mainHandler.post {
                runCatching { cameraProvider?.unbindAll() }
                _state.value = _state.value.copy(cameraActive = false)
                captureInFlight = false
                scheduleNextCycle()
            }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!pollingEnabled || seq != captureSeq) {
                captureInFlight = false
                return@addListener
            }
            val ok = runCatching {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analyzerExecutor) { image ->
                    if (finished.get()) {
                        image.close()
                        return@setAnalyzer
                    }
                    // Skip first frames so auto-exposure converges (important in a dark room)
                    if (frameCounter.incrementAndGet() <= WARMUP_FRAMES) {
                        image.close()
                        return@setAnalyzer
                    }
                    processFrame(image)
                    finishCycle()
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                _state.value = _state.value.copy(cameraActive = true)
            }.isSuccess
            if (!ok) {
                finishCycle()
            } else {
                // Watchdog: never keep the camera open longer than a few seconds
                mainHandler.postDelayed({ finishCycle() }, CAPTURE_WATCHDOG_MS)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun scheduleNextCycle() {
        mainHandler.removeCallbacks(captureTick)
        if (pollingEnabled) {
            mainHandler.postDelayed(captureTick, pollIntervalMs)
        }
    }

    fun stopCamera() {
        pollingEnabled = false
        captureSeq++
        mainHandler.removeCallbacks(captureTick)
        runCatching {
            mainHandler.post {
                runCatching { cameraProvider?.unbindAll() }
                captureInFlight = false
            }
        }
        if (_state.value.cameraActive) {
            _state.value = _state.value.copy(cameraActive = false)
        }
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

    companion object {
        private const val WARMUP_FRAMES = 3
        private const val CAPTURE_WATCHDOG_MS = 3_500L
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
