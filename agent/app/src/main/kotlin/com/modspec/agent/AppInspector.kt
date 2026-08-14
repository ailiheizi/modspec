package com.modspec.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only app inventory + per-package detail for PC/AI clients.
 *
 * Everything here is derived from the public [PackageManager] APIs plus a
 * single optional root read (`pm list packages -i`) for the installer name.
 * No personal app data (battery, storage usage, permissions payloads) is
 * exposed. Mutating operations (restart/trigger) live in `RpcHandler`.
 */
object AppInspector {

    const val MAX_LIMIT = 2000

    fun list(context: Context, scope: String, limit: Int, filter: String?): JSONObject {
        val packages = context.packageManager.getInstalledPackages(0)
            .filter { it.matchesScope(scope) && it.matchesFilter(filter) }
            .sortedBy { it.packageName }
        val systemCount = packages.count { it.isSystemPackage() }
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val entries = JSONArray()
        for (pkg in packages.take(cap)) {
            entries.put(entryJson(pkg))
        }
        return JSONObject()
            .put("total", packages.size)
            .put("system", systemCount)
            .put("user", packages.size - systemCount)
            .put("returned", entries.length())
            .put("truncated", packages.size > cap)
            .put("scope", scope)
            .put("entries", entries)
    }

    fun info(context: Context, packageName: String): JSONObject {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, 0)
        val app = info.applicationInfo
        val launchable = resolveLauncher(pm, packageName)
        return JSONObject()
            .put("package", packageName)
            .put("version_name", info.versionName ?: JSONObject.NULL)
            .put("version_code", info.longVersionCode)
            .put("system", info.isSystemPackage())
            .put("enabled", app?.enabled ?: true)
            .put("installer", installerOf(packageName))
            .put("launchable", launchable != null)
            .put("primary_activity", launchable?.flattenToString() ?: JSONObject.NULL)
            .put("uid", app?.uid?.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("first_install_ms", info.firstInstallTime.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("last_update_ms", info.lastUpdateTime.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("components", JSONObject()
                .put("activities", info.activities?.size ?: 0)
                .put("services", info.services?.size ?: 0)
                .put("receivers", info.receivers?.size ?: 0)
                .put("providers", info.providers?.size ?: 0))
    }

    private fun entryJson(pkg: PackageInfo): JSONObject =
        JSONObject()
            .put("package", pkg.packageName)
            .put("version_name", pkg.versionName ?: JSONObject.NULL)
            .put("version_code", pkg.longVersionCode)
            .put("system", pkg.isSystemPackage())
            .put("enabled", pkg.applicationInfo?.enabled ?: true)

    private fun PackageInfo.matchesScope(scope: String): Boolean = when (scope) {
        "system" -> isSystemPackage()
        "user" -> !isSystemPackage()
        else -> true
    }

    private fun PackageInfo.matchesFilter(filter: String?): Boolean =
        filter.isNullOrBlank() || packageName.contains(filter, ignoreCase = true)

    private fun PackageInfo.isSystemPackage(): Boolean =
        (applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

    /** Resolve the launcher activity, if any (never guesses other components). */
    private fun resolveLauncher(pm: PackageManager, packageName: String): ComponentName? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val resolveInfo = pm.resolveActivity(intent, 0) ?: return@runCatching null
        val activityName = resolveInfo.activityInfo?.name ?: return@runCatching null
        ComponentName(packageName, activityName)
    }.getOrNull()

    /**
     * Installer name via `pm list packages -i` (root); `None` when unknown.
     * Pure parsing lives in [parseInstallerLine] so it is JVM-testable.
     */
    private fun installerOf(packageName: String): String? {
        if (!ShellRunner.canSu()) return null
        val output = ShellRunner.runSu(
            "pm list packages -i ${ShellRunner.shellQuote(packageName)} 2>/dev/null",
        ).getOrNull().orEmpty()
        val first = output.lineSequence().mapNotNull { parseInstallerLine(it) }.firstOrNull()
        return first?.takeIf { it != "unknown" && it.isNotBlank() }
    }

    /** Parse one `pm list packages -i` line: `package:com.foo installer=com.android.vending`. */
    internal fun parseInstallerLine(line: String): String? {
        if (!line.startsWith("package:")) return null
        val marker = "installer="
        val index = line.indexOf(marker)
        if (index < 0) return null
        return line.substring(index + marker.length).trim().takeIf { it.isNotEmpty() }
    }
}
