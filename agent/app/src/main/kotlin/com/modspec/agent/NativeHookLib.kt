package com.modspec.agent

import android.content.Context
import java.io.File

/**
 * On-demand deployment of the ModSpec native hook library (xhook PLT bridge,
 * `libmodspec_native.so`): PC pushes it to `/data/local/tmp/` staging, the
 * agent (root) installs it into the shared dir where hook processes (the
 * script's own package, typically untrusted_app) can dlopen it.
 *
 * Unlike the Frida gadget this needs no listen port and no agent session —
 * hook registration happens in-process via JNI, fully under ModSpec control.
 */
object NativeHookLib {
    const val STAGING_DIR = "/data/local/tmp"
    const val SHARED_DIR = "/data/local/tmp/modspec/frida"
    const val LIB_NAME = "libmodspec_native.so"

    private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun libPath(): String = "$SHARED_DIR/$LIB_NAME"

    /** Install from the staged location (root, world-readable). Single su call. */
    fun install(context: Context): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in ABIS } ?: return false
        val staged = File(STAGING_DIR, "libmodspec_native-$abi.so")
        val target = File(libPath())
        val cmd = buildString {
            append("mkdir -p '$SHARED_DIR' && ")
            append("cp -f '${staged.absolutePath}' '${target.absolutePath}' && ")
            append("chmod 755 '${target.absolutePath}' && ")
            append("chcon u:object_r:apk_data_file:s0 '${target.absolutePath}' 2>/dev/null; ")
            append("test -e '${target.absolutePath}'")
        }
        return ShellRunner.runSu(cmd).isSuccess
    }
}
