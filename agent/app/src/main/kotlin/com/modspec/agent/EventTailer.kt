package com.modspec.agent

import java.io.File

/**
 * Background ingest of hook-process events from logcat into [EventJournal].
 *
 * Hook processes (e.g. joyose) cannot write directly into the agent's channel
 * — libxposed API 102 offers no hook→service file/prefs write primitive, only
 * `module.log()` (LSPosed log / logcat). So the agent tails logcat live and
 * appends structured events to its own journal, which owns the cursor. Requires
 * root to read logcat; without it, only agent-orchestration events are served.
 *
 * The loop backfills with a one-shot `logcat -d` (dedup-safe) on start, then
 * tails live output line-by-line.
 */
object EventTailer {

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var activeProcess: Process? = null

    /** Bind the journal and start the tail loop at most once. Idempotent. */
    fun ensureStarted(context: android.content.Context) {
        EventJournal.init(File(context.filesDir, "events"))
        ScriptStateTracker.bind(context)
        if (running) return
        synchronized(this) {
            if (running) return
            running = true
            thread = Thread({ loop() }, "modspec-event-tailer")
                .also { it.isDaemon = true; it.start() }
        }
    }

    /** Read-only status for diagnostics RPCs. */
    fun isRunning(): Boolean = running

    @Synchronized
    fun stop() {
        running = false
        activeProcess?.destroy()
        val worker = thread
        worker?.interrupt()
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(1000L) }
        }
        activeProcess = null
        thread = null
    }

    private fun loop() {
        runCatching { LogTailReader.ingestFromLogcatDump() }
        while (running) {
            runCatching { tailLogcatLive() }
            if (!running) break
            try {
                Thread.sleep(1000L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun tailLogcatLive() {
        if (!ShellRunner.canSu()) return
        val filter = LogTailReader.TAGS.joinToString(" ") { "$it:V" }
        val process = ShellRunner.runSuStreaming("logcat -v epoch -s $filter") ?: return
        activeProcess = process
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    LogTailReader.ingestLine(line)
                }
            }
        } finally {
            process.destroy()
            if (activeProcess === process) activeProcess = null
        }
    }
}
