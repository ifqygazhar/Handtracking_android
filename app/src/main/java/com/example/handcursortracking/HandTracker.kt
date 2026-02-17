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
    CLICK,              // Jari tengah + jempol (singkat)
    LONG_PRESS_START,   // Jari tengah + jempol (tahan > threshold)
    LONG_PRESS_END,     // Jari tengah + jempol dilepas setelah long press
    BACK,               // Jari manis + jempol
    HOME,               // Kelingking + jempol (singkat)
    RECENT_APPS,        // Kelingking + jempol (tahan > threshold)
    NOTIFICATIONS,      // Kelingking + jempol (tahan lebih lama)
    SWIPE_START,        // Telunjuk + jempol mulai pinch
    SWIPING,            // Telunjuk + jempol masih ditahan
    SWIPE_END,          // Telunjuk + jempol dilepas
    HAND_LOST           // Tangan tidak terdeteksi
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

    private val SENSITIVITY_X = 2.0f
    private val SENSITIVITY_Y = 2.5f
    private val SWIPE_AMPLIFY = 2.5f

    // Timing thresholds (ms)
    private val LONG_PRESS_THRESHOLD = 500L
    private val RECENT_APPS_THRESHOLD = 500L

    private var handLandmarker: HandLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val screenWidth by lazy { context.resources.displayMetrics.widthPixels.toFloat() }
    private val screenHeight by lazy { context.resources.displayMetrics.heightPixels.toFloat() }

    private val filterX = OneEuroFilter(minCutoff = MIN_CUTOFF, beta = BETA, dCutoff = DERIVATIVE_CUTOFF)
    private val filterY = OneEuroFilter(minCutoff = MIN_CUTOFF, beta = BETA, dCutoff = DERIVATIVE_CUTOFF)

    // Swipe state
    private var isSwipePrev = false

    // Click / Long Press state (jari tengah + jempol)
    private var isClickPinchPrev = false
    private var clickPinchStartTime = 0L
    private var longPressTriggered = false

    // Back state (jari manis + jempol)
    private var isBackPrev = false

    // Home / Recent Apps state (kelingking + jempol)
    private var isPinkyPinchPrev = false
    private var pinkyPinchStartTime = 0L
    private var recentAppsTriggered = false

    // Click stabilization
    private var frozenX = 0f
    private var frozenY = 0f
    private var isFrozen = false

    private var lastX = -1f
    private var lastY = -1f

    // Hand lost tracking
    private var handLostFrames = 0
    private val HAND_LOST_THRESHOLD = 5

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
            handLostFrames++

            CoroutineScope(Dispatchers.Main).launch {
                // Kalau sedang swipe lalu tangan hilang
                if (isSwipePrev) {
                    isSwipePrev = false
                    onHandUpdate(lastX, lastY, GestureType.SWIPE_END, null)
                    return@launch
                }

                // Reset pinch states
                if (isClickPinchPrev && !longPressTriggered) {
                    // Tangan hilang saat pinch singkat → click
                    isClickPinchPrev = false
                    onHandUpdate(lastX, lastY, GestureType.CLICK, null)
                    return@launch
                }
                if (isClickPinchPrev && longPressTriggered) {
                    isClickPinchPrev = false
                    longPressTriggered = false
                    onHandUpdate(lastX, lastY, GestureType.LONG_PRESS_END, null)
                    return@launch
                }
                if (isPinkyPinchPrev && !recentAppsTriggered) {
                    isPinkyPinchPrev = false
                    onHandUpdate(lastX, lastY, GestureType.HOME, null)
                    return@launch
                }
                isPinkyPinchPrev = false
                recentAppsTriggered = false
                isBackPrev = false

                val gesture = if (handLostFrames >= HAND_LOST_THRESHOLD) GestureType.HAND_LOST else GestureType.NONE
                onHandUpdate(lastX, lastY, gesture, null)
            }
            return
        }

        handLostFrames = 0
        val now = SystemClock.uptimeMillis()

        val landmarks = result.landmarks()[0]
        val indexTip = landmarks[8]     // Telunjuk
        val thumbTip = landmarks[4]     // Jempol
        val middleTip = landmarks[12]   // Jari tengah
        val ringTip = landmarks[16]     // Jari manis
        val pinkyTip = landmarks[20]    // Kelingking

        // --- Cursor Position ---
        val mirroredX = 1f - indexTip.x()
        val mappedY = indexTip.y()
        val scaledX = ((mirroredX - 0.5f) * SENSITIVITY_X + 0.5f).coerceIn(0f, 1f)
        val scaledY = ((mappedY - 0.5f) * SENSITIVITY_Y + 0.5f).coerceIn(0f, 1f)
        val targetPixelX = scaledX * screenWidth
        val targetPixelY = scaledY * screenHeight

        val timestamp = System.nanoTime() / 1_000_000_000.0
        val smoothX = filterX.filter(targetPixelX, timestamp)
        val smoothY = filterY.filter(targetPixelY, timestamp)

        // --- Pinch Distances ---
        fun pinchDist(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                      b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
            return hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble()).toFloat()
        }

        val swipeDist = pinchDist(indexTip, thumbTip)
        val clickDist = pinchDist(middleTip, thumbTip)
        val backDist = pinchDist(ringTip, thumbTip)
        val pinkyDist = pinchDist(pinkyTip, thumbTip)

        val pinchThreshold = 0.06f

        // Priority: swipe > click/longpress > back > home/recents
        val isSwipePinch = swipeDist < pinchThreshold
        val isClickPinch = clickDist < pinchThreshold && !isSwipePinch
        val isBackPinch = backDist < pinchThreshold && !isSwipePinch && !isClickPinch
        val isPinkyPinch = pinkyDist < pinchThreshold && !isSwipePinch && !isClickPinch && !isBackPinch

        // --- Determine Gesture ---
        var gesture = GestureType.NONE

        // ===== SWIPE =====
        if (isSwipePinch) {
            gesture = if (!isSwipePrev) GestureType.SWIPE_START else GestureType.SWIPING
        } else if (isSwipePrev) {
            gesture = GestureType.SWIPE_END
        }

        // ===== CLICK / LONG PRESS (Jari Tengah + Jempol) =====
        if (gesture == GestureType.NONE) {
            if (isClickPinch) {
                if (!isClickPinchPrev) {
                    // Baru mulai pinch
                    clickPinchStartTime = now
                    longPressTriggered = false
                } else if (!longPressTriggered && (now - clickPinchStartTime) >= LONG_PRESS_THRESHOLD) {
                    // Sudah ditahan cukup lama → long press
                    gesture = GestureType.LONG_PRESS_START
                    longPressTriggered = true
                }
                // Jangan kirim gesture saat masih dalam "waiting" period
            } else if (isClickPinchPrev) {
                // Baru dilepas
                if (longPressTriggered) {
                    gesture = GestureType.LONG_PRESS_END
                    longPressTriggered = false
                } else {
                    // Dilepas sebelum threshold → tap/click
                    gesture = GestureType.CLICK
                }
            }
        }

        // ===== BACK (Jari Manis + Jempol) =====
        if (gesture == GestureType.NONE) {
            if (isBackPinch && !isBackPrev) {
                gesture = GestureType.BACK
            }
        }

        // ===== HOME / RECENT APPS (Kelingking + Jempol) =====
        if (gesture == GestureType.NONE) {
            if (isPinkyPinch) {
                if (!isPinkyPinchPrev) {
                    pinkyPinchStartTime = now
                    recentAppsTriggered = false
                } else if (!recentAppsTriggered && (now - pinkyPinchStartTime) >= RECENT_APPS_THRESHOLD) {
                    gesture = GestureType.RECENT_APPS
                    recentAppsTriggered = true
                }
            } else if (isPinkyPinchPrev) {
                if (!recentAppsTriggered) {
                    gesture = GestureType.HOME
                }
                recentAppsTriggered = false
            }
        }

        // --- Stabilization ---
        val shouldFreeze = isClickPinch || isPinkyPinch
        if (shouldFreeze && !isFrozen) {
            isFrozen = true
            frozenX = smoothX
            frozenY = smoothY
        } else if (!shouldFreeze) {
            isFrozen = false
        }

        val finalX = if (isFrozen) frozenX else smoothX
        val finalY = if (isFrozen) frozenY else smoothY

        lastX = finalX
        lastY = finalY
        isSwipePrev = isSwipePinch
        isClickPinchPrev = isClickPinch
        isBackPrev = isBackPinch
        isPinkyPinchPrev = isPinkyPinch

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
