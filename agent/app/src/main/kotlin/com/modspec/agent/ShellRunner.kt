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

    fun runSu(command: String): Result<String> {
        var lastError: Throwable? = null
        for (su in SU_CANDIDATES) {
            val result = runSuWith(su, command)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
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
            error(output.ifBlank { "su exited ${process.exitValue()}" })
        }
        output
    }

    fun canSu(): Boolean = workingSuPath() != null

    fun workingSuPath(): String? = SU_CANDIDATES.firstOrNull { su ->
        runSuWith(su, "id").map { it.contains("uid=0") }.getOrDefault(false)
    }

    fun fileExists(path: String): Boolean =
        runSu("test -e '$path' && echo yes").getOrNull()?.contains("yes") == true
}
