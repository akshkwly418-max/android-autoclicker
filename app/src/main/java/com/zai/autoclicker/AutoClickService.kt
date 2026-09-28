package com.zai.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService that performs automatic tap gestures using
 * [AccessibilityService.dispatchGesture].
 *
 * The service can be controlled from anywhere in the system through the static
 * [Controller] singleton. The MainActivity / FloatingControlsService use it to
 * start/stop tapping and to query the running state.
 *
 * Tapping is implemented by:
 *   1. Building a [Path] that ends at the requested tap coordinates.
 *   2. Creating a [GestureDescription.StrokeDescription] whose duration matches
 *      the requested tap duration.
 *   3. Dispatching the gesture via [dispatchGesture] and, on completion, posting
 *      the next tap on a [Handler] after the configured interval.
 */
class AutoClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() { performNextTap() }
    }

    @Volatile private var running = false
    private var remainingTaps = 0
    private var lastTapAt = 0L
    private var currentConfig: TapConfig? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Controller.bind(this)
        Log.i(TAG, "AutoClickService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // dispatchGesture works regardless of incoming events; we keep this
        // method so the system registers us as an event consumer.
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: Intent?): Boolean {
        Controller.unbind()
        stopInternal()
        Log.i(TAG, "AutoClickService unbound")
        return false
    }

    /** Public start entry-point. Called from [Controller.start]. */
    internal fun startInternal(config: TapConfig) {
        if (running) return
        running = true
        remainingTaps = config.repeat
        currentConfig = config
        handler.post(tickRunnable)
        Controller.notifyState(running)
    }

    /** Public stop entry-point. Called from [Controller.stop]. */
    internal fun stopInternal() {
        running = false
        remainingTaps = 0
        handler.removeCallbacks(tickRunnable)
        Controller.notifyState(running)
    }

    /** Read-only running flag, exposed for the [Controller]. */
    internal fun isRunningInternal(): Boolean = running

    private fun performNextTap() {
        if (!running) return

        val config = currentConfig ?: return
        if (config.repeat > 0 && remainingTaps <= 0) {
            stopInternal()
            return
        }

        val targetX = config.x.toFloat()
        val targetY = config.y.toFloat()

        // A tiny offset avoids a degenerate single-point path; the gesture
        // engine requires a non-empty path.
        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX + 0.1f, targetY + 0.1f)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime= */ 0,
            /* duration= */ config.duration.toLong().coerceAtLeast(1L)
        )
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                lastTapAt = SystemClock.uptimeMillis()
                scheduleNextTap()
            }

            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "gesture cancelled, retrying after interval")
                scheduleNextTap()
            }
        }, /* handler= */ null)

        if (!dispatched) {
            Log.w(TAG, "dispatchGesture returned false — backing off")
            scheduleNextTap()
        } else if (config.repeat > 0) {
            remainingTaps--
        }
    }

    private fun scheduleNextTap() {
        if (!running) return
        val config = currentConfig ?: return
        if (config.repeat > 0 && remainingTaps <= 0) {
            stopInternal()
            return
        }
        // Interval is measured from the start of one tap to the start of the next.
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastTapAt
        val delay = (config.interval - elapsed).coerceAtLeast(0L)
        handler.postDelayed(tickRunnable, delay)
    }

    companion object {
        private const val TAG = "AutoClickService"

        /**
         * Simple data holder describing a tap. All coordinates are absolute
         * screen pixels.
         *
         * @property x tap x in px
         * @property y tap y in px
         * @property interval milliseconds between consecutive tap starts
         * @property duration milliseconds each tap is held (long-press simulation)
         * @property repeat number of taps to perform, 0 means infinite
         */
        data class TapConfig(
            val x: Int,
            val y: Int,
            val interval: Int,
            val duration: Int,
            val repeat: Int,
        )
    }

    /**
     * Static bridge between the UI (MainActivity / FloatingControlsService) and
     * the bound [AutoClickService]. Lets callers start/stop tapping without
     * caring about the service binding lifecycle.
     */
    object Controller {
        @Volatile private var instance: AutoClickService? = null
        private val stateListeners = mutableListOf<(Boolean) -> Unit>()

        fun bind(service: AutoClickService) { instance = service }
        fun unbind() { instance = null }

        fun isRunning(): Boolean = instance?.isRunningInternal() ?: false

        fun start(config: TapClickConfig): Boolean {
            val s = instance ?: return false
            return try {
                s.startInternal(config)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                false
            }
        }

        fun stop() { instance?.stopInternal() }

        fun register(listener: (Boolean) -> Unit) {
            stateListeners.add(listener)
            listener.invoke(isRunning())
        }

        fun unregister(listener: (Boolean) -> Unit) {
            stateListeners.remove(listener)
        }

        internal fun notifyState(running: Boolean) {
            stateListeners.forEach { it.invoke(running) }
        }
    }
}

/** Public alias for [AutoClickService.TapConfig] so callers do not need to
 *  reference the companion object of a service class. */
typealias TapClickConfig = AutoClickService.TapConfig
