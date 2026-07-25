package com.fresno.gateway.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/**
 * Holds the currently active local (SIM) telecom call so the shared
 * incoming/in-call activities can control it. Only one concurrent local
 * call flow is supported, which matches basic flip-phone usage.
 */
object LocalCallManager {

    var currentCall: Call? = null
        private set
    private var service: InCallService? = null
    var audioState: CallAudioState? = null

    /** Observers registered by the call screens. */
    private val listeners = mutableSetOf<(Call, Int) -> Unit>()

    fun attach(call: Call, svc: InCallService) {
        currentCall = call
        service = svc
    }

    fun clear(call: Call) {
        if (currentCall == call) {
            currentCall = null
        }
    }

    fun addListener(l: (Call, Int) -> Unit) = listeners.add(l)
    fun removeListener(l: (Call, Int) -> Unit) = listeners.remove(l)

    fun notifyStateChanged(call: Call, state: Int) {
        listeners.toList().forEach { it(call, state) }
    }

    // ---------------- Controls ----------------

    fun answer() {
        currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        currentCall?.reject(false, null)
    }

    fun hangup() {
        currentCall?.disconnect()
    }

    fun playDtmf(digit: Char) {
        currentCall?.let {
            it.playDtmfTone(digit)
            it.stopDtmfTone()
        }
    }

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    fun isMuted(): Boolean = audioState?.isMuted ?: false

    fun toggleSpeaker() {
        val svc = service ?: return
        val current = audioState?.route ?: CallAudioState.ROUTE_EARPIECE
        val next = if (current == CallAudioState.ROUTE_SPEAKER) {
            CallAudioState.ROUTE_WIRED_OR_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        svc.setAudioRoute(next)
    }

    fun callerNumber(): String? =
        currentCall?.details?.handle?.schemeSpecificPart

    fun callerName(): String? =
        currentCall?.details?.callerDisplayName
}
