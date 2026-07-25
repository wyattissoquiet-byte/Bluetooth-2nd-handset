package com.fresno.gateway.util

import android.content.Context
import android.content.SharedPreferences

/** Thin wrapper over SharedPreferences for app settings. */
object Prefs {
    private const val FILE = "gateway_prefs"

    const val KEY_HOST_ADDRESS = "host_address"
    const val KEY_HOST_NAME = "host_name"
    const val KEY_FLIP_ANSWER = "flip_answer"
    const val KEY_FLIP_HANGUP = "flip_hangup"
    const val KEY_AUTO_RECONNECT = "auto_reconnect"
    const val KEY_RING_FRESNO = "ring_fresno"
    const val KEY_NATIVE_HFP = "native_hfp"
    const val KEY_SETUP_DONE = "setup_done"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hostAddress(ctx: Context): String? = get(ctx).getString(KEY_HOST_ADDRESS, null)
    fun hostName(ctx: Context): String? = get(ctx).getString(KEY_HOST_NAME, null)

    fun setHost(ctx: Context, address: String, name: String?) {
        get(ctx).edit()
            .putString(KEY_HOST_ADDRESS, address)
            .putString(KEY_HOST_NAME, name ?: address)
            .apply()
    }

    fun flipAnswer(ctx: Context): Boolean = get(ctx).getBoolean(KEY_FLIP_ANSWER, true)
    fun flipHangup(ctx: Context): Boolean = get(ctx).getBoolean(KEY_FLIP_HANGUP, false)
    fun autoReconnect(ctx: Context): Boolean = get(ctx).getBoolean(KEY_AUTO_RECONNECT, true)
    fun ringFresno(ctx: Context): Boolean = get(ctx).getBoolean(KEY_RING_FRESNO, true)
    fun nativeHfp(ctx: Context): Boolean = get(ctx).getBoolean(KEY_NATIVE_HFP, true)
    fun setupDone(ctx: Context): Boolean = get(ctx).getBoolean(KEY_SETUP_DONE, false)

    private fun setBool(ctx: Context, key: String, value: Boolean) {
        get(ctx).edit().putBoolean(key, value).apply()
    }

    fun setFlipAnswer(ctx: Context, v: Boolean) = setBool(ctx, KEY_FLIP_ANSWER, v)
    fun setFlipHangup(ctx: Context, v: Boolean) = setBool(ctx, KEY_FLIP_HANGUP, v)
    fun setAutoReconnect(ctx: Context, v: Boolean) = setBool(ctx, KEY_AUTO_RECONNECT, v)
    fun setRingFresno(ctx: Context, v: Boolean) = setBool(ctx, KEY_RING_FRESNO, v)
    fun setNativeHfp(ctx: Context, v: Boolean) = setBool(ctx, KEY_NATIVE_HFP, v)
    fun setSetupDone(ctx: Context, v: Boolean) = setBool(ctx, KEY_SETUP_DONE, v)
}
