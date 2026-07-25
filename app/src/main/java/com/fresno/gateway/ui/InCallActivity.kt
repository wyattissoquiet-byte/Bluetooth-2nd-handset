package com.fresno.gateway.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fresno.gateway.R
import com.fresno.gateway.hfp.HfpGatewayService
import com.fresno.gateway.hfp.RemoteCallState
import com.fresno.gateway.telecom.GatewayInCallService
import com.fresno.gateway.telecom.LocalCallManager

/**
 * Active-call screen for both remote (gateway) and local SIM calls.
 *
 * Physical key mapping while in a call:
 *   0-9 * #      -> DTMF tones (sent to the far end)
 *   D-pad LEFT   -> toggle mute
 *   D-pad RIGHT  -> toggle speakerphone
 *   D-pad UP     -> (remote native mode) pull/push call audio to Fresno
 *   Red end key  -> hang up
 *   Closing flip -> hang up (if enabled in Settings; handled by service
 *                   for remote calls, here for local calls)
 */
class InCallActivity : AppCompatActivity() {

    private var isLocalCall = false
    private lateinit var callerText: TextView
    private lateinit var stateText: TextView
    private lateinit var chrono: Chronometer
    private lateinit var muteBtn: Button
    private lateinit var speakerBtn: Button
    private lateinit var audioBtn: Button
    private var chronoStarted = false
    private var remoteMuted = false
    private var scoOnFresno = false
    private var usingNative = false

    private val localListener: (Call, Int) -> Unit = { _, state ->
        runOnUiThread {
            when (state) {
                Call.STATE_ACTIVE -> startChrono()
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
            remoteMuted = intent.getBooleanExtra(HfpGatewayService.EXTRA_MUTED, false)
            scoOnFresno = intent.getBooleanExtra(HfpGatewayService.EXTRA_SCO, false)
            usingNative = intent.getBooleanExtra(HfpGatewayService.EXTRA_NATIVE, false)
            runOnUiThread {
                if (number != null) callerText.text = number
                render(call)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incall)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isLocalCall = intent.getBooleanExtra(GatewayInCallService.EXTRA_LOCAL_CALL, false)

        callerText = findViewById(R.id.incall_caller)
        stateText = findViewById(R.id.incall_state)
        chrono = findViewById(R.id.incall_chrono)
        muteBtn = findViewById(R.id.btn_mute)
        speakerBtn = findViewById(R.id.btn_speaker)
        audioBtn = findViewById(R.id.btn_audio)
        val endBtn = findViewById<Button>(R.id.btn_end)

        muteBtn.setOnClickListener { toggleMute() }
        speakerBtn.setOnClickListener { toggleSpeaker() }
        audioBtn.setOnClickListener { toggleAudioRoute() }
        endBtn.setOnClickListener { hangup() }
        endBtn.requestFocus()

        if (isLocalCall) {
            callerText.text = LocalCallManager.callerName()
                ?: LocalCallManager.callerNumber()
                ?: getString(R.string.unknown_number)
            audioBtn.visibility = android.view.View.GONE
            LocalCallManager.addListener(localListener)
            if (LocalCallManager.currentCall?.details?.state == Call.STATE_ACTIVE) {
                startChrono()
            } else {
                stateText.text = getString(R.string.state_dialing)
            }
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

    private fun render(call: RemoteCallState) {
        when (call) {
            RemoteCallState.ACTIVE -> startChrono()
            RemoteCallState.DIALING -> stateText.text = getString(R.string.state_dialing)
            RemoteCallState.IDLE -> finish()
            RemoteCallState.INCOMING -> Unit
        }
        muteBtn.text = if (isMuted()) getString(R.string.unmute) else getString(R.string.mute)
        audioBtn.text = if (scoOnFresno) {
            getString(R.string.audio_to_host)
        } else {
            getString(R.string.audio_to_fresno)
        }
        audioBtn.visibility = if (isLocalCall) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun startChrono() {
        if (chronoStarted) return
        chronoStarted = true
        stateText.text = getString(R.string.state_active)
        chrono.base = SystemClock.elapsedRealtime()
        chrono.start()
    }

    private fun isMuted(): Boolean =
        if (isLocalCall) LocalCallManager.isMuted() else remoteMuted

    private fun toggleMute() {
        if (isLocalCall) {
            LocalCallManager.setMuted(!LocalCallManager.isMuted())
            muteBtn.text = if (LocalCallManager.isMuted()) {
                getString(R.string.unmute)
            } else {
                getString(R.string.mute)
            }
        } else {
            HfpGatewayService.command(this, HfpGatewayService.ACTION_TOGGLE_MUTE)
        }
    }

    private fun toggleSpeaker() {
        if (isLocalCall) LocalCallManager.toggleSpeaker()
        else HfpGatewayService.command(this, HfpGatewayService.ACTION_TOGGLE_SPEAKER)
    }

    private fun toggleAudioRoute() {
        if (!isLocalCall) {
            HfpGatewayService.command(this, HfpGatewayService.ACTION_TOGGLE_SCO)
        }
    }

    private fun hangup() {
        if (isLocalCall) LocalCallManager.hangup()
        else HfpGatewayService.command(this, HfpGatewayService.ACTION_HANGUP)
        finish()
    }

    private fun sendDtmf(digit: Char) {
        if (isLocalCall) {
            LocalCallManager.playDtmf(digit)
        } else {
            HfpGatewayService.command(this, HfpGatewayService.ACTION_DTMF) {
                putExtra(HfpGatewayService.EXTRA_DIGIT, digit.toString())
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                ('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_STAR -> '*'
            KeyEvent.KEYCODE_POUND -> '#'
            else -> null
        }
        if (digit != null) {
            sendDtmf(digit)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { toggleMute(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { toggleSpeaker(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { toggleAudioRoute(); return true }
            KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_HEADSETHOOK -> {
                hangup(); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Back leaves the screen without ending the call.
                finish(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
