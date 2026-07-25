package com.fresno.gateway.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.fresno.gateway.ui.IncomingCallActivity
import com.fresno.gateway.ui.InCallActivity

/**
 * InCallService implementation. Registering this service (plus the DIAL
 * intent filters on MainActivity) makes the app eligible to be selected
 * as the Default Phone App on the Fresno, satisfying the Telecom
 * framework requirements for handling the phone's own SIM calls.
 *
 * Local SIM calls reuse the same flip-friendly incoming/in-call screens
 * as remote (Bluetooth gateway) calls; the activities distinguish the two
 * via [LocalCallManager.currentCall].
 */
class GatewayInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            LocalCallManager.notifyStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED) {
                LocalCallManager.clear(call)
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        LocalCallManager.attach(call, this)
        call.registerCallback(callCallback)

        val state = call.details.state
        val screen = if (state == Call.STATE_RINGING) {
            IncomingCallActivity::class.java
        } else {
            InCallActivity::class.java
        }
        startActivity(
            Intent(this, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_LOCAL_CALL, true)
        )
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        LocalCallManager.clear(call)
    }

    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        LocalCallManager.audioState = audioState
    }

    companion object {
        const val EXTRA_LOCAL_CALL = "local_call"
    }
}
