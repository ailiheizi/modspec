package com.modspec.agent

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/**
 * libxposed service remote preferences — synced between app and hooked processes.
 * Group: `modspec` (see docs/protocol.md).
 */
object RemotePrefsManager {
    const val DEFAULT_GROUP = "modspec"

    fun set(key: String, value: Any?, group: String = DEFAULT_GROUP) {
        val service = requireService()
        val editor = service.getRemotePreferences(group).edit()
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Double -> editor.putString(key, value.toString())
            is String -> editor.putString(key, value)
            else -> editor.putString(key, value.toString())
        }
        check(editor.commit()) { "failed to commit remote preference $group/$key" }
    }

    fun setFromJson(key: String, jsonValue: Any?, group: String = DEFAULT_GROUP) {
        when (jsonValue) {
            is JSONObject -> {
                if (jsonValue.has("type")) {
                    set(key, parseTypedValue(jsonValue), group)
                } else {
                    set(key, jsonValue.toString(), group)
                }
            }
            else -> set(key, jsonValue, group)
        }
    }

    fun getGroup(group: String = DEFAULT_GROUP): SharedPreferences =
        requireService().getRemotePreferences(group)

    private fun parseTypedValue(obj: JSONObject): Any? =
        when (obj.getString("type")) {
            "boolean" -> obj.optBoolean("data")
            "int" -> obj.optInt("data")
            "long" -> obj.optLong("data")
            "float" -> obj.optDouble("data").toFloat()
            "double" -> obj.optDouble("data")
            "string" -> obj.optString("data")
            "null" -> null
            else -> obj.optString("data", obj.toString())
        }

    private fun requireService(): XposedService =
        ModspecApp.xposedService
            ?: error("XposedService not bound — enable modspec-agent in LSPosed and open the app once")
}
