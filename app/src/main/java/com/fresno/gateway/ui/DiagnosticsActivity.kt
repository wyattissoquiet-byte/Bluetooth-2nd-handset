package com.fresno.gateway.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fresno.gateway.R
import com.fresno.gateway.hfp.NativeHfpClient

/**
 * On-device diagnostics for the two hardware-dependent features:
 *
 *  1. Flip (lid) detection — lists hall/lid sensors found and shows live
 *     open/close transitions so a misbehaving sensor is visible immediately.
 *  2. Native HFP audio — probes the hidden BluetoothHeadsetClient profile
 *     and the system properties that gate it, and reports exactly why full
 *     audio routing is or is not possible on this firmware.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var text: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val report = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        text = findViewById(R.id.diag_text)
        runDiagnostics()
    }

    private fun line(s: String) {
        report.append(s).append('\n')
        text.text = report.toString()
    }

    @SuppressLint("MissingPermission")
    private fun runDiagnostics() {
        report.clear()
        line("== FLIP SENSOR ==")
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val lidSensors = sm.getSensorList(Sensor.TYPE_ALL).filter { s ->
            val n = (s.name ?: "").lowercase()
            val t = (s.stringType ?: "").lowercase()
            n.contains("hall") || n.contains("lid") ||
                t.contains("hall") || t.contains("lid")
        }
        if (lidSensors.isEmpty()) {
            line("No hall/lid sensor found.")
            line("Flip detection uses screen on/off")
            line("fallback (self-wakes are filtered).")
            line("All sensors on device:")
            sm.getSensorList(Sensor.TYPE_ALL).take(20).forEach {
                line("  ${it.name} [${it.stringType}]")
            }
        } else {
            lidSensors.forEach { line("Found: ${it.name} [${it.stringType}]") }
            line("Open/close the flip now and watch")
            line("logcat tag FlipSensor for events.")
        }

        line("")
        line("== NATIVE HFP AUDIO ==")
        val hfProp = readProp("bluetooth.profile.hfp.hf.enabled")
        val hfPropPersist = readProp("persist.bluetooth.hfp_client.enabled")
        val legacyProp = readProp("persist.service.bt.hfp.client")
        line("bluetooth.profile.hfp.hf.enabled = ${hfProp.ifEmpty { "(unset)" }}")
        line("persist.bluetooth.hfp_client.enabled = ${hfPropPersist.ifEmpty { "(unset)" }}")
        line("persist.service.bt.hfp.client = ${legacyProp.ifEmpty { "(unset)" }}")

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            line("Bluetooth off — enable and re-run.")
            return
        }
        line("Probing HEADSET_CLIENT profile (16)…")
        var resolved = false
        try {
            val ok = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == NativeHfpClient.PROFILE_HEADSET_CLIENT) {
                        resolved = true
                        handler.post {
                            line("RESULT: HFP-client proxy BOUND!")
                            line("Native audio mode should work.")
                            line("Enable 'Try native HFP audio' in")
                            line("Settings and reconnect.")
                        }
                        runCatching {
                            adapter.closeProfileProxy(
                                NativeHfpClient.PROFILE_HEADSET_CLIENT, proxy
                            )
                        }
                    }
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, NativeHfpClient.PROFILE_HEADSET_CLIENT)

            if (!ok) {
                line("RESULT: profile proxy REFUSED.")
                explainUnavailable()
            } else {
                // Give the binder 3 seconds; if no callback, the service is absent.
                handler.postDelayed({
                    if (!resolved) {
                        line("RESULT: no HFP-client service")
                        line("answered within 3 s.")
                        explainUnavailable()
                    }
                }, 3000)
            }
        } catch (t: Throwable) {
            line("RESULT: probe failed: ${t.message}")
            explainUnavailable()
        }
    }

    private fun explainUnavailable() {
        line("")
        line("This firmware ships with the HFP")
        line("client role disabled, so no app can")
        line("route the host call's voice audio")
        line("to this phone. Call control still")
        line("works. You can try enabling it via")
        line("adb (PC required, may not stick):")
        line("")
        line(" adb shell setprop persist.bluetooth.hfp_client.enabled true")
        line(" adb shell setprop bluetooth.profile.hfp.hf.enabled true")
        line("")
        line("then toggle Bluetooth off/on and")
        line("re-run this diagnostic. If props")
        line("revert or the probe still fails,")
        line("root would be required.")
    }

    private fun readProp(name: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", name))
        p.inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")
}
