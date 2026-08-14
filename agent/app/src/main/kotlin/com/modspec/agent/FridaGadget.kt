package com.modspec.agent

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Deploys the Frida gadget (on-demand, PC-provided) and per-script Frida
 * companions into `/data/local/tmp/modspec/frida/` — a directory every hooked
 * process can enter (data_local_tmp), with files relabeled `apk_data_file` so
 * they are readable and dlopen-able by untrusted apps.
 *
 * The module APK dir is NOT usable for config/script reads: `System.load`
 * succeeds there only because the native loader proxies it, while the gadget
 * itself opens sibling files as the host uid (denied for untrusted_app).
 *
 * Flow: the PC pushes `libfrida-gadget-<abi>.so` to `/data/local/tmp/`
 * (staging), then calls `install_frida_gadget`; the agent (root) moves it
 * into the shared dir, writes the gadget config (sibling `libfrida-gadget.config.so`)
 * and the active script's `frida.js`.
 */
object FridaGadget {
    // Staging lives directly in /data/local/tmp (shell-writable).
    const val STAGING_DIR = "/data/local/tmp"
    // Shared runtime dir (root-owned but world-traversable; files relabeled).
    const val SHARED_DIR = "/data/local/tmp/modspec/frida"
    // Neutral gadget name: games with anti-Frida checks exit when they see a
    // library named like Frida or a well-known gadget port. Frida officially
    // supports arbitrary binary names ("dodging anti-Frida detection schemes").
    const val GADGET_FILE_NAME = "libmodsecurity.so"
    // Config sibling: `<gadget name minus .so>.config.so`.
    const val GADGET_CONFIG_NAME = "libmodsecurity.config.so"
    // Uncommon listen port (avoid 27042 which anti-cheat probes).
    const val LISTEN_PORT = 38457

    private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** Absolute gadget path (shared, stable across reinstalls). */
    fun gadgetPath(): String = "$SHARED_DIR/$GADGET_FILE_NAME"

    /** Absolute frida.js path for a script id. */
    fun scriptPath(scriptId: String): String =
        "$SHARED_DIR/${ScriptManager.safeScriptId(scriptId)}.js"

    /** Install the staged gadget (root move + relabel). Single su call. */
    fun install(context: Context): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in ABIS } ?: return false
        val staged = File(STAGING_DIR, "libfrida-gadget-$abi.so")
        val target = File(gadgetPath())
        val cmd = buildString {
            append("mkdir -p '$SHARED_DIR' && ")
            append("cp -f '${staged.absolutePath}' '${target.absolutePath}' && ")
            append("chmod 755 '${target.absolutePath}' && ")
            append("chcon u:object_r:apk_data_file:s0 '${target.absolutePath}' 2>/dev/null; ")
            append("test -e '${target.absolutePath}'")
        }
        return ShellRunner.runSu(cmd).isSuccess
    }

    /**
     * Deploy the active script's frida.js + gadget config (root, relabeled).
     * Idempotent; called by ScriptManager when a frida-capable script deploys.
     *
     * The gadget runs in LISTEN mode with `on_load: resume` — the autonomous
     * script mode stalls on Android (GLib async scheduling has no main loop in
     * app processes), so the PC connects interactively instead:
     *   adb forward tcp:27042 tcp:27042
     *   frida -H 127.0.0.1:27042 -n Gadget -l frida.js
     */
    fun deployScript(context: Context, scriptId: String, fridaJs: String): Boolean {
        if (!ShellRunner.canSu()) return false
        val safe = ScriptManager.safeScriptId(scriptId) ?: return false
        val scriptFile = File(scriptPath(scriptId))
        val configFile = File(SHARED_DIR, GADGET_CONFIG_NAME)
        val config =
            """{"interaction":{"type":"listen","address":"127.0.0.1","port":$LISTEN_PORT,"on_load":"resume"}}"""
        val stagingScript = File(context.cacheDir, "modspec-frida.js")
        val stagingConfig = File(context.cacheDir, "gadget.config")
        try {
            stagingScript.writeText(fridaJs)
            stagingConfig.writeText(config)
            val cmd = buildString {
                append("mkdir -p '$SHARED_DIR' && ")
                append("cp -f '${stagingScript.absolutePath}' '${scriptFile.absolutePath}' && ")
                append("chmod 644 '${scriptFile.absolutePath}' && ")
                append("cp -f '${stagingConfig.absolutePath}' '${configFile.absolutePath}' && ")
                append("chmod 644 '${configFile.absolutePath}' && ")
                append("chcon u:object_r:apk_data_file:s0 '${scriptFile.absolutePath}' 2>/dev/null; ")
                append("chcon u:object_r:apk_data_file:s0 '${configFile.absolutePath}' 2>/dev/null; ")
                append("true")
            }
            val ok = ShellRunner.runSu(cmd).isSuccess && ShellRunner.fileExists(scriptFile.absolutePath)
            android.util.Log.i(
                "FridaGadget",
                "deployScript $scriptId -> ${scriptFile.absolutePath} ok=$ok config=${configFile.absolutePath}",
            )
            return ok
        } catch (error: Throwable) {
            android.util.Log.e("FridaGadget", "deployScript $scriptId failed: ${error.message}")
            return false
        }
    }

    /** Whether the gadget is present (hook-process check). */
    fun isInstalled(): Boolean = File(gadgetPath()).isFile
}
