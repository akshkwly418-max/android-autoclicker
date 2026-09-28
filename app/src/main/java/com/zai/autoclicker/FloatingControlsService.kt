package com.zai.autoclicker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zai.autoclicker.AutoClickService.Controller
import com.zai.autoclicker.databinding.FloatingControlsBinding

/**
 * Foreground service that draws a floating circle on top of every other app.
 *
 * The floating control lets the user start and stop the auto clicker without
 * having to come back to the [MainActivity]. The user can drag the circle
 * anywhere on the screen; a quick tap toggles the clicker state.
 *
 * The service runs as `specialUse` (declared in the manifest) because it does
 * not match any of the strict foreground-service categories that Android 14+
 * recognises, but it must stay alive while the user is interacting with
 * another app.
 */
class FloatingControlsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var binding: FloatingControlsBinding
    private var floatingView: View? = null

    private val stateListener: (Boolean) -> Unit = { running ->
        binding.floatingStatus.text = if (running) "STOP" else "START"
        binding.floatingLabel.text = getString(R.string.app_name).take(4)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingButton()
        Controller.register(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FROM_NOTIFICATION) {
            Controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Controller.unregister(stateListener)
        floatingView?.let { windowManager.removeView(it) }
        floatingView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        binding = FloatingControlsBinding.inflate(LayoutInflater.from(this))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Resources.getSystem().displayMetrics.widthPixels - dp(120)
            y = dp(180)
        }

        // Touch listener that distinguishes between a drag and a tap.
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) moved = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(binding.root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (Controller.isRunning()) {
                            Controller.stop()
                        } else {
                            // The MainActivity is responsible for starting the
                            // tap loop with valid config; if the user pressed
                            // START on the floating button without ever opening
                            // the app we just bounce them back there.
                            val launch = Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launch)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(binding.root, params)
        floatingView = binding.root
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingControlsService::class.java)
                .setAction(ACTION_STOP_FROM_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_controls"
        private const val ACTION_STOP_FROM_NOTIFICATION = "STOP_FROM_NOTIFICATION"

        fun launch(context: Context) {
            val intent = Intent(context, FloatingControlsService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlsService::class.java))
        }
    }
}
