package com.modspec.agent

/**
 * 采集 ModSpec 相关 logcat 尾行 — 供 Hook 管家 UI 与 [EventTailer] 摄入。
 *
 * 本文件不再拥有 PC 采集的 cursor；可靠 cursor 由 [EventJournal]（event_id）
 * 提供。这里只负责：UI 的 `tail`、把 logcat 行解析成结构化 [HookEvent]，
 * 以及一次性 `logcat -d` 回填（兼容旧客户端/冷启动）。
 */
object LogTailReader {

    internal val TAGS = listOf(
        "ModspecModule",
        "ModspecRuleEngine",
        "ModspecScript",
        "ModspecAgent",
        "Frida",
    )

    fun tail(lines: Int = 12): List<String> {
        if (!ShellRunner.canSu()) return emptyList()
        val filter = TAGS.joinToString(" ") { "$it:I" }
        val cmd = "logcat -d -t ${lines * 3} -s $filter 2>/dev/null"
        val raw = ShellRunner.runSu(cmd).getOrNull()?.lineSequence()?.toList().orEmpty()
        return raw.takeLast(lines)
    }

    /**
     * Parse a single logcat epoch line into a structured [HookEvent] and ingest
     * it into the journal (deduplicated). Returns the stored event or null.
     */
    fun ingestLine(line: String): HookEvent? {
        val parsed = parseEpochLine(line) ?: return null
        val stored = EventJournal.appendIfNew(
            HookEvent(
                eventId = 0L,
                timestampMs = parsed.timestampMs,
                level = parsed.level,
                tag = parsed.tag,
                event = parsed.event,
                generation = parsed.generation,
                ruleId = parsed.ruleId,
                scriptId = parsed.scriptId,
                packageName = parsed.packageName,
                message = parsed.message,
                raw = line,
            ),
        )
        // Reflect hook-process script lifecycle into the agent's persisted state.
        if (stored != null && parsed.scriptId != null &&
            parsed.event in SCRIPT_STATE_EVENTS
        ) {
            ScriptStateTracker.onEvent(
                ScriptStateTracker.context(),
                parsed.scriptId,
                parsed.event,
                parsed.message,
            )
        }
        return stored
    }

    /** Events that update persisted script lifecycle state. */
    internal val SCRIPT_STATE_EVENTS = setOf(
        "script_loaded",
        "script_unloaded",
        "script_hit",
        "script_error",
    )

    /**
     * One-shot `logcat -d` backfill into the journal (compatibility/legacy path).
     * Deduped by content signature, so overlaps with a running tailer are safe.
     * Returns the number of newly ingested events.
     */
    fun ingestFromLogcatDump(maxLines: Int = 4000): Int {
        if (!ShellRunner.canSu()) return 0
        val filter = TAGS.joinToString(" ") { "$it:V" }
        val cmd = "logcat -d -v epoch -t $maxLines -s $filter 2>/dev/null"
        val raw = ShellRunner.runSu(cmd).getOrNull()?.lineSequence().orEmpty()
        var ingested = 0
        for (line in raw) {
            if (ingestLine(line) != null) ingested++
        }
        return ingested
    }

    internal fun parseEpochLine(line: String): ParsedEntry? {
        val match = EPOCH_LINE.matchEntire(line) ?: return null
        val timestampMs = (match.groupValues[1].toDoubleOrNull()?.times(1000))?.toLong() ?: return null
        val level = match.groupValues[2]
        val tag = match.groupValues[3].trim()
        val message = match.groupValues[4]
        val payload = runCatching { org.json.JSONObject(message) }.getOrNull()
        return ParsedEntry(
            timestampMs = timestampMs,
            level = level,
            tag = tag,
            event = payload?.optString("event")?.takeIf { it.isNotBlank() } ?: "log",
            generation = payload?.takeIf { it.has("generation") }?.optLong("generation"),
            ruleId = payload?.optString("rule_id")?.takeIf { it.isNotBlank() },
            scriptId = payload?.optString("script_id")?.takeIf { it.isNotBlank() },
            packageName = payload?.optString("package")?.takeIf { it.isNotBlank() },
            message = payload?.optString("message")?.takeIf { it.isNotBlank() } ?: message,
        )
    }

    internal data class ParsedEntry(
        val timestampMs: Long,
        val level: String,
        val tag: String,
        val event: String,
        val generation: Long?,
        val ruleId: String?,
        val scriptId: String?,
        val packageName: String?,
        val message: String,
    )

    internal val EPOCH_LINE =
        Regex("""\s*(\d+(?:\.\d+)?)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+):\s?(.*)""")
}
