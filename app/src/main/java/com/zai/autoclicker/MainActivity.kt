package com.zai.autoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zai.autoclicker.AutoClickService.Controller
import com.zai.autoclicker.databinding.ActivityMainBinding

/**
 * The single activity that ships with the Auto Clicker app.
 *
 * The activity is a thin orchestrator around three concerns:
 *   1. **Permissions** — overlay (draw over other apps) and accessibility
 *      service. Both must be granted before the user can start tapping.
 *   2. **Tap configuration** — interval, duration, repeat count, position.
 *   3. **Start / stop** — once permissions are granted, the activity launches
 *      the [FloatingControlsService] so the user can toggle the clicker from
 *      anywhere in the system without returning to this activity.
 *
 * All settings are written to SharedPreferences so they persist across launches.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStatus()
        }

    private val stateListener: (Boolean) -> Unit = { running ->
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.textRunStatus.text = getString(
            if (running) R.string.status_running else R.string.status_stopped
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wireControls()
        refreshPermissionStatus()
        loadPersistedSettings()
    }

    override fun onResume() {
        super.onResume()
        // The accessibility permission can be revoked from system settings at
        // any time, so we re-check it whenever the activity is resumed.
        refreshPermissionStatus()
        Controller.register(stateListener)
    }

    override fun onPause() {
        super.onPause()
        Controller.unregister(stateListener)
    }

    private fun wireControls() {
        // --- Interval slider ----------------------------------------------------
        binding.seekInterval.setOnSeekBarChangeListener(SimpleProgressListener { progress ->
            val ms = progress + 100 // range: 100..5000 ms
            binding.textInterval.text = "$ms ms"
            prefs().edit().putInt(PREF_INTERVAL, ms).apply()
        })
        // --- Duration slider ---------------------------------------------------
        binding.seekDuration.setOnSeekBarChangeListener(SimpleProgressListener { progress ->
            val ms = progress + 1 // range: 1..500 ms
            binding.textDuration.text = "$ms ms"
            prefs().edit().putInt(PREF_DURATION, ms).apply()
        })
        // --- Repeat slider -----------------------------------------------------
        binding.seekRepeat.setOnSeekBarChangeListener(SimpleProgressListener { progress ->
            val count = progress // 0 = infinite
            binding.textRepeat.text = if (count == 0) "∞" else count.toString()
            prefs().edit().putInt(PREF_REPEAT, count).apply()
        })

        // --- Position mode ------------------------------------------------------
        binding.positionMode.setOnCheckedChangeListener { _, id ->
            val custom = id == R.id.radioCustom
            binding.coordsRow.visibility = if (custom) android.view.View.VISIBLE else android.view.View.GONE
            prefs().edit().putBoolean(PREF_CUSTOM_POS, custom).apply()
        }

        // --- Buttons -----------------------------------------------------------
        binding.btnStart.setOnClickListener { tryStart() }
        binding.btnStop.setOnClickListener {
            Controller.stop()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayLauncher.launch(intent)
            } else {
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun tryStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_required, Toast.LENGTH_LONG).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_accessibility_required, Toast.LENGTH_LONG).show()
            return
        }

        val custom = binding.positionMode.checkedRadioButtonId == R.id.radioCustom
        val x: Int
        val y: Int
        if (custom) {
            x = binding.editX.text?.toString()?.toIntOrNull() ?: -1
            y = binding.editY.text?.toString()?.toIntOrNull() ?: -1
            if (x < 0 || y < 0) {
                Toast.makeText(this, R.string.toast_invalid_position, Toast.LENGTH_LONG).show()
                return
            }
            prefs().edit().putInt(PREF_X, x).putInt(PREF_Y, y).apply()
        } else {
            // Use screen centre as a sensible default for the "last touch"
            // mode — the accessibility service cannot reliably know the
            // user's last touch point on another app, so we tap the centre
            // of the screen instead.
            val dm = resources.displayMetrics
            x = dm.widthPixels / 2
            y = dm.heightPixels / 2
        }

        val config = AutoClickService.TapConfig(
            x = x,
            y = y,
            interval = binding.seekInterval.progress + 100,
            duration = (binding.seekDuration.progress + 1).coerceAtLeast(1),
            repeat = binding.seekRepeat.progress,
        )

        val started = Controller.start(config)
        if (started) {
            FloatingControlsService.launch(this)
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
            // Bring the user back to the home screen so the floating button
            // becomes visible immediately.
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
        } else {
            Toast.makeText(this, R.string.toast_accessibility_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPermissionStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val a11yGranted = isAccessibilityEnabled()
        binding.textOverlayStatus.text = getString(
            R.string.status_permission_overlay,
            getString(if (overlayGranted) R.string.permission_granted else R.string.permission_missing)
        )
        binding.textAccessibilityStatus.text = getString(
            R.string.status_permission_accessibility,
            getString(if (a11yGranted) R.string.permission_granted else R.string.permission_missing)
        )
        binding.btnStart.isEnabled = overlayGranted && a11yGranted && !Controller.isRunning()
        binding.btnStop.isEnabled = Controller.isRunning()
        binding.btnGrantOverlay.isEnabled = !overlayGranted
        binding.btnGrantAccessibility.isEnabled = !a11yGranted
    }

    private fun loadPersistedSettings() {
        val prefs = prefs()
        binding.seekInterval.progress = (prefs.getInt(PREF_INTERVAL, 500) - 100).coerceIn(0, 4900)
        binding.seekDuration.progress = (prefs.getInt(PREF_DURATION, 20) - 1).coerceIn(0, 500)
        binding.seekRepeat.progress = prefs.getInt(PREF_REPEAT, 0).coerceIn(0, 50)
        val custom = prefs.getBoolean(PREF_CUSTOM_POS, false)
        binding.positionMode.check(if (custom) R.id.radioCustom else R.id.radioLastTouch)
        binding.coordsRow.visibility = if (custom) android.view.View.VISIBLE else android.view.View.GONE
        binding.editX.setText(prefs.getInt(PREF_X, 540).toString())
        binding.editY.setText(prefs.getInt(PREF_Y, 960).toString())

        binding.textInterval.text = "${binding.seekInterval.progress + 100} ms"
        binding.textDuration.text = "${binding.seekDuration.progress + 1} ms"
        binding.textRepeat.text = if (binding.seekRepeat.progress == 0) "∞" else binding.seekRepeat.progress.toString()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = packageName
        return enabled.any {
            it.resolveInfo.serviceInfo.packageName.equals(target, ignoreCase = true)
        }
    }

    private fun prefs() =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class SimpleProgressListener(val onProgress: (Int) -> Unit) :
        SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            seekBar?.progress?.let(onProgress)
        }
    }

    companion object {
        private const val PREFS_NAME = "autoclicker_prefs"
        private const val PREF_INTERVAL = "interval"
        private const val PREF_DURATION = "duration"
        private const val PREF_REPEAT = "repeat"
        private const val PREF_CUSTOM_POS = "custom_position"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
    }
}
