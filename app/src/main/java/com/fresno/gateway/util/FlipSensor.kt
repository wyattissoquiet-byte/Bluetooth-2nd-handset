package com.fresno.gateway.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Detects the flip phone's lid opening and closing.
 *
 * Flip phones such as the Hot Pepper Fresno report the lid state through a
 * hall-effect sensor. Depending on the firmware this surfaces either as:
 *  1. A hall/proximity-style sensor in the SensorManager list (type name
 *     usually contains "hall" or vendor string "lid"), reporting 0 = closed.
 *  2. The system broadcast `android.intent.action.LID_STATE_CHANGED`
 *     (used by several KaiOS-heritage/AOSP flip builds).
 *  3. Screen on/off events as a coarse fallback: on most flips the main
 *     display turns on when opened and off when closed.
 *
 * All three signals are wired here; the first one that fires wins.
 */
class FlipSensor(
    private val context: Context,
    private val onFlipOpened: () -> Unit,
    private val onFlipClosed: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var hallSensor: Sensor? = null
    private var lastLidOpen: Boolean? = null
    private var registered = false

    private val lidReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_LID_STATE_CHANGED -> {
                    // 0 = unknown, 1 = open, 2 = closed (device-specific)
                    when (intent.getIntExtra(EXTRA_LID_STATE, -1)) {
                        1 -> emit(true)
                        2 -> emit(false)
                    }
                }
                Intent.ACTION_SCREEN_ON -> emit(true)
                Intent.ACTION_SCREEN_OFF -> emit(false)
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

    override fun onSensorChanged(event: SensorEvent) {
        // Convention: 0 = closed, non-zero = open (some firmwares invert;
        // we track transitions rather than absolute values).
        val open = event.values.firstOrNull()?.let { it != 0f } ?: return
        emit(open)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun emit(open: Boolean) {
        if (lastLidOpen == open) return
        lastLidOpen = open
        Log.i(TAG, "Flip ${if (open) "OPENED" else "CLOSED"}")
        if (open) onFlipOpened() else onFlipClosed()
    }

    companion object {
        private const val TAG = "FlipSensor"
        private const val ACTION_LID_STATE_CHANGED =
            "android.intent.action.LID_STATE_CHANGED"
        private const val EXTRA_LID_STATE = "state"
    }
}
