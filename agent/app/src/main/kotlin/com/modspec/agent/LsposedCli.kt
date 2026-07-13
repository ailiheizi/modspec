package com.modspec.agent

import java.util.concurrent.TimeUnit

/**
 * Shell-out to LSPosed CLI ([mywalkb/LSPosed_mod](https://github.com/mywalkb/LSPosed_mod/wiki/CLI)).
 * Requires root + CLI enabled in LSPosed Manager settings.
 */
object LsposedCli {

    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private val CLI_CANDIDATES = listOf(
        "/data/adb/lspd/bin/cli",
        "/data/adb/lsposed/bin/cli",
        "/data/adb/lspd/bin/lsposed",
    )

    private const val TIMEOUT_SEC = 30L

    private fun resolveCli(): String? {
        CLI_CANDIDATES.firstOrNull { ShellRunner.fileExists(it) }?.let { return it }
        // Post-update layouts (e.g. JingMatrix/Vector 675208+) may place cli elsewhere.
        return ShellRunner.runSu(
            "find /data/adb -maxdepth 5 -type f \\( -name cli -o -name lsposed \\) 2>/dev/null | head -n 5",
        ).getOrNull()
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && ShellRunner.fileExists(it) }
    }

    fun isAvailable(): Boolean = resolveCli()?.let { cli ->
        runWithCli(cli, "status").isSuccess
    } ?: false

    fun cliPath(): String? = resolveCli()

    fun statusLabel(): String? {
        val cli = resolveCli() ?: return null
        return if (isAvailable()) "ok@$cli" else "found@$cli (status failed)"
    }

    fun run(vararg args: String): Result<String> {
        val cli = resolveCli()
            ?: return Result.failure(IllegalStateException("lsposed-cli not found under ${CLI_CANDIDATES.joinToString()}"))
        return runWithCli(cli, *args)
    }

    private fun resolvePin(): String {
        System.getenv("LSPOSED_CLI_PIN")?.takeIf { it.isNotBlank() }?.let { return it }
        val ctx = appContext ?: return ""
        return ctx.getSharedPreferences("modspec_lsposed", android.content.Context.MODE_PRIVATE)
            .getString("cli_pin", "")
            .orEmpty()
    }

    private fun runWithCli(cli: String, vararg args: String): Result<String> = runCatching {
        val pin = resolvePin()
        val inner = buildString {
            append(cli)
            if (pin.isNotBlank()) append(" --pin ").append(pin)
            args.forEach { append(' ').append(shellQuote(it)) }
        }
        ShellRunner.runSu(inner).getOrThrow()
    }

    fun enableModule(packageName: String): Result<String> =
        run("modules", "set", "-e", packageName)

    fun disableModule(packageName: String): Result<String> =
        run("modules", "set", "-d", packageName)

    fun setScope(module: String, apps: List<String>, mode: ScopeMode = ScopeMode.SET): Result<String> {
        val flag = when (mode) {
            ScopeMode.SET -> "-s"
            ScopeMode.APPEND -> "-a"
            ScopeMode.REMOVE -> "-d"
        }
        return run(*buildList {
            add("scope")
            add("set")
            add(flag)
            add(module)
            addAll(apps)
        }.toTypedArray())
    }

    enum class ScopeMode { SET, APPEND, REMOVE }

    private fun shellQuote(value: String): String =
        if (value.any { it.isWhitespace() || it == '\'' || it == '"' }) {
            "'${value.replace("'", "'\\''")}'"
        } else {
            value
        }
}
