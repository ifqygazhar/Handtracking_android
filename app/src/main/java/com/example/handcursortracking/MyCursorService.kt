package com.example.handcursortracking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class MyCursorService : AccessibilityService(), LifecycleOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var lifecycleRegistry: LifecycleRegistry

    private var cursorView: ImageView? = null
    private var cameraContainer: FrameLayout? = null
    private var overlayView: HandOverlayView? = null
    private var handTracker: HandTracker? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupCameraDebugWindow()
        setupCursorWindow()

        val previewView = cameraContainer?.getChildAt(0) as? PreviewView

        handTracker = HandTracker(this, this) { x, y, isClicking, result ->
            if (x >= 0 && y >= 0) {
                updateCursorPosition(x, y)
            }

            if (result != null && result.landmarks().isNotEmpty()) {
                overlayView?.setLandmarks(result.landmarks()[0])
            } else {
                overlayView?.setLandmarks(null)
            }

            if (isClicking && x >= 0 && y >= 0) performClick(x, y)
        }

        handTracker?.start(previewView?.surfaceProvider)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun setupCameraDebugWindow() {
        cameraContainer = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame)
            clipToOutline = true
        }

        val previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        cameraContainer?.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        overlayView = HandOverlayView(this)
        cameraContainer?.addView(overlayView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            360, 480,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.x = 20
        params.y = 20

        windowManager.addView(cameraContainer, params)
    }

    private fun setupCursorWindow() {
        cursorView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_input_get)
            setColorFilter(Color.CYAN)
            rotation = -45f
        }

        val params = WindowManager.LayoutParams(
            60, 60,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(cursorView, params)
    }

    private fun updateCursorPosition(x: Float, y: Float) {
        cursorView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.x = x.toInt()
            params.y = y.toInt()
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun performClick(x: Float, y: Float) {
        cursorView?.setColorFilter(Color.RED)
        cursorView?.postDelayed({ cursorView?.setColorFilter(Color.CYAN) }, 200)

        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        handTracker?.stop()
        if (cursorView != null) windowManager.removeView(cursorView)
        if (cameraContainer != null) windowManager.removeView(cameraContainer)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    class HandOverlayView(context: Context) : View(context) {
        private var landmarks: List<NormalizedLandmark>? = null

        // Rasio kamera portrait setelah rotasi: 3:4
        private val cameraAspectRatio = 3f / 4f

        private val pointPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 10f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val indexPaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 16f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val thumbPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 14f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val linePaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        // Hand connections (MediaPipe hand landmark topology)
        private val connections = listOf(
            // Thumb
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            // Index
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            // Middle
            0 to 9, 9 to 10, 10 to 11, 11 to 12,
            // Ring
            0 to 13, 13 to 14, 14 to 15, 15 to 16,
            // Pinky
            0 to 17, 17 to 18, 18 to 19, 19 to 20,
            // Palm
            5 to 9, 9 to 13, 13 to 17
        )

        fun setLandmarks(newLandmarks: List<NormalizedLandmark>?) {
            landmarks = newLandmarks
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val viewRatio = viewWidth / viewHeight

            // Hitung area gambar yang sebenarnya (FIT_CENTER)
            var imageWidth = viewWidth
            var imageHeight = viewHeight

            if (viewRatio > cameraAspectRatio) {
                imageWidth = viewHeight * cameraAspectRatio
            } else {
                imageHeight = viewWidth / cameraAspectRatio
            }

            val offsetX = (viewWidth - imageWidth) / 2f
            val offsetY = (viewHeight - imageHeight) / 2f

            landmarks?.let { marks ->
                if (marks.size < 21) return@let


                fun mapX(normX: Float): Float = offsetX + (1f - normX) * imageWidth
                fun mapY(normY: Float): Float = offsetY + normY * imageHeight


                for ((start, end) in connections) {
                    val s = marks[start]
                    val e = marks[end]
                    canvas.drawLine(
                        mapX(s.x()), mapY(s.y()),
                        mapX(e.x()), mapY(e.y()),
                        linePaint
                    )
                }


                for ((i, landmark) in marks.withIndex()) {
                    val px = mapX(landmark.x())
                    val py = mapY(landmark.y())

                    val paint = when (i) {
                        8 -> indexPaint
                        4 -> thumbPaint
                        else -> pointPaint
                    }
                    canvas.drawCircle(px, py, if (i == 8) 10f else if (i == 4) 8f else 5f, paint)
                }
            }
        }
    }
}
