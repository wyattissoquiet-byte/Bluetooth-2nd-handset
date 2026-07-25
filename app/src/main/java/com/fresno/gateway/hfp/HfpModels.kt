package com.fresno.gateway.hfp

/** Connection state of the gateway link to the host phone. */
enum class LinkState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,      // RFCOMM socket up, SLC in progress
    SLC_READY       // Service Level Connection established
}

/** State of the remote call on the host phone, mirrored via HFP indicators. */
enum class RemoteCallState {
    IDLE,
    INCOMING,       // callsetup = 1 (RING received)
    DIALING,        // callsetup = 2/3 (outgoing)
    ACTIVE          // call = 1
}

/** Snapshot of the gateway status, pushed to UI via broadcast. */
data class GatewayStatus(
    val link: LinkState = LinkState.DISCONNECTED,
    val call: RemoteCallState = RemoteCallState.IDLE,
    val callerNumber: String? = null,
    val callerName: String? = null,
    val muted: Boolean = false,
    val scoAudioOnFresno: Boolean = false,
    val hostName: String? = null,
    val usingNativeHfp: Boolean = false
)
