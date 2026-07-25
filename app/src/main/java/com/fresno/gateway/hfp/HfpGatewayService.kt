package com.fresno.gateway.hfp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fresno.gateway.GatewayApp
import com.fresno.gateway.R
import com.fresno.gateway.ui.IncomingCallActivity
import com.fresno.gateway.ui.InCallActivity
import com.fresno.gateway.ui.MainActivity
import com.fresno.gateway.util.FlipSensor
import com.fresno.gateway.util.Prefs

/**
 * Foreground service that owns the Bluetooth gateway link to the host phone.
 *
 * Responsibilities:
 *  - Maintain the HFP connection: native BluetoothHeadsetClient when the
 *    firmware supports it (full SCO audio), otherwise the app-level
 *    RFCOMM AT-command engine (call control + caller ID).
 *  - Ring locally, show the incoming-call screen, and honor flip-open
 *    answer / flip-close hang-up gestures.
 *  - Publish [GatewayStatus] snapshots to the UI via local broadcasts.
 *  - Auto-reconnect when the link drops.
 */
class HfpGatewayService : Service(), HfpAtEngine.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var engine: HfpAtEngine? = null
    private var nativeClient: NativeHfpClient? = null
    private var device: BluetoothDevice? = null
    private var flipSensor: FlipSensor? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectAttempts = 0

    private var status = GatewayStatus()
        set(value) {
            field = value
            broadcastStatus()
        }

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // -------- Native HFP client broadcast listener (only fires on supported firmware)

    private val nativeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                NativeHfpClient.ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", 0)
                    status = status.copy(scoAudioOnFresno = state == 2 /* STATE_AUDIO_CONNECTED */)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Vibrator::class.java)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_idle)))

        flipSensor = FlipSensor(
            this,
            onFlipOpened = { onFlipOpened() },
            onFlipClosed = { onFlipClosed() }
        ).also { it.start() }

        val filter = IntentFilter().apply {
            addAction(NativeHfpClient.ACTION_AUDIO_STATE_CHANGED)
            addAction(NativeHfpClient.ACTION_CALL_CHANGED)
        }
        registerReceiver(nativeReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connectToHost()
            ACTION_DISCONNECT -> disconnectFromHost()
            ACTION_ANSWER -> answer()
            ACTION_REJECT -> reject()
            ACTION_HANGUP -> hangup()
            ACTION_DIAL -> intent.getStringExtra(EXTRA_NUMBER)?.let { dial(it) }
            ACTION_DTMF -> intent.getStringExtra(EXTRA_DIGIT)?.firstOrNull()?.let { dtmf(it) }
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_TOGGLE_SPEAKER -> toggleSpeaker()
            ACTION_TOGGLE_SCO -> toggleScoAudio()
            ACTION_QUERY_STATUS -> broadcastStatus()
        }
        return START_STICKY
    }

    // ---------------- Connection management ----------------

    @SuppressLint("MissingPermission")
    private fun connectToHost() {
        val address = Prefs.hostAddress(this) ?: run {
            onError(getString(R.string.no_device)); return
        }
        val adapter = adapter ?: return
        if (!adapter.isEnabled) {
            onError("Bluetooth is off"); return
        }
        val dev = adapter.getRemoteDevice(address)
        device = dev
        reconnectAttempts = 0

        // Try native HFP client first if enabled in settings.
        if (Prefs.nativeHfp(this)) {
            val nc = nativeClient ?: NativeHfpClient(this, nativeCallback).also { nativeClient = it }
            if (!nc.available) nc.bind(adapter)
            handler.postDelayed({
                if (nc.available && nc.connect(dev)) {
                    Log.i(TAG, "Connected via native HFP client")
                    status = status.copy(
                        link = LinkState.SLC_READY,
                        hostName = deviceName(dev),
                        usingNativeHfp = true
                    )
                    updateNotification(getString(R.string.notif_connected, deviceName(dev)))
                } else {
                    connectAtEngine(dev)
                }
            }, 700)
        } else {
            connectAtEngine(dev)
        }
    }

    private fun connectAtEngine(dev: BluetoothDevice) {
        Thread {
            engine?.disconnect()
            val e = HfpAtEngine(dev, this)
            engine = e
            if (e.connect()) {
                status = status.copy(hostName = deviceName(dev), usingNativeHfp = false)
                updateNotification(getString(R.string.notif_connected, deviceName(dev)))
            }
        }.start()
    }

    private fun disconnectFromHost() {
        reconnectAttempts = MAX_RECONNECT   // suppress auto-reconnect
        engine?.disconnect()
        device?.let { d -> nativeClient?.let { if (it.available) it.disconnect(d) } }
        status = status.copy(link = LinkState.DISCONNECTED, call = RemoteCallState.IDLE)
        updateNotification(getString(R.string.notif_idle))
    }

    private fun scheduleReconnect() {
        if (!Prefs.autoReconnect(this) || reconnectAttempts >= MAX_RECONNECT) return
        reconnectAttempts++
        val delay = (reconnectAttempts * 5000L).coerceAtMost(30000L)
        Log.i(TAG, "Reconnect attempt $reconnectAttempts in ${delay}ms")
        handler.postDelayed({ connectToHost() }, delay)
    }

    // ---------------- Call actions ----------------

    private fun answer() {
        stopRinging()
        val d = device
        if (status.usingNativeHfp && d != null) {
            nativeClient?.acceptCall(d)
            // Pull SCO audio to the Fresno so mic/speaker work locally.
            nativeClient?.setAudioRouteAllowed(d, true)
            nativeClient?.connectAudio(d)
        } else {
            engine?.answerCall()
        }
    }

    private fun reject() {
        stopRinging()
        val d = device
        if (status.usingNativeHfp && d != null) nativeClient?.rejectCall(d)
        else engine?.hangupOrReject()
    }

    private fun hangup() {
        stopRinging()
        val d = device
        if (status.usingNativeHfp && d != null) nativeClient?.terminateCall(d)
        else engine?.hangupOrReject()
    }

    private fun dial(number: String) {
        val d = device
        if (status.usingNativeHfp && d != null) nativeClient?.dial(d, number)
        else engine?.dial(number)
    }

    private fun dtmf(digit: Char) {
        val d = device
        if (status.usingNativeHfp && d != null) nativeClient?.sendDtmf(d, digit.code.toByte())
        else engine?.sendDtmf(digit)
    }

    private fun toggleMute() {
        val am = getSystemService(AudioManager::class.java)
        val newMuted = !status.muted
        am.isMicrophoneMute = newMuted
        status = status.copy(muted = newMuted)
    }

    private fun toggleSpeaker() {
        val am = getSystemService(AudioManager::class.java)
        am.isSpeakerphoneOn = !am.isSpeakerphoneOn
        broadcastStatus()
    }

    /** Toggles SCO audio between host phone and Fresno (native mode only). */
    private fun toggleScoAudio() {
        val d = device ?: return
        if (!status.usingNativeHfp) {
            // In AT fallback we can politely ask for audio transfer.
            engine?.requestAudioTransferToHf()
            return
        }
        if (status.scoAudioOnFresno) nativeClient?.disconnectAudio(d)
        else {
            nativeClient?.setAudioRouteAllowed(d, true)
            nativeClient?.connectAudio(d)
        }
    }

    // ---------------- Flip gestures ----------------

    private fun onFlipOpened() {
        if (status.call == RemoteCallState.INCOMING && Prefs.flipAnswer(this)) {
            Log.i(TAG, "Flip opened during ring -> answering")
            answer()
        }
    }

    private fun onFlipClosed() {
        if (status.call == RemoteCallState.ACTIVE && Prefs.flipHangup(this)) {
            Log.i(TAG, "Flip closed during call -> hanging up")
            hangup()
        }
    }

    // ---------------- HfpAtEngine.Listener ----------------

    override fun onLinkStateChanged(state: LinkState) {
        status = status.copy(link = state)
        if (state == LinkState.SLC_READY) reconnectAttempts = 0
        if (state == LinkState.DISCONNECTED) {
            stopRinging()
            if (status.call != RemoteCallState.IDLE) {
                status = status.copy(call = RemoteCallState.IDLE)
            }
            scheduleReconnect()
        }
    }

    override fun onRing() {
        if (status.call != RemoteCallState.INCOMING) {
            status = status.copy(call = RemoteCallState.INCOMING)
        }
        startRinging()
        showIncomingUi()
    }

    override fun onCallerId(number: String, name: String?) {
        status = status.copy(callerNumber = number, callerName = name)
    }

    override fun onCallStateChanged(call: RemoteCallState) {
        val previous = status.call
        status = status.copy(call = call)
        when (call) {
            RemoteCallState.INCOMING -> {
                startRinging()
                showIncomingUi()
            }
            RemoteCallState.ACTIVE -> {
                stopRinging()
                if (previous != RemoteCallState.ACTIVE) showInCallUi()
            }
            RemoteCallState.IDLE -> {
                stopRinging()
                status = status.copy(callerNumber = null, callerName = null, muted = false)
                val am = getSystemService(AudioManager::class.java)
                am.isMicrophoneMute = false
            }
            RemoteCallState.DIALING -> showInCallUi()
        }
    }

    override fun onAgEvent(indicator: String, value: Int) {
        Log.d(TAG, "AG event: $indicator=$value")
    }

    override fun onError(message: String) {
        Log.w(TAG, "Error: $message")
        val i = Intent(ACTION_STATUS_UPDATE).setPackage(packageName)
        i.putExtra(EXTRA_ERROR, message)
        sendBroadcast(i)
    }

    // ---------------- Native client callback ----------------

    private val nativeCallback = object : NativeHfpClient.Callback {
        override fun onNativeAvailable(available: Boolean) {
            Log.i(TAG, "Native HFP available: $available")
        }

        override fun onNativeCallChanged(state: RemoteCallState, number: String?) {
            if (number != null) status = status.copy(callerNumber = number)
            onCallStateChanged(state)
        }

        override fun onNativeAudioStateChanged(scoConnected: Boolean) {
            status = status.copy(scoAudioOnFresno = scoConnected)
        }
    }

    // ---------------- Ringer ----------------

    private fun startRinging() {
        if (!Prefs.ringFresno(this)) return
        if (ringtone?.isPlaying == true) return
        acquireWakeLock()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        )
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        releaseWakeLock()
    }

    // ---------------- UI plumbing ----------------

    private fun showIncomingUi() {
        val i = Intent(this, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun showInCallUi() {
        val i = Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun broadcastStatus() {
        val i = Intent(ACTION_STATUS_UPDATE).setPackage(packageName)
        i.putExtra(EXTRA_LINK, status.link.name)
        i.putExtra(EXTRA_CALL, status.call.name)
        i.putExtra(EXTRA_NUMBER, status.callerNumber)
        i.putExtra(EXTRA_NAME, status.callerName)
        i.putExtra(EXTRA_MUTED, status.muted)
        i.putExtra(EXTRA_SCO, status.scoAudioOnFresno)
        i.putExtra(EXTRA_HOST, status.hostName)
        i.putExtra(EXTRA_NATIVE, status.usingNativeHfp)
        sendBroadcast(i)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, GatewayApp.CHANNEL_GATEWAY)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FresnoGateway:ring"
        ).apply { acquire(60_000L) }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull() ?: d.address

    override fun onDestroy() {
        stopRinging()
        flipSensor?.stop()
        engine?.disconnect()
        adapter?.let { nativeClient?.unbind(it) }
        runCatching { unregisterReceiver(nativeReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "HfpGatewayService"
        private const val NOTIF_ID = 1001
        private const val MAX_RECONNECT = 10

        const val ACTION_CONNECT = "com.fresno.gateway.CONNECT"
        const val ACTION_DISCONNECT = "com.fresno.gateway.DISCONNECT"
        const val ACTION_ANSWER = "com.fresno.gateway.ANSWER"
        const val ACTION_REJECT = "com.fresno.gateway.REJECT"
        const val ACTION_HANGUP = "com.fresno.gateway.HANGUP"
        const val ACTION_DIAL = "com.fresno.gateway.DIAL"
        const val ACTION_DTMF = "com.fresno.gateway.DTMF"
        const val ACTION_TOGGLE_MUTE = "com.fresno.gateway.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.fresno.gateway.TOGGLE_SPEAKER"
        const val ACTION_TOGGLE_SCO = "com.fresno.gateway.TOGGLE_SCO"
        const val ACTION_QUERY_STATUS = "com.fresno.gateway.QUERY_STATUS"

        const val ACTION_STATUS_UPDATE = "com.fresno.gateway.STATUS_UPDATE"
        const val EXTRA_LINK = "link"
        const val EXTRA_CALL = "call"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_SCO = "sco"
        const val EXTRA_HOST = "host"
        const val EXTRA_NATIVE = "native"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DIGIT = "digit"

        /** Convenience for UI components to send a command to the service. */
        fun command(ctx: Context, action: String, configure: (Intent.() -> Unit)? = null) {
            val i = Intent(ctx, HfpGatewayService::class.java).setAction(action)
            configure?.invoke(i)
            ctx.startForegroundService(i)
        }
    }
}
