package com.example.handcursortracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

enum class GestureType {
    NONE,
    CLICK,        // Jari tengah + jempol
    BACK,         // Jari manis + jempol
    SWIPE_START,  // Telunjuk + jempol mulai pinch
    SWIPING,      // Telunjuk + jempol masih ditahan & bergerak
    SWIPE_END     // Telunjuk + jempol dilepas
}

class HandTracker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onHandUpdate: (
        x: Float, y: Float,
        gesture: GestureType,
        result: HandLandmarkerResult?
    ) -> Unit
) {
    private val MIN_CUTOFF = 0.3f
    private val BETA = 15.0f
    private val DERIVATIVE_CUTOFF = 1.0f

    private var handLandmarker: HandLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val screenWidth by lazy { context.resources.displayMetrics.widthPixels.toFloat() }
    private val screenHeight by lazy { context.resources.displayMetrics.heightPixels.toFloat() }

    private val filterX = OneEuroFilter(minCutoff = MIN_CUTOFF, beta = BETA, dCutoff = DERIVATIVE_CUTOFF)
    private val filterY = OneEuroFilter(minCutoff = MIN_CUTOFF, beta = BETA, dCutoff = DERIVATIVE_CUTOFF)

    // Click state: jari tengah + jempol
    private var isClickPrev = false

    // Back state: jari manis + jempol
    private var isBackPrev = false

    // Swipe state: telunjuk + jempol
    private var isSwipePrev = false

    // Click stabilization (for middle finger click)
    private var frozenX = 0f
    private var frozenY = 0f
    private var isFrozen = false

    private var lastX = -1f
    private var lastY = -1f

    fun start(previewSurfaceProvider: Preview.SurfaceProvider?) {
        this.surfaceProvider = previewSurfaceProvider
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupHandLandmarker()
        startCamera()
    }

    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> handleHandLandmarks(result) }
            .setErrorListener { it.printStackTrace() }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()

            surfaceProvider?.let { preview.setSurfaceProvider(it) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val rawBitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        val timestamp = SystemClock.uptimeMillis()

        try {
            handLandmarker?.detectAsync(mpImage, timestamp)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        imageProxy.close()
    }

    private fun handleHandLandmarks(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            CoroutineScope(Dispatchers.Main).launch {
                // Kalau sedang swipe lalu tangan hilang, kirim SWIPE_END
                val gesture = if (isSwipePrev) GestureType.SWIPE_END else GestureType.NONE
                isSwipePrev = false
                onHandUpdate(lastX, lastY, gesture, null)
            }
            return
        }

        val landmarks = result.landmarks()[0]
        val indexTip = landmarks[8]    // Telunjuk - cursor & swipe
        val thumbTip = landmarks[4]    // Jempol
        val middleTip = landmarks[12]  // Jari tengah - click
        val ringTip = landmarks[16]    // Jari manis - back

        // Cursor position dari telunjuk
        val mirroredX = 1f - indexTip.x()
        val mappedY = indexTip.y()
        val targetPixelX = mirroredX * screenWidth
        val targetPixelY = mappedY * screenHeight

        val timestamp = System.nanoTime() / 1_000_000_000.0
        val smoothX = filterX.filter(targetPixelX, timestamp)
        val smoothY = filterY.filter(targetPixelY, timestamp)

        // --- Deteksi pinch jarak ---
        val swipeDist = hypot(
            (indexTip.x() - thumbTip.x()).toDouble(),
            (indexTip.y() - thumbTip.y()).toDouble()
        ).toFloat()

        val clickDist = hypot(
            (middleTip.x() - thumbTip.x()).toDouble(),
            (middleTip.y() - thumbTip.y()).toDouble()
        ).toFloat()

        val backDist = hypot(
            (ringTip.x() - thumbTip.x()).toDouble(),
            (ringTip.y() - thumbTip.y()).toDouble()
        ).toFloat()

        val isSwipePinch = swipeDist < 0.06f
        val isClickPinch = clickDist < 0.06f && !isSwipePinch
        val isBackPinch = backDist < 0.06f && !isSwipePinch && !isClickPinch

        // --- Tentukan gesture ---
        var gesture = GestureType.NONE

        // SWIPE: Telunjuk + Jempol pinch & drag
        if (isSwipePinch) {
            gesture = if (!isSwipePrev) GestureType.SWIPE_START else GestureType.SWIPING
        } else if (isSwipePrev) {
            gesture = GestureType.SWIPE_END
        }

        // CLICK: Jari Tengah + Jempol (hanya trigger sekali saat mulai pinch)
        if (isClickPinch && !isClickPrev && gesture == GestureType.NONE) {
            gesture = GestureType.CLICK
        }

        // BACK: Jari Manis + Jempol (hanya trigger sekali)
        if (isBackPinch && !isBackPrev && gesture == GestureType.NONE) {
            gesture = GestureType.BACK
        }

        // Click stabilization: freeze cursor saat click
        if (isClickPinch && !isFrozen) {
            isFrozen = true
            frozenX = smoothX
            frozenY = smoothY
        } else if (!isClickPinch) {
            isFrozen = false
        }

        // Saat swipe, cursor TIDAK di-freeze agar bisa track gerakan
        val finalX = if (isFrozen) frozenX else smoothX
        val finalY = if (isFrozen) frozenY else smoothY

        lastX = finalX
        lastY = finalY
        isSwipePrev = isSwipePinch
        isClickPrev = isClickPinch
        isBackPrev = isBackPinch

        CoroutineScope(Dispatchers.Main).launch {
            onHandUpdate(finalX, finalY, gesture, result)
        }
    }

    fun stop() {
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        handLandmarker?.close()
    }

    class OneEuroFilter(
        private val minCutoff: Float = 1.0f,
        private val beta: Float = 0.0f,
        private val dCutoff: Float = 1.0f
    ) {
        private var xPrev: Float? = null
        private var dxPrev: Float = 0.0f
        private var tPrev: Double? = null

        fun reset() {
            xPrev = null
            dxPrev = 0.0f
            tPrev = null
        }

        fun filter(x: Float, t: Double): Float {
            if (tPrev == null || xPrev == null) {
                xPrev = x
                tPrev = t
                return x
            }

            val dt = t - tPrev!!
            if (dt <= 0.0) return xPrev!!

            val alphaD = smoothingFactor(dt, dCutoff)
            val dx = ((x - xPrev!!) / dt).toFloat()
            val dxSmoothed = alphaD.toFloat() * dx + (1f - alphaD.toFloat()) * dxPrev

            val cutoff = minCutoff + beta * abs(dxSmoothed)
            val alpha = smoothingFactor(dt, cutoff)
            val xFiltered = alpha.toFloat() * x + (1f - alpha.toFloat()) * xPrev!!

            xPrev = xFiltered
            dxPrev = dxSmoothed
            tPrev = t
            return xFiltered
        }

        private fun smoothingFactor(dt: Double, cutoff: Float): Double {
            val r = 2.0 * Math.PI * cutoff
            return r * dt / (1.0 + r * dt)
        }
    }
}
