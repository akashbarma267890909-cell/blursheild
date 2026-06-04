package com.blureshield.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * OverlayService — runs as a Foreground Service.
 *
 * Manages the lifecycle of the floating BubbleView and BlurOverlayView.
 * Both views are added directly to the system WindowManager so they
 * appear on top of ALL other applications.
 *
 * The service must be a foreground service (with a visible notification)
 * or Android will kill it after a few seconds.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    // The draggable floating bubble (always visible while service is running)
    private var bubbleView: BubbleView? = null

    // The frosted-glass blur rectangle (toggled by tapping the bubble)
    private var blurOverlayView: BlurOverlayView? = null

    // Whether the blur overlay is currently visible
    private var isBlurVisible = false

    // Broadcast receiver for settings changes from MainActivity
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_SETTINGS) {
                applyCurrentSettings()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // MUST call startForeground() immediately — Android 8+ requirement
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Register for settings change broadcasts
        val filter = IntentFilter(ACTION_UPDATE_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }

        // Add bubble to screen
        addBubbleToScreen()

        // Notify MainActivity that protection is now active
        broadcastStatus(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures Android restarts the service if it's killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // Remove all views from WindowManager cleanly
        removeBubbleFromScreen()
        hideBlurOverlay()

        try {
            unregisterReceiver(settingsReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered, ignore
        }

        // Notify MainActivity that protection is now inactive
        broadcastStatus(false)
        prefs.edit().putBoolean(MainActivity.PREF_IS_ACTIVE, false).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Bubble management
    // -------------------------------------------------------------------------

    /**
     * Creates the BubbleView and adds it to WindowManager.
     * The bubble floats over all other apps at all times while the service runs.
     */
    private fun addBubbleToScreen() {
        if (bubbleView != null) return  // Already added

        val bubbleSizePx = dpToPx(BUBBLE_SIZE_DP)

        val params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Correct type for Android 8+
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or     // Don't steal keyboard focus
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // Start position: right side of screen, vertically centered
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        params.x = screenWidth / 2 - bubbleSizePx / 2 - dpToPx(8)
        params.y = 0  // WindowManager uses offset from gravity, not absolute

        // Use top-left gravity so x/y are from top-left corner
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.x = screenWidth - bubbleSizePx - dpToPx(16)
        params.y = screenHeight / 3

        val savedColor = prefs.getString(MainActivity.PREF_BUBBLE_COLOR, MainActivity.COLOR_BLUE)
            ?: MainActivity.COLOR_BLUE

        bubbleView = BubbleView(this, savedColor).apply {
            // When bubble is tapped (short click), toggle the blur overlay
            onTap = {
                if (isBlurVisible) hideBlurOverlay() else showBlurOverlay()
            }
        }

        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeBubbleFromScreen() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bubbleView = null
        }
    }

    // -------------------------------------------------------------------------
    // Blur overlay management
    // -------------------------------------------------------------------------

    /**
     * Creates the BlurOverlayView and adds it to WindowManager.
     * The overlay appears behind the bubble (lower z-order because bubble was added first).
     * Users drag it to cover whatever they want to hide.
     */
    fun showBlurOverlay() {
        if (blurOverlayView != null) {
            // Already shown — make sure it's visible
            isBlurVisible = true
            return
        }

        val blurIntensity = prefs.getInt(MainActivity.PREF_BLUR_INTENSITY, MainActivity.DEFAULT_BLUR_INTENSITY)
        val sizePercent = prefs.getInt(MainActivity.PREF_BOX_SIZE, MainActivity.DEFAULT_BOX_SIZE)

        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()

        // Calculate initial size from the size percentage setting
        val initialWidth = (screenWidth * sizePercent / 100).coerceAtLeast(dpToPx(100))
        val initialHeight = (initialWidth * 2 / 3).coerceAtLeast(dpToPx(80))  // 3:2 aspect ratio default

        val params = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START

        // Position: center of screen
        params.x = (screenWidth - initialWidth) / 2
        params.y = (screenHeight - initialHeight) / 2

        blurOverlayView = BlurOverlayView(this, blurIntensity.toFloat())

        try {
            windowManager.addView(blurOverlayView, params)
            isBlurVisible = true
        } catch (e: Exception) {
            e.printStackTrace()
            blurOverlayView = null
        }
    }

    fun hideBlurOverlay() {
        blurOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            blurOverlayView = null
        }
        isBlurVisible = false
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    /**
     * Called when MainActivity broadcasts a settings change.
     * Updates blur intensity and bubble color in real time.
     */
    private fun applyCurrentSettings() {
        val blurIntensity = prefs.getInt(MainActivity.PREF_BLUR_INTENSITY, MainActivity.DEFAULT_BLUR_INTENSITY)
        val sizePercent = prefs.getInt(MainActivity.PREF_BOX_SIZE, MainActivity.DEFAULT_BOX_SIZE)
        val color = prefs.getString(MainActivity.PREF_BUBBLE_COLOR, MainActivity.COLOR_BLUE)
            ?: MainActivity.COLOR_BLUE

        // Update blur radius on the overlay if it's visible
        blurOverlayView?.setBlurRadius(blurIntensity.toFloat())

        // Resize overlay if it's visible
        if (blurOverlayView != null) {
            try {
                val lp = blurOverlayView!!.layoutParams as? WindowManager.LayoutParams
                if (lp != null) {
                    val screenWidth = getScreenWidth()
                    lp.width = (screenWidth * sizePercent / 100).coerceAtLeast(dpToPx(100))
                    lp.height = (lp.width * 2 / 3).coerceAtLeast(dpToPx(80))
                    windowManager.updateViewLayout(blurOverlayView, lp)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Update bubble color
        bubbleView?.updateColor(color)
    }

    // -------------------------------------------------------------------------
    // Notification (required for foreground service)
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BlurShield Protection",
            NotificationManager.IMPORTANCE_LOW  // Low importance = no sound, no popup
        ).apply {
            description = "BlurShield floating privacy overlay is running"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tapping the notification brings user back to MainActivity
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action directly from notification
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("BlurShield Active")
            .setContentText("Privacy overlay is running. Tap bubble on screen to toggle blur.")
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)        // Cannot be swiped away
            .setSilent(true)         // No sound
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers
    // -------------------------------------------------------------------------

    private fun broadcastStatus(isActive: Boolean) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_ACTIVE, isActive)
        }
        sendBroadcast(intent)
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.width
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.height
        }
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "blureshield_channel"
        const val NOTIFICATION_ID = 1001
        const val BUBBLE_SIZE_DP = 56

        // Intent actions
        const val ACTION_UPDATE_SETTINGS = "com.blureshield.app.UPDATE_SETTINGS"
        const val ACTION_STATUS_UPDATE = "com.blureshield.app.STATUS_UPDATE"
        const val ACTION_STOP = "com.blureshield.app.STOP"

        // Intent extras
        const val EXTRA_IS_ACTIVE = "is_active"
    }
}
