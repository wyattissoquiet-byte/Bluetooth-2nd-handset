package com.fresno.gateway.hfp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implements the Hands-Free Profile (HFP 1.7) Hands-Free (HF) role at the
 * application layer, over an RFCOMM socket to the host phone's Audio
 * Gateway (AG) service.
 *
 * This performs the standard Service Level Connection (SLC) handshake and
 * then processes unsolicited results from the AG:
 *   RING           incoming call ring
 *   +CLIP:         caller line identification
 *   +CIEV:         indicator change (call, callsetup, service, battery...)
 * and issues commands:
 *   ATA            answer
 *   AT+CHUP        hang up / reject
 *   ATD<n>;        dial number
 *   AT+BLDN        redial last number
 *   AT+VTS=<c>     DTMF tone
 *   AT+CLCC        query current calls
 *
 * Because the phone's Bluetooth chip is not configured for the HF role,
 * the eSCO audio link cannot be terminated by an unprivileged app; audio
 * therefore remains on the host phone in this mode. Call *control* and
 * caller-ID work with any HFP-compliant host (Android or iPhone).
 */
class HfpAtEngine(
    private val device: BluetoothDevice,
    private val listener: Listener
) {

    interface Listener {
        fun onLinkStateChanged(state: LinkState)
        fun onRing()
        fun onCallerId(number: String, name: String?)
        fun onCallStateChanged(call: RemoteCallState)
        fun onAgEvent(indicator: String, value: Int)
        fun onError(message: String)
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    private val running = AtomicBoolean(false)
    private val okQueue = LinkedBlockingQueue<String>()
    private var readerThread: Thread? = null

    // CIND indicator mapping discovered during SLC (index -> name)
    private val indicatorMap = mutableMapOf<Int, String>()
    private var callInd = 0
    private var callSetupInd = 0

    val isRunning: Boolean get() = running.get()

    /** Opens the RFCOMM link and performs the SLC handshake. Blocking. */
    @SuppressLint("MissingPermission")
    fun connect(): Boolean {
        if (running.get()) return true
        listener.onLinkStateChanged(LinkState.CONNECTING)
        try {
            // The AG advertises the "Handsfree Audio Gateway" service.
            val s = device.createRfcommSocketToServiceRecord(UUID_HFP_AG)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
            running.set(true)
            listener.onLinkStateChanged(LinkState.CONNECTED)

            readerThread = Thread({ readLoop() }, "hfp-at-reader").also { it.start() }

            if (!establishSlc()) {
                listener.onError("SLC handshake failed")
                disconnect()
                return false
            }
            listener.onLinkStateChanged(LinkState.SLC_READY)
            return true
        } catch (e: IOException) {
            Log.e(TAG, "connect failed", e)
            listener.onError("Connect failed: ${e.message}")
            disconnect()
            return false
        }
    }

    fun disconnect() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        listener.onLinkStateChanged(LinkState.DISCONNECTED)
    }

    // ---------------- Call control API ----------------

    fun answerCall() = sendCommand("ATA")

    /** Rejects a ringing call or hangs up the active call. */
    fun hangupOrReject() = sendCommand("AT+CHUP")

    fun dial(number: String) = sendCommand("ATD$number;")

    fun redial() = sendCommand("AT+BLDN")

    fun sendDtmf(digit: Char): Boolean {
        if (digit !in DTMF_ALLOWED) return false
        return sendCommand("AT+VTS=$digit")
    }

    fun queryCalls() = sendCommand("AT+CLCC")

    /** HF-side microphone gain hint to AG (0..15); informational only. */
    fun reportMicVolume(volume: Int) = sendCommand("AT+VGM=${volume.coerceIn(0, 15)}")

    /** HF-side speaker gain hint to AG (0..15); informational only. */
    fun reportSpeakerVolume(volume: Int) = sendCommand("AT+VGS=${volume.coerceIn(0, 15)}")

    /** Transfers audio between AG and HF (BT+BCS/codec negotiation not needed
     *  for control; success depends on the local stack accepting eSCO). */
    fun requestAudioTransferToHf() = sendCommand("AT+BCC")

    // ---------------- SLC handshake ----------------

    private fun establishSlc(): Boolean {
        // 1. Exchange supported features.
        //    HF features bitmap: bit0 EC/NR, bit1 3-way, bit2 CLI, bit3 VR,
        //    bit4 remote volume, bit5 enhanced call status, bit6 enh. call ctrl
        if (!sendAndWaitOk("AT+BRSF=$HF_FEATURES")) return false
        // 2. Query indicator layout.
        if (!sendAndWaitOk("AT+CIND=?")) return false
        // 3. Read current indicator values.
        if (!sendAndWaitOk("AT+CIND?")) return false
        // 4. Enable indicator event reporting -> SLC established.
        if (!sendAndWaitOk("AT+CMER=3,0,0,1")) return false
        // Optional post-SLC configuration (failures tolerated):
        sendAndWaitOk("AT+CHLD=?")
        sendAndWaitOk("AT+CLIP=1")   // caller ID
        sendAndWaitOk("AT+CCWA=1")   // call waiting
        sendAndWaitOk("AT+CMEE=1")   // extended errors
        sendAndWaitOk("AT+BIA=")     // keep all indicators active (some AGs reject; fine)
        return true
    }

    // ---------------- IO plumbing ----------------

    @Synchronized
    private fun sendCommand(cmd: String): Boolean {
        val out = output ?: return false
        return try {
            Log.d(TAG, ">> $cmd")
            out.write((cmd + "\r").toByteArray())
            out.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "write failed", e)
            listener.onError("Link lost")
            disconnect()
            false
        }
    }

    private fun sendAndWaitOk(cmd: String, timeoutMs: Long = 3000): Boolean {
        okQueue.clear()
        if (!sendCommand(cmd)) return false
        while (true) {
            val resp = okQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
            if (resp == "OK") return true
            if (resp == "ERROR" || resp.startsWith("+CME ERROR")) return false
            // Any other buffered line: keep waiting within the same timeout window.
        }
    }

    private fun readLoop() {
        val buf = ByteArray(1024)
        val line = StringBuilder()
        while (running.get()) {
            val n = try {
                input?.read(buf) ?: -1
            } catch (e: IOException) {
                -1
            }
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '\r' || c == '\n') {
                    if (line.isNotBlank()) handleLine(line.toString().trim())
                    line.setLength(0)
                } else {
                    line.append(c)
                }
            }
        }
        if (running.get()) {
            listener.onError("Link closed by host")
            disconnect()
        }
    }

    private fun handleLine(line: String) {
        Log.d(TAG, "<< $line")
        when {
            line == "OK" || line == "ERROR" || line.startsWith("+CME ERROR") ->
                okQueue.offer(line)

            line == "RING" -> listener.onRing()

            line.startsWith("+CLIP:") -> parseClip(line)

            line.startsWith("+CIND:") -> parseCind(line)

            line.startsWith("+CIEV:") -> parseCiev(line)

            line.startsWith("+BRSF:") -> okQueue.offer(line) // feature echo, sink it

            line.startsWith("+CCWA:") -> parseClipLike(line, "+CCWA:")

            line.startsWith("+CLCC:") -> parseClcc(line)

            line.startsWith("+BSIR:") || line.startsWith("+BTRH:") ||
                line.startsWith("+VGS:") || line.startsWith("+VGM:") -> Unit
        }
    }

    /** +CLIP: "5551234567",129[,...] */
    private fun parseClip(line: String) = parseClipLike(line, "+CLIP:")

    private fun parseClipLike(line: String, prefix: String) {
        val body = line.removePrefix(prefix).trim()
        val m = CLIP_REGEX.find(body) ?: return
        val number = m.groupValues[1]
        // Optional 4th quoted param can be an alpha name on some AGs.
        val name = ALPHA_REGEX.findAll(body).drop(1).firstOrNull()?.groupValues?.get(1)
        listener.onCallerId(number, name)
    }

    /**
     * Parses `+CIND: ("call",(0,1)),("callsetup",(0-3)),...` (layout query)
     * or `+CIND: 0,0,1,4,...` (current values).
     */
    private fun parseCind(line: String) {
        val body = line.removePrefix("+CIND:").trim()
        if (body.contains("(")) {
            indicatorMap.clear()
            var idx = 1
            for (m in CIND_NAME_REGEX.findAll(body)) {
                indicatorMap[idx] = m.groupValues[1]
                idx++
            }
            Log.i(TAG, "Indicator map: $indicatorMap")
        } else {
            val values = body.split(",").mapNotNull { it.trim().toIntOrNull() }
            values.forEachIndexed { i, v -> applyIndicator(i + 1, v) }
        }
    }

    /** +CIEV: <index>,<value> */
    private fun parseCiev(line: String) {
        val body = line.removePrefix("+CIEV:").trim()
        val parts = body.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size >= 2) applyIndicator(parts[0], parts[1])
    }

    private fun applyIndicator(index: Int, value: Int) {
        val name = indicatorMap[index] ?: return
        listener.onAgEvent(name, value)
        when (name) {
            "call" -> {
                callInd = value
                pushCallState()
            }
            "callsetup", "call_setup" -> {
                callSetupInd = value
                pushCallState()
            }
        }
    }

    private fun pushCallState() {
        val state = when {
            callInd == 1 -> RemoteCallState.ACTIVE
            callSetupInd == 1 -> RemoteCallState.INCOMING
            callSetupInd == 2 || callSetupInd == 3 -> RemoteCallState.DIALING
            else -> RemoteCallState.IDLE
        }
        listener.onCallStateChanged(state)
    }

    /** +CLCC: <idx>,<dir>,<status>,<mode>,<mpty>[,"<number>",<type>] */
    private fun parseClcc(line: String) {
        val body = line.removePrefix("+CLCC:").trim()
        val m = CLIP_REGEX.find(body)
        if (m != null) listener.onCallerId(m.groupValues[1], null)
    }

    companion object {
        private const val TAG = "HfpAtEngine"

        /** Handsfree Audio Gateway service class UUID (0x111F). */
        val UUID_HFP_AG: UUID = UUID.fromString("0000111F-0000-1000-8000-00805F9B34FB")

        /** HF features: CLI presentation (bit2) + enhanced call status (bit5)
         *  + remote volume control (bit4) = 0b0110100 = 52 */
        private const val HF_FEATURES = 52

        private const val DTMF_ALLOWED = "0123456789*#ABCD"

        private val CLIP_REGEX = Regex("\"([+\\d*#]+)\"\\s*,\\s*(\\d+)")
        private val ALPHA_REGEX = Regex("\"([^\"]*)\"")
        private val CIND_NAME_REGEX = Regex("\\(\\s*\"([^\"]+)\"")
    }
}
