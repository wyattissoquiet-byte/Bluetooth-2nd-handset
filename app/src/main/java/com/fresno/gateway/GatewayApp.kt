package com.fresno.gateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Application singleton. Creates notification channels used by the
 * foreground gateway service and the incoming-call notifications.
 */
class GatewayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        val link = NotificationChannel(
            CHANNEL_GATEWAY,
            getString(R.string.notif_channel_gateway),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        val calls = NotificationChannel(
            CHANNEL_CALLS,
            getString(R.string.notif_channel_calls),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
        }

        nm.createNotificationChannels(listOf(link, calls))
    }

    companion object {
        const val CHANNEL_GATEWAY = "gateway_link"
        const val CHANNEL_CALLS = "gateway_calls"
    }
}
