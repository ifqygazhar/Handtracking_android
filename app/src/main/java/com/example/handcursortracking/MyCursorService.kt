package com.example.handcursortracking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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
    private val handler = Handler(Looper.getMainLooper())

    private var cursorView: View? = null
    private var cursorDot: View? = null
    private var cursorRing: View? = null
    private var cameraContainer: FrameLayout? = null
    private var overlayView: HandOverlayView? = null
    private var gestureLabel: TextView? = null
    private var handTracker: HandTracker? = null
    private var isCameraPipVisible = false

    // Swipe tracking
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    // Gesture label auto-hide
    private val hideLabelRunnable = Runnable {
        gestureLabel?.visibility = View.GONE
    }

    // BroadcastReceiver for PIP toggle from MainActivity
    private val pipToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val showPip = intent?.getBooleanExtra("show_pip", false) ?: false
            toggleCameraPip(showPip)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Read PIP preference (hidden by default)
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        isCameraPipVisible = prefs.getBoolean(MainActivity.KEY_SHOW_CAMERA_PIP, false)

        setupCameraDebugWindow()
        setupCursorWindow()
        setupGestureLabel()

        // Register broadcast receiver for PIP toggle
        val filter = IntentFilter("com.example.handcursortracking.TOGGLE_PIP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipToggleReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(pipToggleReceiver, filter)
        }

        val previewView = cameraContainer?.getChildAt(0) as? PreviewView

        handTracker = HandTracker(this, this) { x, y, gesture, result ->
            if (x >= 0 && y >= 0) {
                updateCursorPosition(x, y)
            }

            if (result != null && result.landmarks().isNotEmpty()) {
                overlayView?.setLandmarks(result.landmarks()[0])
            } else {
                overlayView?.setLandmarks(null)
            }

            handleGesture(gesture, x, y)
        }

        handTracker?.start(previewView?.surfaceProvider)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun toggleCameraPip(show: Boolean) {
        isCameraPipVisible = show
        cameraContainer?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun handleGesture(gesture: GestureType, x: Float, y: Float) {
        when (gesture) {
            GestureType.CLICK -> {
                if (x >= 0 && y >= 0) {
                    performClick(x, y)
                    showGestureLabel("TAP", Color.RED)
                    setCursorColor(Color.RED)
                    resetCursorColorDelayed(200)
                }
            }

            GestureType.LONG_PRESS_START -> {
                if (x >= 0 && y >= 0) {
                    performLongPress(x, y)
                    showGestureLabel("LONG PRESS", 0xFFFF6600.toInt())
                    setCursorColor(0xFFFF6600.toInt())
                }
            }

            GestureType.LONG_PRESS_END -> {
                setCursorColor(Color.CYAN)
            }

            GestureType.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                showGestureLabel("← BACK", Color.MAGENTA)
                setCursorColor(Color.MAGENTA)
                resetCursorColorDelayed(300)
            }

            GestureType.HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                showGestureLabel("⌂ HOME", Color.GREEN)
                setCursorColor(Color.GREEN)
                resetCursorColorDelayed(300)
            }

            GestureType.RECENT_APPS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                showGestureLabel("☐ RECENTS", 0xFF00BFFF.toInt())
                setCursorColor(0xFF00BFFF.toInt())
                resetCursorColorDelayed(400)
            }

            GestureType.NOTIFICATIONS -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                showGestureLabel("🔔 NOTIFICATIONS", 0xFFFFD700.toInt())
                setCursorColor(0xFFFFD700.toInt())
                resetCursorColorDelayed(400)
            }

            GestureType.SWIPE_START -> {
                swipeStartX = x
                swipeStartY = y
                showGestureLabel("SWIPE...", Color.YELLOW)
                setCursorColor(Color.YELLOW)
            }

            GestureType.SWIPING -> {
                // Cursor tetap kuning selama swipe
            }

            GestureType.SWIPE_END -> {
                if (x >= 0 && y >= 0) {
                    performSwipe(swipeStartX, swipeStartY, x, y)
                    showGestureLabel("SWIPE ✓", Color.GREEN)
                }
                setCursorColor(Color.CYAN)
            }

            GestureType.HAND_LOST -> {
                setCursorAlpha(80)
            }

            GestureType.NONE -> {
                setCursorAlpha(255)
            }
        }
    }

    // ==================== UI SETUP ====================

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

        // Apply initial visibility from preferences
        cameraContainer?.visibility = if (isCameraPipVisible) View.VISIBLE else View.GONE
    }

    private fun setupCursorWindow() {
        // Container for cursor components
        val container = FrameLayout(this)

        // Outer ring (glow effect)
        cursorRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(3, Color.CYAN)
                setColor(Color.TRANSPARENT)
            }
        }
        container.addView(cursorRing, FrameLayout.LayoutParams(40, 40).apply {
            gravity = Gravity.CENTER
        })

        // Inner dot (precise click point)
        cursorDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.CYAN)
            }
        }
        container.addView(cursorDot, FrameLayout.LayoutParams(14, 14).apply {
            gravity = Gravity.CENTER
        })

        cursorView = container

        val params = WindowManager.LayoutParams(
            48, 48,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(cursorView, params)
    }

    private fun setupGestureLabel() {
        gestureLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(0xCC000000.toInt())
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 100

        windowManager.addView(gestureLabel, params)
    }

    // ==================== CURSOR HELPERS ====================

    private fun setCursorColor(color: Int) {
        (cursorDot?.background as? GradientDrawable)?.setColor(color)
        (cursorRing?.background as? GradientDrawable)?.setStroke(3, color)
    }

    private fun setCursorAlpha(alpha: Int) {
        cursorView?.alpha = alpha / 255f
    }

    private fun resetCursorColorDelayed(delayMs: Long) {
        handler.postDelayed({ setCursorColor(Color.CYAN) }, delayMs)
    }

    private fun updateCursorPosition(x: Float, y: Float) {
        cursorView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.x = x.toInt() - 24  // Center the 48px cursor
            params.y = y.toInt() - 24
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    private fun showGestureLabel(text: String, color: Int) {
        gestureLabel?.let { label ->
            label.text = text
            label.setTextColor(color)
            label.visibility = View.VISIBLE

            handler.removeCallbacks(hideLabelRunnable)
            handler.postDelayed(hideLabelRunnable, 1000)
        }
    }

    // ==================== GESTURE ACTIONS ====================

    private fun performClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {}
        }, null)
    }

    private fun performLongPress(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {}
        }, null)
    }

    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val amplify = 2.5f
        val centerX = (startX + endX) / 2f
        val centerY = (startY + endY) / 2f
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val ampStartX = (centerX + (startX - centerX) * amplify).coerceIn(0f, screenW)
        val ampStartY = (centerY + (startY - centerY) * amplify).coerceIn(0f, screenH)
        val ampEndX = (centerX + (endX - centerX) * amplify).coerceIn(0f, screenW)
        val ampEndY = (centerY + (endY - centerY) * amplify).coerceIn(0f, screenH)

        val path = Path()
        path.moveTo(ampStartX, ampStartY)
        path.lineTo(ampEndX, ampEndY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {}
        }, null)
    }

    // ==================== LIFECYCLE ====================

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        handTracker?.stop()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(pipToggleReceiver)
        } catch (_: Exception) {}
        try {
            if (cursorView != null) windowManager.removeView(cursorView)
            if (cameraContainer != null) windowManager.removeView(cameraContainer)
            if (gestureLabel != null) windowManager.removeView(gestureLabel)
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ==================== HAND OVERLAY ====================

    class HandOverlayView(context: Context) : View(context) {
        private var landmarks: List<NormalizedLandmark>? = null

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

        private val connections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            0 to 9, 9 to 10, 10 to 11, 11 to 12,
            0 to 13, 13 to 14, 14 to 15, 15 to 16,
            0 to 17, 17 to 18, 18 to 19, 19 to 20,
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
                        12 -> indexPaint
                        else -> pointPaint
                    }
                    canvas.drawCircle(px, py, if (i == 8 || i == 12) 10f else if (i == 4) 8f else 5f, paint)
                }
            }
        }
    }
}
