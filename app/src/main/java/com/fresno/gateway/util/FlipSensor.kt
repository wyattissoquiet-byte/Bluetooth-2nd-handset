package com.fresno.gateway.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * Detects the flip phone's lid opening and closing.
 *
 * Signal sources, in order of trust:
 *  1. A hall/lid sensor from the SensorManager list (name/type contains
 *     "hall" or "lid"). This is a genuine hardware lid signal.
 *  2. The system broadcast `android.intent.action.LID_STATE_CHANGED`.
 *  3. Screen on/off events — UNTRUSTED fallback, used only when no hardware
 *     lid signal exists AND only after a grace period, because the app
 *     itself wakes the screen when a call comes in (which previously caused
 *     a false "flip opened" event that auto-answered calls while closed).
 *
 * Once a hardware signal (1 or 2) has ever fired, screen events are ignored
 * permanently for this instance. Consumers receive both edge callbacks and
 * can query [isOpen] / [lastOpenElapsed] to validate transitions.
 */
class FlipSensor(
    private val context: Context,
    private val onFlipOpened: () -> Unit,
    private val onFlipClosed: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var hallSensor: Sensor? = null
    private var registered = false

    /** True once any hardware lid signal (hall sensor or LID broadcast) fired. */
    @Volatile private var hardwareSignalSeen = false

    /** Current lid state; null until first signal. */
    @Volatile var isOpen: Boolean? = null
        private set

    /** elapsedRealtime of the last genuine open transition, 0 if none. */
    @Volatile var lastOpenAt: Long = 0L
        private set

    /** elapsedRealtime of the last genuine close transition, 0 if none. */
    @Volatile var lastCloseAt: Long = 0L
        private set

    /** Human-readable description of the signal source, for diagnostics. */
    @Volatile var sourceDescription: String = "none"
        private set

    // Screen events within this window after an explicit suppress call are
    // ignored (the service suppresses around its own wake-ups).
    @Volatile private var suppressScreenUntil = 0L

    private val lidReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_LID_STATE_CHANGED -> {
                    // 0 = unknown, 1 = open, 2 = closed (device-specific)
                    val state = intent.getIntExtra(EXTRA_LID_STATE, -1)
                    if (state == 1 || state == 2) {
                        hardwareSignalSeen = true
                        sourceDescription = "LID_STATE_CHANGED broadcast"
                        emit(state == 1, trusted = true)
                    }
                }
                Intent.ACTION_SCREEN_ON -> onScreenEvent(true)
                Intent.ACTION_SCREEN_OFF -> onScreenEvent(false)
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true

        // Look for a hall / lid sensor among all sensors.
        hallSensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { s ->
            val n = (s.name ?: "").lowercase()
            val t = (s.stringType ?: "").lowercase()
            n.contains("hall") || n.contains("lid") ||
                t.contains("hall") || t.contains("lid")
        }
        hallSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            sourceDescription = "hall sensor: ${it.name}"
            Log.i(TAG, "Using hall sensor: ${it.name}")
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_LID_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(lidReceiver, filter)
    }

    fun stop() {
        if (!registered) return
        registered = false
        sensorManager.unregisterListener(this)
        runCatching { context.unregisterReceiver(lidReceiver) }
    }

    /**
     * Tells the sensor to ignore screen-state changes for [ms] milliseconds.
     * The service calls this right before it wakes the screen itself
     * (incoming call UI), so the wake-up is never mistaken for a flip-open.
     */
    fun suppressScreenSignals(ms: Long = 5000L) {
        suppressScreenUntil = SystemClock.elapsedRealtime() + ms
    }

    /** Whether a trusted hardware lid signal source is present. */
    val hasHardwareSignal: Boolean get() = hardwareSignalSeen || hallSensor != null

    override fun onSensorChanged(event: SensorEvent) {
        // Hall convention: 0 = closed, non-zero = open. Some firmwares invert;
        // we only care about transitions, and the very first reading
        // calibrates polarity implicitly through the transition edges.
        val open = event.values.firstOrNull()?.let { it != 0f } ?: return
        hardwareSignalSeen = true
        emit(open, trusted = true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onScreenEvent(screenOn: Boolean) {
        // Never let screen events drive flip state when hardware signals exist.
        if (hardwareSignalSeen || hallSensor != null) return
        // Ignore wake-ups the app caused itself (incoming-call UI, wakelock).
        if (SystemClock.elapsedRealtime() < suppressScreenUntil) {
            Log.d(TAG, "Screen event ignored (suppressed window)")
            return
        }
        if (sourceDescription == "none") sourceDescription = "screen events (fallback)"
        emit(screenOn, trusted = false)
    }

    private fun emit(open: Boolean, trusted: Boolean) {
        if (isOpen == open) return
        val first = isOpen == null
        isOpen = open
        val now = SystemClock.elapsedRealtime()
        if (open) lastOpenAt = now else lastCloseAt = now
        Log.i(TAG, "Flip ${if (open) "OPENED" else "CLOSED"} (trusted=$trusted, first=$first)")
        // The very first signal only calibrates state; it is not a user
        // gesture, so never fire callbacks for it.
        if (first) return
        if (open) onFlipOpened() else onFlipClosed()
    }

    companion object {
        private const val TAG = "FlipSensor"
        private const val ACTION_LID_STATE_CHANGED =
            "android.intent.action.LID_STATE_CHANGED"
        private const val EXTRA_LID_STATE = "state"
    }
}
