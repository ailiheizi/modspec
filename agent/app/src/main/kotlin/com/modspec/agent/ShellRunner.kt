package com.modspec.agent

import java.util.concurrent.TimeUnit

object ShellRunner {
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "ksu",
    )

    /**
     * The su channel worked but the wrapped command exited non-zero. The output
     * is preserved so callers can classify command-level failures (e.g. monkey
     * reporting "No activities found" → needs_trigger) instead of treating them
     * as a broken su binary.
     */
    class SuCommandFailed(val output: String) : Exception(output.ifBlank { "su command failed" })

    fun runSu(command: String): Result<String> {
        var lastError: Throwable? = null
        for (su in SU_CANDIDATES) {
            val result = runSuWith(su, command)
            if (result.isSuccess) return result
            val error = result.exceptionOrNull()
            // Command-level failure (exit != 0) means this su binary works;
            // stop probing and surface the output to the caller.
            if (error is SuCommandFailed) return result
            lastError = error
        }
        return Result.failure(lastError ?: IllegalStateException("no working su binary"))
    }

    private fun runSuWith(su: String, command: String): Result<String> = runCatching {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("su timed out")
        }
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() != 0) {
            throw SuCommandFailed(output)
        }
        output
    }

    fun canSu(): Boolean = workingSuPath() != null

    fun workingSuPath(): String? = SU_CANDIDATES.firstOrNull { su ->
        runSuWith(su, "id").map { it.contains("uid=0") }.getOrDefault(false)
    }

    fun fileExists(path: String): Boolean =
        runSu("test -e ${shellQuote(path)} && echo yes").getOrNull()?.contains("yes") == true

    /**
     * Start a long-lived command (e.g. `logcat`) without the 30s wait timeout.
     * The caller owns the returned process and must destroy it. Never blocks.
     */
    fun runSuStreaming(command: String): Process? {
        for (su in SU_CANDIDATES) {
            try {
                val process = ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                return process
            } catch (_: Exception) {
                // try the next su candidate
            }
        }
        return null
    }

    /** Quote a value for a single-quoted POSIX shell token. */
    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
