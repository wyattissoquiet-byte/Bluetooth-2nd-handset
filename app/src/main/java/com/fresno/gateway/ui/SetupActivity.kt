package com.fresno.gateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fresno.gateway.R
import com.fresno.gateway.util.Prefs

/**
 * Step-based setup wizard, one action per screen, driven by the OK key.
 *
 * Steps:
 *  1. Bluetooth runtime permissions (CONNECT + SCAN)
 *  2. Phone permissions (CALL_PHONE, READ_PHONE_STATE) for local SIM calls
 *  3. Default Phone app role (optional but recommended)
 *  4. Display-over-other-apps (so call screens appear instantly)
 *  5. Ignore battery optimizations (keeps the link alive)
 *  6. Pick the host phone from paired devices
 */
class SetupActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var actionBtn: Button
    private lateinit var skipBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        titleText = findViewById(R.id.setup_title)
        bodyText = findViewById(R.id.setup_body)
        actionBtn = findViewById(R.id.btn_action)
        skipBtn = findViewById(R.id.btn_skip)

        actionBtn.setOnClickListener { runStep() }
        skipBtn.setOnClickListener { nextStep() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // Auto-advance when a step was completed in a system screen.
        if (stepDone(step)) nextStep(silent = true)
        else render()
    }

    private fun stepDone(s: Int): Boolean = when (s) {
        0 -> granted(Manifest.permission.BLUETOOTH_CONNECT) &&
            granted(Manifest.permission.BLUETOOTH_SCAN)
        1 -> granted(Manifest.permission.CALL_PHONE) &&
            granted(Manifest.permission.READ_PHONE_STATE)
        2 -> isDefaultDialer()
        3 -> Settings.canDrawOverlays(this)
        4 -> (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        else -> false
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun isDefaultDialer(): Boolean {
        val rm = getSystemService(RoleManager::class.java)
        return rm.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun render() {
        val (title, body, action) = when (step) {
            0 -> Triple(
                getString(R.string.setup_bt_title),
                getString(R.string.setup_bt_body),
                getString(R.string.setup_grant)
            )
            1 -> Triple(
                getString(R.string.setup_phone_title),
                getString(R.string.setup_phone_body),
                getString(R.string.setup_grant)
            )
            2 -> Triple(
                getString(R.string.setup_dialer_title),
                getString(R.string.setup_dialer_body),
                getString(R.string.setup_set)
            )
            3 -> Triple(
                getString(R.string.setup_overlay_title),
                getString(R.string.setup_overlay_body),
                getString(R.string.setup_open)
            )
            4 -> Triple(
                getString(R.string.setup_battery_title),
                getString(R.string.setup_battery_body),
                getString(R.string.setup_open)
            )
            else -> Triple(
                getString(R.string.setup_host_title),
                getString(R.string.setup_host_body),
                getString(R.string.setup_pick)
            )
        }
        titleText.text = title
        bodyText.text = body
        actionBtn.text = action
        actionBtn.requestFocus()
    }

    private fun runStep() {
        when (step) {
            0 -> ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                REQ_BT
            )
            1 -> ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.RECORD_AUDIO
                ),
                REQ_PHONE
            )
            2 -> {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !isDefaultDialer()) {
                    startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE
                    )
                } else nextStep()
            }
            3 -> startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            4 -> startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            else -> {
                Prefs.setSetupDone(this, true)
                startActivity(Intent(this, DevicePickerActivity::class.java))
                finish()
            }
        }
    }

    private fun nextStep(silent: Boolean = false) {
        if (step >= LAST_STEP) {
            Prefs.setSetupDone(this, true)
            finish()
            return
        }
        step++
        // Skip already-satisfied steps.
        while (step < LAST_STEP && stepDone(step)) step++
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        nextStep()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE) nextStep()
    }

    companion object {
        private const val REQ_BT = 1
        private const val REQ_PHONE = 2
        private const val REQ_ROLE = 3
        private const val LAST_STEP = 5
    }
}
