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


class HandTracker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onHandUpdate: (x: Float, y: Float, isClicking: Boolean, result: HandLandmarkerResult?) -> Unit
) {
    // --- ONE EURO FILTER SETTINGS --
    private val MIN_CUTOFF = 0.3f
    private val BETA = 15.0f
    private val DERIVATIVE_CUTOFF = 1.0f

    private var handLandmarker: HandLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val screenWidth by lazy { context.resources.displayMetrics.widthPixels.toFloat() }
    private val screenHeight by lazy { context.resources.displayMetrics.heightPixels.toFloat() }

    // One Euro Filters for X and Y
    private val filterX = OneEuroFilter(minCutoff = MIN_CUTOFF, beta = BETA, dCutoff = DERIVATIVE_CUTOFF)
    private val filterY = OneEuroFilter(minCutoff = MIN_CUTOFF, beta = BETA, dCutoff = DERIVATIVE_CUTOFF)

    private var isClickingPrev = false


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


        if (rotatedBitmap != rawBitmap) {
            // Don't recycle rawBitmap as it may be managed by imageProxy
        }

        imageProxy.close()
    }

    private fun handleHandLandmarks(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {

            CoroutineScope(Dispatchers.Main).launch {
                onHandUpdate(lastX, lastY, false, null)
            }
            return
        }

        val landmarks = result.landmarks()[0]
        val indexTip = landmarks[8]   // Ujung telunjuk - untuk posisi cursor
        val thumbTip = landmarks[4]   // Ujung jempol - untuk pinch click
        val indexMcp = landmarks[5]   // Pangkal telunjuk - untuk stabilitas

        val rawX = indexTip.x()
        val rawY = indexTip.y()


        val mirroredX = 1f - rawX  // Mirror untuk front camera
        val mappedY = rawY         // Y sudah benar setelah rotasi


        val targetPixelX = mirroredX * screenWidth
        val targetPixelY = mappedY * screenHeight


        val timestamp = System.nanoTime() / 1_000_000_000.0
        val smoothX = filterX.filter(targetPixelX, timestamp)
        val smoothY = filterY.filter(targetPixelY, timestamp)

        // --- CLICK DETECTION (Pinch: jempol + telunjuk) ---
        val pinchDist = hypot(
            (indexTip.x() - thumbTip.x()).toDouble(),
            (indexTip.y() - thumbTip.y()).toDouble()
        ).toFloat()
        val isPinching = pinchDist < 0.05f
        val finalIsClicking = isPinching && !isClickingPrev


        if (isPinching && !isFrozen) {
            isFrozen = true
            frozenX = smoothX
            frozenY = smoothY
        } else if (!isPinching) {
            isFrozen = false
        }

        val finalX = if (isFrozen) frozenX else smoothX
        val finalY = if (isFrozen) frozenY else smoothY


        lastX = finalX
        lastY = finalY

        isClickingPrev = isPinching

        CoroutineScope(Dispatchers.Main).launch {
            onHandUpdate(finalX, finalY, finalIsClicking, result)
        }
    }

    fun stop() {
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        handLandmarker?.close()
    }

    // --- ONE EURO FILTER ---
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
