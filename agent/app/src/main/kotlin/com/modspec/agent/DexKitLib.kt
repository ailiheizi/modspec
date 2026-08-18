package com.modspec.agent

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Shares the DexKit native library with hooked processes.
 *
 * Hooked processes (e.g. Security Center) cannot `System.loadLibrary("dexkit")`
 * — the module APK's libs are not on the host's native library path — and
 * cannot dlopen from their own data dir (SELinux denies execute) nor from
 * `/data/local/tmp` (system domains have no search permission on the dir).
 * The agent process (root) therefore deploys `libdexkit.so` into its own APK
 * install directory (`/data/app/.../<pkg>==/modspec_lib/`, `apk_data_file`
 * SELinux label) — a location every process can read and execute.
 */
object DexKitLib {
    const val SHARED_LIB_DIR_NAME = "modspec_lib"

    private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * The shared so path for hook processes: next to the module APK.
     * Derives from the module APK path (also valid in hooked processes).
     */
    fun sharedLibPath(moduleApkPath: String): String =
        File(File(moduleApkPath).parentFile, "$SHARED_LIB_DIR_NAME/libdexkit.so").absolutePath

    /** Idempotent: deploys the so where hooked processes can dlopen it. */
    fun ensureShared(context: Context) {
        val tag = "DexKitLib"
        if (!ShellRunner.canSu()) {
            android.util.Log.w(tag, "ensureShared: no root, skip")
            return
        }
        val apkPath = context.applicationInfo.sourceDir
        val apk = File(apkPath)
        if (!apk.isFile) {
            android.util.Log.w(tag, "ensureShared: apk not a file: $apkPath")
            return
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in ABIS }
        if (abi == null) {
            android.util.Log.w(tag, "ensureShared: no supported abi in ${Build.SUPPORTED_ABIS.contentToString()}")
            return
        }
        val entryName = "lib/$abi/libdexkit.so"
        val staging = File(context.cacheDir, "libdexkit.so")
        try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryName) ?: return
                zip.getInputStream(entry).use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val targetDir = File(apk.parentFile, SHARED_LIB_DIR_NAME)
            val target = File(targetDir, "libdexkit.so")
            val cmd = buildString {
                append("mkdir -p '${targetDir.absolutePath}' && ")
                append("cp -f '${staging.absolutePath}' '${target.absolutePath}' && ")
                append("chmod 755 '${target.absolutePath}' && ")
                append("chcon u:object_r:apk_data_file:s0 '${target.absolutePath}' 2>/dev/null; ")
                append("true")
            }
            val result = ShellRunner.runSu(cmd)
            if (result.isFailure || !ShellRunner.fileExists(target.absolutePath)) {
                android.util.Log.e(
                    "ModspecAgent",
                    "DexKitLib deploy failed: ${result.exceptionOrNull()?.message} target=${target.absolutePath}",
                )
            }
        } catch (error: Throwable) {
            android.util.Log.e("ModspecAgent", "DexKitLib deploy exception: ${error.message}")
        }
    }
}
