package com.modspec.agent

import android.content.Context
import org.json.JSONObject

/**
 * Writes SharedPreferences XML for third-party module companion apps (requires root).
 */
object ModulePrefsWriter {
    fun write(context: Context, modulePackage: String, prefs: Map<String, Any>) {
        val prefsName = "${modulePackage}_preferences"
        val xml = buildPrefsXml(prefs)
        val dir = "/data/data/$modulePackage/shared_prefs"
        val path = "$dir/$prefsName.xml"

        ShellRunner.runSu("mkdir -p '$dir'").getOrThrow()
        val escaped = xml.replace("'", "'\\''")
        ShellRunner.runSu("printf '%s' '$escaped' > '$path'").getOrThrow()

        val owner = ShellRunner.runSu("stat -c %u:%g /data/data/$modulePackage 2>/dev/null || echo 0:0")
            .getOrDefault("0:0")
            .trim()
        ShellRunner.runSu("chown $owner '$path' && chmod 660 '$path'").getOrNull()
    }

    fun writeFromJson(modulePackage: String, prefsObj: JSONObject) {
        val prefs = mutableMapOf<String, Any>()
        prefsObj.keys().forEach { key ->
            prefs[key] = prefsObj.get(key)
        }
        // Context only needed for consistency — use a no-op path via shell
        writeWithPackage(modulePackage, prefs)
    }

    private fun writeWithPackage(modulePackage: String, prefs: Map<String, Any>) {
        val prefsName = "${modulePackage}_preferences"
        val xml = buildPrefsXml(prefs)
        val dir = "/data/data/$modulePackage/shared_prefs"
        val path = "$dir/$prefsName.xml"
        ShellRunner.runSu("mkdir -p '$dir'").getOrThrow()
        val escaped = xml.replace("'", "'\\''")
        ShellRunner.runSu("printf '%s' '$escaped' > '$path'").getOrThrow()
        val owner = ShellRunner.runSu("stat -c %u:%g /data/data/$modulePackage 2>/dev/null || echo 0:0")
            .getOrDefault("0:0").trim()
        ShellRunner.runSu("chown $owner '$path' && chmod 660 '$path'").getOrNull()
    }

    private fun buildPrefsXml(prefs: Map<String, Any>): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((key, value) in prefs) {
            when (value) {
                is Boolean -> append("  <boolean name=\"$key\" value=\"$value\" />\n")
                is Int -> append("  <int name=\"$key\" value=\"$value\" />\n")
                is Long -> append("  <long name=\"$key\" value=\"$value\" />\n")
                is Float -> append("  <float name=\"$key\" value=\"$value\" />\n")
                is Double -> append("  <string name=\"$key\">$value</string>\n")
                else -> append("  <string name=\"$key\">${value.toString().escapeXml()}</string>\n")
            }
        }
        append("</map>\n")
    }

    private fun String.escapeXml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
