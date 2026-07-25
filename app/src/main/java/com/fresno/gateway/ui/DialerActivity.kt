package com.fresno.gateway.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fresno.gateway.R
import com.fresno.gateway.hfp.HfpGatewayService
import com.fresno.gateway.util.Prefs

/**
 * Keypad dialer. Type digits with the physical keypad; long-press * for +;
 * # or D-pad LEFT deletes; green call key or OK dials.
 *
 * "Dial via" toggle (D-pad RIGHT): HOST = place the call on the connected
 * host phone through the gateway link; FRESNO = place the call on the
 * Fresno's own SIM through the system telecom stack.
 *
 * Also handles ACTION_DIAL / ACTION_VIEW tel: intents so the app can be
 * chosen as the default dialer.
 */
class DialerActivity : AppCompatActivity() {

    private val number = StringBuilder()
    private var viaHost = true
    private lateinit var numberText: TextView
    private lateinit var viaText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)

        numberText = findViewById(R.id.dial_number)
        viaText = findViewById(R.id.dial_via)
        findViewById<Button>(R.id.btn_call).setOnClickListener { dial() }
        findViewById<Button>(R.id.btn_via).setOnClickListener { toggleVia() }

        // Prefill from tel: intent if launched as dialer.
        intent?.data?.let { uri: Uri ->
            if (uri.scheme == "tel") {
                number.append(uri.schemeSpecificPart.filter { it.isDigit() || it == '+' || it == '*' || it == '#' })
            }
        }
        render()
    }

    private fun render() {
        numberText.text = if (number.isEmpty()) " " else number.toString()
        viaText.text = if (viaHost) {
            getString(R.string.dial_via_host, Prefs.hostName(this) ?: "host")
        } else {
            getString(R.string.dial_via_fresno)
        }
    }

    private fun toggleVia() {
        viaHost = !viaHost
        render()
    }

    private fun dial() {
        val num = number.toString()
        if (num.isEmpty()) return
        if (viaHost) {
            HfpGatewayService.command(this, HfpGatewayService.ACTION_DIAL) {
                putExtra(HfpGatewayService.EXTRA_NUMBER, num)
            }
            startActivity(Intent(this, InCallActivity::class.java))
        } else {
            // Place on the Fresno's own SIM via telecom.
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$num"))
            try {
                startActivity(i)
            } catch (e: SecurityException) {
                Toast.makeText(this, R.string.perm_call_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun append(c: Char) {
        if (number.length < 24) {
            number.append(c)
            render()
        }
    }

    private fun backspace() {
        if (number.isNotEmpty()) {
            number.deleteCharAt(number.length - 1)
            render()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                append('0' + (keyCode - KeyEvent.KEYCODE_0)); return true
            }
            KeyEvent.KEYCODE_STAR -> {
                event.startTracking(); return true
            }
            KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_DEL -> {
                backspace(); return true
            }
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dial(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { toggleVia(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            append('+'); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR && (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0
            && !event.isCanceled
        ) {
            append('*'); return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
