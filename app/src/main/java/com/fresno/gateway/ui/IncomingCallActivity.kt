package com.fresno.gateway.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.telecom.Call
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fresno.gateway.R
import com.fresno.gateway.hfp.HfpGatewayService
import com.fresno.gateway.hfp.RemoteCallState
import com.fresno.gateway.telecom.GatewayInCallService
import com.fresno.gateway.telecom.LocalCallManager

/**
 * Full-screen incoming call UI for both remote (Bluetooth gateway) calls
 * and local SIM calls.
 *
 * Key mapping:
 *   Green call key / D-pad OK  -> answer
 *   Red end key / #            -> decline
 *   Opening the flip           -> answer (handled by the service for remote
 *                                 calls; handled here for local calls via
 *                                 screen-on signal when flip-answer is on)
 */
class IncomingCallActivity : AppCompatActivity() {

    private var isLocalCall = false
    private lateinit var callerText: TextView
    private lateinit var numberText: TextView

    private val localListener: (Call, Int) -> Unit = { _, state ->
        runOnUiThread {
            when (state) {
                Call.STATE_ACTIVE -> {
                    startActivity(
                        Intent(this, InCallActivity::class.java)
                            .putExtra(GatewayInCallService.EXTRA_LOCAL_CALL, true)
                    )
                    finish()
                }
                Call.STATE_DISCONNECTED -> finish()
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != HfpGatewayService.ACTION_STATUS_UPDATE) return
            val call = intent.getStringExtra(HfpGatewayService.EXTRA_CALL)
                ?.let { RemoteCallState.valueOf(it) } ?: return
            val number = intent.getStringExtra(HfpGatewayService.EXTRA_NUMBER)
            val name = intent.getStringExtra(HfpGatewayService.EXTRA_NAME)
            runOnUiThread {
                if (number != null) numberText.text = number
                if (name != null) callerText.text = name
                when (call) {
                    RemoteCallState.ACTIVE -> {
                        startActivity(Intent(this@IncomingCallActivity, InCallActivity::class.java))
                        finish()
                    }
                    RemoteCallState.IDLE -> finish()
                    else -> Unit
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming)

        // Wake + show over keyguard so the call is visible when flip opens.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isLocalCall = intent.getBooleanExtra(GatewayInCallService.EXTRA_LOCAL_CALL, false)

        callerText = findViewById(R.id.caller_name)
        numberText = findViewById(R.id.caller_number)
        val answerBtn = findViewById<Button>(R.id.btn_answer)
        val declineBtn = findViewById<Button>(R.id.btn_decline)

        answerBtn.setOnClickListener { answer() }
        declineBtn.setOnClickListener { decline() }
        answerBtn.requestFocus()

        if (isLocalCall) {
            callerText.text = LocalCallManager.callerName()
                ?: getString(R.string.incoming_call)
            numberText.text = LocalCallManager.callerNumber()
                ?: getString(R.string.unknown_number)
            LocalCallManager.addListener(localListener)
        } else {
            callerText.text = getString(R.string.incoming_call)
            numberText.text = getString(R.string.unknown_number)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isLocalCall) {
            registerReceiver(
                statusReceiver,
                IntentFilter(HfpGatewayService.ACTION_STATUS_UPDATE),
                RECEIVER_NOT_EXPORTED
            )
            HfpGatewayService.command(this, HfpGatewayService.ACTION_QUERY_STATUS)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isLocalCall) runCatching { unregisterReceiver(statusReceiver) }
    }

    override fun onDestroy() {
        if (isLocalCall) LocalCallManager.removeListener(localListener)
        super.onDestroy()
    }

    private fun answer() {
        if (isLocalCall) {
            LocalCallManager.answer()
        } else {
            HfpGatewayService.command(this, HfpGatewayService.ACTION_ANSWER)
        }
    }

    private fun decline() {
        if (isLocalCall) {
            LocalCallManager.reject()
        } else {
            HfpGatewayService.command(this, HfpGatewayService.ACTION_REJECT)
        }
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                answer(); return true
            }
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_POUND,
            KeyEvent.KEYCODE_BACK -> {
                decline(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
