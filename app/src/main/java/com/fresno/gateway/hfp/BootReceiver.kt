package com.fresno.gateway.hfp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fresno.gateway.util.Prefs

/** Restarts the gateway link after the phone reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autoReconnect(context)) return
        if (Prefs.hostAddress(context) == null) return
        HfpGatewayService.command(context, HfpGatewayService.ACTION_CONNECT)
    }
}
