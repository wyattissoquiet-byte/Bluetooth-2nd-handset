package com.fresno.gateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fresno.gateway.R
import com.fresno.gateway.hfp.HfpGatewayService
import com.fresno.gateway.util.Prefs

/**
 * Lists devices already paired in the system Bluetooth settings and lets
 * the user pick which one is the "host" phone.
 *
 * Navigation: D-pad up/down moves the highlight, OK/Enter/green call key
 * selects, and digits 1-9 jump-select the Nth device directly. Selection
 * is handled in [onKeyDown] as well as via the item-click listener so it
 * works reliably on non-touch keypad devices.
 */
class DevicePickerActivity : AppCompatActivity() {

    private data class Entry(val name: String, val address: String) {
        override fun toString(): String = name
    }

    private lateinit var list: ListView
    private var bonded: List<Entry> = emptyList()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)

        list = findViewById(R.id.device_list)
        val empty = findViewById<TextView>(R.id.empty_text)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            empty.text = "Bluetooth permission missing.\nRun Setup wizard first."
            return
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bonded = adapter?.bondedDevices.orEmpty()
            .map { Entry(it.name ?: it.address, it.address) }
            .sortedBy { it.name }

        if (bonded.isEmpty()) {
            empty.text = getString(R.string.picker_empty)
            return
        }
        empty.text = getString(R.string.picker_hint)

        // Number the entries so digit keys can jump-select.
        val labels = bonded.mapIndexed { i, e -> "${i + 1}  ${e.name}" }
        list.adapter = ArrayAdapter(this, R.layout.item_device, labels)
        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.isFocusable = true
        list.isFocusableInTouchMode = true
        list.itemsCanFocus = false
        list.setSelector(R.drawable.list_selector)
        list.setOnItemClickListener { _, _, pos, _ -> choose(pos) }
        list.post {
            list.requestFocus()
            list.setSelection(0)
        }
    }

    private fun choose(pos: Int) {
        val e = bonded.getOrNull(pos) ?: return
        Prefs.setHost(this, e.address, e.name)
        Toast.makeText(this, "Host: ${e.name}", Toast.LENGTH_SHORT).show()
        HfpGatewayService.command(this, HfpGatewayService.ACTION_CONNECT)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_CALL -> {
                val pos = if (list.selectedItemPosition != ListView.INVALID_POSITION) {
                    list.selectedItemPosition
                } else {
                    0
                }
                choose(pos)
                return true
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val idx = keyCode - KeyEvent.KEYCODE_1
                if (idx < bonded.size) {
                    choose(idx)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
