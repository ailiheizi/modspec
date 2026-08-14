package com.modspec.agent

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StatFs
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/** Structured, read-only device inventory for PC/AI clients. */
object DeviceInspector {
    fun inspect(context: Context, includeApps: Boolean, appLimit: Int): JSONObject {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = StatFs(context.filesDir.absolutePath)
        val metrics = context.resources.displayMetrics

        val packages = context.packageManager.getInstalledPackages(0)
            .sortedBy { it.packageName }
        val systemCount = packages.count { it.isSystemPackage() }
        val limit = appLimit.coerceIn(1, 2000)
        val entries = JSONArray()
        if (includeApps) {
            for (pkg in packages.take(limit)) {
                entries.put(
                    JSONObject()
                        .put("package", pkg.packageName)
                        .put("version_name", pkg.versionName ?: JSONObject.NULL)
                        .put("version_code", pkg.longVersionCode)
                        .put("system", pkg.isSystemPackage())
                        .put("enabled", pkg.applicationInfo?.enabled ?: true),
                )
            }
        }

        return JSONObject()
            .put("hardware", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("product", Build.PRODUCT)
                .put("board", Build.BOARD)
                .put("hardware", Build.HARDWARE)
                .put("soc_manufacturer", Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("soc_model", Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("cpu_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("cpu_cores", Runtime.getRuntime().availableProcessors()))
            .put("software", JSONObject()
                .put("android_release", Build.VERSION.RELEASE)
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("security_patch", Build.VERSION.SECURITY_PATCH)
                .put("build_id", Build.ID)
                .put("incremental", Build.VERSION.INCREMENTAL)
                .put("display_build", Build.DISPLAY)
                .put("fingerprint", Build.FINGERPRINT))
            .put("display", JSONObject()
                .put("width_pixels", metrics.widthPixels)
                .put("height_pixels", metrics.heightPixels)
                .put("density_dpi", metrics.densityDpi)
                .put("refresh_rate_hz", currentRefreshRate(context)))
            .put("memory", JSONObject()
                .put("total_bytes", memory.totalMem)
                .put("available_bytes", memory.availMem)
                .put("low_memory", memory.lowMemory))
            .put("storage", JSONObject()
                .put("internal_total_bytes", storage.totalBytes)
                .put("internal_available_bytes", storage.availableBytes))
            .put("runtime", JSONObject()
                .put("root_available", ShellRunner.canSu())
                .put("xposed_service_bound", ModspecApp.xposedService != null)
                .put("lsposed_framework", ModspecApp.xposedService?.frameworkName ?: JSONObject.NULL)
                .put("agent_version", agentVersion(context)))
            .put("apps", JSONObject()
                .put("total", packages.size)
                .put("system", systemCount)
                .put("user", packages.size - systemCount)
                .put("returned", entries.length())
                .put("truncated", includeApps && packages.size > limit)
                .put("entries", entries))
    }

    /**
     * `context.display` is illegal on a non-visual Context (applicationContext
     * throws on API 31+); use the DisplayManager default display instead, which
     * does not require the context to be display-associated. On older releases
     * fall back to the (deprecated but universal) window-manager default display.
     */
    private fun currentRefreshRate(context: Context): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            return displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.refreshRate ?: 0f
        }
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return windowManager?.defaultDisplay?.refreshRate ?: 0f
    }

    /** Agent version reported in inventory/status (from the installed APK). */
    fun agentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}

private fun android.content.pm.PackageInfo.isSystemPackage(): Boolean =
    (applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
