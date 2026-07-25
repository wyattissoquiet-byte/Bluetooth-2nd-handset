package com.fresno.gateway.hfp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Opportunistic wrapper around the hidden `android.bluetooth.BluetoothHeadsetClient`
 * system API (profile id 16, HFP HF role).
 *
 * On stock retail phones this profile is disabled in the Bluetooth stack
 * (`bluetooth.profile.hfp.hf.enabled=false`), so `getProfileProxy` will not
 * deliver a proxy and this class reports unavailable — the app then falls
 * back to the RFCOMM AT-command engine. On firmwares where the HFP-client
 * profile IS enabled (some AOSP flip builds enable it), this class provides
 * full native call control INCLUDING SCO audio routing to the local
 * microphone and speaker via connectAudio()/disconnectAudio().
 *
 * All calls use reflection because the API is @hide. Reflection on these
 * members is on the unsupported list; failures are caught and reported as
 * unavailable rather than crashing.
 */
class NativeHfpClient(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onNativeAvailable(available: Boolean)
        fun onNativeCallChanged(state: RemoteCallState, number: String?)
        fun onNativeAudioStateChanged(scoConnected: Boolean)
    }

    private var proxy: BluetoothProfile? = null
    private var clientClass: Class<*>? = null
    @Volatile var available: Boolean = false
        private set

    fun bind(adapter: BluetoothAdapter): Boolean {
        return try {
            clientClass = Class.forName("android.bluetooth.BluetoothHeadsetClient")
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    if (profile == PROFILE_HEADSET_CLIENT) {
                        proxy = p
                        available = true
                        Log.i(TAG, "Native HFP client proxy bound")
                        callback.onNativeAvailable(true)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == PROFILE_HEADSET_CLIENT) {
                        proxy = null
                        available = false
                        callback.onNativeAvailable(false)
                    }
                }
            }, PROFILE_HEADSET_CLIENT)
        } catch (t: Throwable) {
            Log.w(TAG, "Native HFP client unavailable: ${t.message}")
            available = false
            callback.onNativeAvailable(false)
            false
        }
    }

    fun unbind(adapter: BluetoothAdapter) {
        proxy?.let {
            runCatching { adapter.closeProfileProxy(PROFILE_HEADSET_CLIENT, it) }
        }
        proxy = null
        available = false
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val p = proxy ?: return null
        val cls = clientClass ?: return null
        return try {
            val method: Method = cls.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterCount == args.size
            } ?: return null
            method.isAccessible = true
            method.invoke(p, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "Native call $name failed: ${t.message}")
            null
        }
    }

    fun connect(device: BluetoothDevice): Boolean =
        call("connect", device) as? Boolean ?: false

    fun disconnect(device: BluetoothDevice): Boolean =
        call("disconnect", device) as? Boolean ?: false

    fun acceptCall(device: BluetoothDevice): Boolean =
        call("acceptCall", device, 0) as? Boolean ?: false

    fun rejectCall(device: BluetoothDevice): Boolean =
        call("rejectCall", device) as? Boolean ?: false

    /** Terminates the active call (null = default/first call). */
    fun terminateCall(device: BluetoothDevice): Boolean =
        call("terminateCall", device, null) as? Boolean ?: false

    fun dial(device: BluetoothDevice, number: String): Boolean =
        call("dial", device, number) != null

    fun sendDtmf(device: BluetoothDevice, code: Byte): Boolean =
        call("sendDTMF", device, code) as? Boolean ?: false

    /** Routes SCO call audio to this phone (full audio mode). */
    fun connectAudio(device: BluetoothDevice): Boolean =
        call("connectAudio", device) as? Boolean ?: false

    /** Sends SCO call audio back to the host phone. */
    fun disconnectAudio(device: BluetoothDevice): Boolean =
        call("disconnectAudio", device) as? Boolean ?: false

    fun setAudioRouteAllowed(device: BluetoothDevice, allowed: Boolean) {
        call("setAudioRouteAllowed", device, allowed)
    }

    fun getConnectionState(device: BluetoothDevice): Int =
        call("getConnectionState", device) as? Int ?: BluetoothProfile.STATE_DISCONNECTED

    companion object {
        private const val TAG = "NativeHfpClient"

        /** BluetoothProfile.HEADSET_CLIENT (hidden constant). */
        const val PROFILE_HEADSET_CLIENT = 16

        // Hidden broadcast actions (registered dynamically by the service).
        const val ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        const val ACTION_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
    }
}
