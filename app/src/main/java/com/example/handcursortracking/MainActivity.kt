package com.example.handcursortracking

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnCameraPermission: Button
    private lateinit var switchCameraPip: MaterialSwitch
    private lateinit var prefs: SharedPreferences

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

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        const val PREFS_NAME = "hand_cursor_prefs"
        const val KEY_SHOW_CAMERA_PIP = "show_camera_pip"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission)
        btnCameraPermission = findViewById(R.id.btnCameraPermission)
        switchCameraPip = findViewById(R.id.switchCameraPip)

        setupGestureCards()
        setupButtons()
        setupCameraPipToggle()

        // Prioritize Camera Permission on launch
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
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

        btnCameraPermission.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun setupCameraPipToggle() {
        val isEnabled = prefs.getBoolean(KEY_SHOW_CAMERA_PIP, false)
        switchCameraPip.isChecked = isEnabled

        switchCameraPip.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_CAMERA_PIP, isChecked).apply()

            // Notify the service to toggle PIP visibility
            val intent = Intent("com.example.handcursortracking.TOGGLE_PIP")
            intent.putExtra("show_pip", isChecked)
            sendBroadcast(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val serviceEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val cameraEnabled = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        // Camera permission button
        btnCameraPermission.visibility = if (cameraEnabled) View.GONE else View.VISIBLE

        // Overlay permission button
        btnOverlayPermission.visibility = if (overlayEnabled) View.GONE else View.VISIBLE

        // Status
        if (serviceEnabled && overlayEnabled && cameraEnabled) {
            statusDot.setBackgroundResource(R.drawable.status_dot_green)
            statusText.text = "Hand tracking is active"
            btnToggleService.text = "Settings"
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_red)
            val missing = mutableListOf<String>()
            if (!cameraEnabled) missing.add("Camera")
            if (!overlayEnabled) missing.add("Overlay")
            if (!serviceEnabled) missing.add("Accessibility")
            statusText.text = "Missing: ${missing.joinToString(", ")}"
            btnToggleService.text = "Enable"
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
