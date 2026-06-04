package com.blureshield.app

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.blureshield.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // Broadcast receiver that listens for status updates from OverlayService
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_STATUS_UPDATE) {
                val isActive = intent.getBooleanExtra(OverlayService.EXTRA_IS_ACTIVE, false)
                updateProtectionStatus(isActive)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        setupSeekBars()
        setupColorButtons()
    }

    override fun onResume() {
        super.onResume()

        // Register receiver for service status updates
        val filter = IntentFilter(OverlayService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // Check current state and update UI
        val isActive = prefs.getBoolean(PREF_IS_ACTIVE, false)
        updateProtectionStatus(isActive)

        // If we just came back from overlay permission settings, re-check
        checkOverlayPermissionAndUpdateUI()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver wasn't registered, ignore
        }
    }

    private fun setupUI() {
        // Start / Stop button
        binding.btnToggleProtection.setOnClickListener {
            val isActive = prefs.getBoolean(PREF_IS_ACTIVE, false)
            if (isActive) {
                stopProtection()
            } else {
                startProtection()
            }
        }

        // Restore saved seek bar values
        val savedBlur = prefs.getInt(PREF_BLUR_INTENSITY, DEFAULT_BLUR_INTENSITY)
        val savedSize = prefs.getInt(PREF_BOX_SIZE, DEFAULT_BOX_SIZE)
        binding.seekBarBlur.progress = savedBlur
        binding.seekBarSize.progress = savedSize
        binding.tvBlurValue.text = savedBlur.toString()
        binding.tvSizeValue.text = "$savedSize%"
    }

    private fun setupSeekBars() {
        // Blur intensity seekbar (1–20)
        binding.seekBarBlur.max = 19  // 0-based, so 0..19 maps to 1..20
        binding.seekBarBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 1  // Ensure minimum of 1
                binding.tvBlurValue.text = value.toString()
                prefs.edit().putInt(PREF_BLUR_INTENSITY, value).apply()
                // Broadcast change to the running service
                sendSettingsBroadcast()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Box size seekbar (10–100%)
        binding.seekBarSize.max = 90  // 0-based, so 0..90 maps to 10..100
        binding.seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 10  // Ensure minimum of 10%
                binding.tvSizeValue.text = "$value%"
                prefs.edit().putInt(PREF_BOX_SIZE, value).apply()
                sendSettingsBroadcast()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupColorButtons() {
        binding.btnColorBlue.setOnClickListener {
            saveAndBroadcastColor(COLOR_BLUE)
            highlightSelectedColor(COLOR_BLUE)
        }
        binding.btnColorRed.setOnClickListener {
            saveAndBroadcastColor(COLOR_RED)
            highlightSelectedColor(COLOR_RED)
        }
        binding.btnColorGreen.setOnClickListener {
            saveAndBroadcastColor(COLOR_GREEN)
            highlightSelectedColor(COLOR_GREEN)
        }
        binding.btnColorBlack.setOnClickListener {
            saveAndBroadcastColor(COLOR_BLACK)
            highlightSelectedColor(COLOR_BLACK)
        }

        // Restore saved color selection
        val savedColor = prefs.getString(PREF_BUBBLE_COLOR, COLOR_BLUE) ?: COLOR_BLUE
        highlightSelectedColor(savedColor)
    }

    private fun saveAndBroadcastColor(color: String) {
        prefs.edit().putString(PREF_BUBBLE_COLOR, color).apply()
        sendSettingsBroadcast()
    }

    private fun highlightSelectedColor(selectedColor: String) {
        // Reset all buttons to unselected state
        val alpha = 0.4f
        binding.btnColorBlue.alpha = alpha
        binding.btnColorRed.alpha = alpha
        binding.btnColorGreen.alpha = alpha
        binding.btnColorBlack.alpha = alpha

        // Highlight selected
        when (selectedColor) {
            COLOR_BLUE -> binding.btnColorBlue.alpha = 1.0f
            COLOR_RED -> binding.btnColorRed.alpha = 1.0f
            COLOR_GREEN -> binding.btnColorGreen.alpha = 1.0f
            COLOR_BLACK -> binding.btnColorBlack.alpha = 1.0f
        }
    }

    /**
     * Sends a broadcast to OverlayService to update blur/size/color settings in real time.
     */
    private fun sendSettingsBroadcast() {
        val intent = Intent(OverlayService.ACTION_UPDATE_SETTINGS)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    /**
     * Checks if SYSTEM_ALERT_WINDOW permission is granted and updates UI accordingly.
     */
    private fun checkOverlayPermissionAndUpdateUI() {
        val hasPermission = Settings.canDrawOverlays(this)
        if (!hasPermission) {
            // If service was previously active but permission was revoked, stop it
            val wasActive = prefs.getBoolean(PREF_IS_ACTIVE, false)
            if (wasActive) {
                stopProtection()
            }
        }
    }

    /**
     * Initiates the start protection flow — checks permission first.
     */
    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            // Need to request Draw Over Apps permission
            showPermissionExplanationDialog()
        } else {
            // Permission granted — start the service
            val serviceIntent = Intent(this, OverlayService::class.java)
            startForegroundService(serviceIntent)
            prefs.edit().putBoolean(PREF_IS_ACTIVE, true).apply()
            updateProtectionStatus(true)

            // Minimize app so user can see the floating bubble over their content
            Toast.makeText(this, "BlurShield is active! Tap the floating bubble to toggle blur.", Toast.LENGTH_LONG).show()

            // Move task to back so overlay becomes visible
            moveTaskToBack(true)
        }
    }

    /**
     * Stops the overlay service and cleans up.
     */
    private fun stopProtection() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        stopService(serviceIntent)
        prefs.edit().putBoolean(PREF_IS_ACTIVE, false).apply()
        updateProtectionStatus(false)
    }

    /**
     * Shows a dialog explaining why we need the Draw Over Other Apps permission,
     * then opens system settings when the user confirms.
     */
    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(
                "BlurShield needs the \"Draw Over Other Apps\" permission to show the " +
                "floating blur overlay on top of other apps.\n\n" +
                "On the next screen:\n" +
                "1. Find \"BlurShield\" in the list\n" +
                "2. Toggle it ON\n" +
                "3. Come back to this app\n\n" +
                "This permission is only used to show the privacy blur overlay — " +
                "BlurShield never reads or records your screen content."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openOverlayPermissionSettings()
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    /**
     * Opens the system settings page where user can grant overlay permission.
     */
    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some devices don't support this direct link — fall back to general settings
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            Toast.makeText(
                this,
                "Find BlurShield in the list and enable 'Draw Over Other Apps'",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Updates all UI elements based on whether protection is active.
     */
    private fun updateProtectionStatus(isActive: Boolean) {
        if (isActive) {
            binding.tvStatusLabel.text = "PROTECTION ACTIVE"
            binding.tvStatusIndicator.text = "●"
            binding.tvStatusIndicator.setTextColor(getColor(R.color.success))
            binding.tvStatusLabel.setTextColor(getColor(R.color.success))
            binding.btnToggleProtection.text = "STOP PROTECTION"
            binding.btnToggleProtection.setBackgroundResource(R.drawable.bg_button_secondary)
        } else {
            binding.tvStatusLabel.text = "PROTECTION INACTIVE"
            binding.tvStatusIndicator.text = "●"
            binding.tvStatusIndicator.setTextColor(getColor(R.color.text_muted))
            binding.tvStatusLabel.setTextColor(getColor(R.color.text_muted))
            binding.btnToggleProtection.text = "START PROTECTION"
            binding.btnToggleProtection.setBackgroundResource(R.drawable.bg_button_primary)
        }
    }

    companion object {
        const val PREFS_NAME = "blureshield_prefs"
        const val PREF_IS_ACTIVE = "is_active"
        const val PREF_BLUR_INTENSITY = "blur_intensity"
        const val PREF_BOX_SIZE = "box_size"
        const val PREF_BUBBLE_COLOR = "bubble_color"

        const val DEFAULT_BLUR_INTENSITY = 10
        const val DEFAULT_BOX_SIZE = 50

        const val COLOR_BLUE = "blue"
        const val COLOR_RED = "red"
        const val COLOR_GREEN = "green"
        const val COLOR_BLACK = "black"
    }
}
