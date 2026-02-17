package com.example.handcursortracking

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnOverlayPermission: Button

    data class GestureInfo(
        val emoji: String,
        val name: String,
        val fingers: String,
        val desc: String
    )

    private val gestures = listOf(
        GestureInfo("☝️", "Move Cursor", "Index finger", "Point with your index finger to move the cursor around the screen"),
        GestureInfo("👌", "Tap / Click", "Middle + Thumb pinch", "Quick pinch to tap at the cursor position. Opens apps, buttons, links"),
        GestureInfo("✊", "Long Press", "Middle + Thumb hold >0.5s", "Hold the pinch for 500ms to trigger a long press. Context menus, drag icons"),
        GestureInfo("👆", "Swipe / Scroll", "Index + Thumb pinch & drag", "Pinch, hold, move your hand, then release. Scrolls pages, swipes between screens"),
        GestureInfo("👈", "Back", "Ring + Thumb pinch", "Quick pinch to go back. Same as Android back button"),
        GestureInfo("🏠", "Home", "Pinky + Thumb pinch", "Quick pinch to go to home screen"),
        GestureInfo("📱", "Recent Apps", "Pinky + Thumb hold >0.5s", "Hold the pinch to open app switcher")
    )

    private val gestureViewIds = listOf(
        R.id.gestureCursor,
        R.id.gestureTap,
        R.id.gestureLongPress,
        R.id.gestureSwipe,
        R.id.gestureBack,
        R.id.gestureHome,
        R.id.gestureRecents
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission)

        setupGestureCards()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupGestureCards() {
        for (i in gestures.indices) {
            val cardView = findViewById<View>(gestureViewIds[i])
            val gesture = gestures[i]

            cardView.findViewById<TextView>(R.id.gestureEmoji).text = gesture.emoji
            cardView.findViewById<TextView>(R.id.gestureName).text = gesture.name
            cardView.findViewById<TextView>(R.id.gestureFingers).text = gesture.fingers
            cardView.findViewById<TextView>(R.id.gestureDesc).text = gesture.desc
        }
    }

    private fun setupButtons() {
        btnToggleService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun updateStatus() {
        val serviceEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)

        if (serviceEnabled && overlayEnabled) {
            statusDot.setBackgroundResource(R.drawable.status_dot_green)
            statusText.text = "Hand tracking is active"
            btnToggleService.text = "Settings"
            btnOverlayPermission.visibility = View.GONE
        } else if (!overlayEnabled) {
            statusDot.setBackgroundResource(R.drawable.status_dot_red)
            statusText.text = "Overlay permission required"
            btnToggleService.text = "Enable"
            btnOverlayPermission.visibility = View.VISIBLE
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_red)
            statusText.text = "Accessibility service not enabled"
            btnToggleService.text = "Enable"
            btnOverlayPermission.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${MyCursorService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }
}
