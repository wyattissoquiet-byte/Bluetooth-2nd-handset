package com.fresno.gateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
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
 * the user pick which one is the "host" phone. D-pad up/down + OK.
 */
class DevicePickerActivity : AppCompatActivity() {

    private data class Entry(val name: String, val address: String) {
        override fun toString(): String = name
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)

        val list = findViewById<ListView>(R.id.device_list)
        val empty = findViewById<TextView>(R.id.empty_text)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            empty.text = "Bluetooth permission missing.\nRun Setup wizard first."
            return
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bonded = adapter?.bondedDevices.orEmpty()
            .map { Entry(it.name ?: it.address, it.address) }
            .sortedBy { it.name }

        if (bonded.isEmpty()) {
            empty.text = getString(R.string.picker_empty)
            return
        }
        empty.text = getString(R.string.picker_hint)

        list.adapter = ArrayAdapter(this, R.layout.item_device, bonded)
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = bonded[pos]
            Prefs.setHost(this, e.address, e.name)
            Toast.makeText(this, "Host: ${e.name}", Toast.LENGTH_SHORT).show()
            HfpGatewayService.command(this, HfpGatewayService.ACTION_CONNECT)
            finish()
        }
        list.requestFocus()
    }
}
