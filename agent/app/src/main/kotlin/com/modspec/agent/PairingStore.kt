package com.modspec.agent

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object PairingStore {
    private const val PREFS = "modspec_pairing"
    private const val KEY_CODE = "pairing_code"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreateDeviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val id = "modspec-${UUID.randomUUID().toString().take(8)}"
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun getDeviceName(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_DEVICE_NAME, null)
            ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                .also { p.edit().putString(KEY_DEVICE_NAME, it).apply() }
    }

    fun setPairingCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CODE, code).apply()
    }

    fun getPairingCode(context: Context): String? =
        prefs(context).getString(KEY_CODE, null)

    fun validateCode(context: Context, code: String): Boolean {
        val expected = getPairingCode(context) ?: return false
        return expected == code
    }
}
