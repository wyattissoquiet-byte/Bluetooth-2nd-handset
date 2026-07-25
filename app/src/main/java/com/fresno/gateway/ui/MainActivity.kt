package com.fresno.gateway.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fresno.gateway.R
import com.fresno.gateway.hfp.HfpGatewayService
import com.fresno.gateway.hfp.LinkState
import com.fresno.gateway.util.Prefs

/**
 * Dashboard screen. Fully D-pad operable:
 *   1 = Pair (device picker)     2 = Dialer
 *   3 = Settings                 4 = Setup wizard
 *   OK (D-pad center) on the Connect button toggles the gateway link.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var hostText: TextView
    private lateinit var connectButton: Button
    private var linkState = LinkState.DISCONNECTED

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != HfpGatewayService.ACTION_STATUS_UPDATE) return
            intent.getStringExtra(HfpGatewayService.EXTRA_LINK)?.let {
                linkState = LinkState.valueOf(it)
            }
            intent.getStringExtra(HfpGatewayService.EXTRA_ERROR)?.let { err ->
                statusText.text = err
                return
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        hostText = findViewById(R.id.host_text)
        connectButton = findViewById(R.id.btn_connect)

        connectButton.setOnClickListener { toggleConnection() }
        findViewById<Button>(R.id.btn_pair).setOnClickListener { openPicker() }
        findViewById<Button>(R.id.btn_dial).setOnClickListener { openDialer() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btn_setup).setOnClickListener { openSetup() }

        if (!Prefs.setupDone(this)) openSetup()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            statusReceiver,
            IntentFilter(HfpGatewayService.ACTION_STATUS_UPDATE),
            RECEIVER_NOT_EXPORTED
        )
        HfpGatewayService.command(this, HfpGatewayService.ACTION_QUERY_STATUS)
        render()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    private fun render() {
        hostText.text = Prefs.hostName(this) ?: getString(R.string.no_device)
        statusText.text = when (linkState) {
            LinkState.DISCONNECTED -> getString(R.string.status_disconnected)
            LinkState.CONNECTING -> getString(R.string.status_connecting)
            LinkState.CONNECTED -> getString(R.string.status_connected)
            LinkState.SLC_READY -> getString(R.string.status_slc_ready)
        }
        connectButton.text = if (linkState == LinkState.DISCONNECTED) {
            "Connect"
        } else {
            "Disconnect"
        }
    }

    private fun toggleConnection() {
        val action = if (linkState == LinkState.DISCONNECTED) {
            HfpGatewayService.ACTION_CONNECT
        } else {
            HfpGatewayService.ACTION_DISCONNECT
        }
        HfpGatewayService.command(this, action)
    }

    private fun openPicker() = startActivity(Intent(this, DevicePickerActivity::class.java))
    private fun openDialer() = startActivity(Intent(this, DialerActivity::class.java))
    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))
    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_1 -> { openPicker(); return true }
            KeyEvent.KEYCODE_2 -> { openDialer(); return true }
            KeyEvent.KEYCODE_3 -> { openSettings(); return true }
            KeyEvent.KEYCODE_4 -> { openSetup(); return true }
            KeyEvent.KEYCODE_CALL -> { openDialer(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
