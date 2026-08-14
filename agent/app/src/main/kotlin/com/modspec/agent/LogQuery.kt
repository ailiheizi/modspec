package com.modspec.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, filtered retrieval of raw diagnostic logcat, kept deliberately
 * separate from the structured hook-event journal served by `collect_logs`.
 *
 * Filters: exact tag, package (resolved to live pids, then to tid/pid), and a
 * `since_ms` cutoff. The dump is `logcat -d -v threadtime -t <max>` so reads
 * are bounded before filtering; `truncated` reports lines dropped by the cap.
 * Requires root on modern Android to read other apps' logs; without it the
 * response reports `root_available=false` and an empty entry list rather than
 * silently returning nothing.
 */
object LogQuery {

    const val MAX_LIMIT = 1000

    fun fetch(packageFilter: String?, tag: String?, limit: Int, sinceMs: Long?): JSONObject {
        if (!ShellRunner.canSu()) {
            return JSONObject()
                .put("entries", JSONArray())
                .put("truncated", false)
                .put("source", "none")
                .put("root_available", false)
                .put("resolved_pids", JSONArray())
        }
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val pids = packageFilter?.let { ProcessInspector.list(it, 100).pidsOf() }.orEmpty()
        val maxLines = (cap * 40).coerceIn(2000, 20000)
        val cmd = "logcat -d -v threadtime -t $maxLines 2>/dev/null"
        val raw = ShellRunner.runSu(cmd).getOrNull().orEmpty()
        val entries = JSONArray()
        var truncated = false
        for (line in raw.lineSequence()) {
            if (!matchesFilters(line, pids, tag, sinceMs)) continue
            val entry = entryJson(line) ?: continue
            if (entries.length() >= cap) {
                truncated = true
                break
            }
            entries.put(entry)
        }
        return JSONObject()
            .put("entries", entries)
            .put("truncated", truncated)
            .put("source", "logcat")
            .put("root_available", true)
            .put("resolved_pids", JSONArray(pids))
    }

    private fun JSONObject.pidsOf(): List<Int> =
        optJSONArray("processes")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { index ->
                    val pid = arr.optJSONObject(index)?.optInt("pid", -1) ?: -1
                    pid.takeIf { it > 0 }
                }
            }
            .orEmpty()

    /** Pure filter: pid set (if any), exact tag, since_ms cutoff. */
    internal fun matchesFilters(
        line: String,
        pids: List<Int>,
        tag: String?,
        sinceMs: Long?,
    ): Boolean {
        val parsed = parseThreadTimeLine(line) ?: return false
        if (sinceMs != null && parsed.timestampMs < sinceMs) return false
        if (pids.isNotEmpty() && parsed.tid !in pids && parsed.pid !in pids) return false
        if (tag != null && !parsed.tag.equals(tag, ignoreCase = true)) return false
        return true
    }

    internal data class ThreadTimeLine(
        val timestampMs: Long,
        val pid: Int,
        val tid: Int,
        val level: Char,
        val tag: String,
        val message: String,
    )

    /**
     * Parse one `logcat -v threadtime` line:
     * `08-07 12:34:56.789  1234  1234 E Tag    : message`
     */
    internal fun parseThreadTimeLine(line: String): ThreadTimeLine? {
        val match = THREADTIME_LINE.matchEntire(line) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].toIntOrNull() ?: return null
        val seconds = match.groupValues[5].toIntOrNull() ?: return null
        val millis = match.groupValues[6].toIntOrNull() ?: 0
        val year = currentYear()
        var timestampMs = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, seconds)
        }.timeInMillis + millis
        // logcat has no year; clamp near the current time rather than
        // producing a wildly stale epoch (e.g. new year's rollover).
        val now = System.currentTimeMillis()
        if (timestampMs > now + 24 * 3600_000L) timestampMs -= 365L * 24 * 3600_000L
        return ThreadTimeLine(
            timestampMs = timestampMs,
            pid = match.groupValues[7].toIntOrNull() ?: 0,
            tid = match.groupValues[8].toIntOrNull() ?: 0,
            level = match.groupValues[9].first(),
            tag = match.groupValues[10],
            message = match.groupValues[11],
        )
    }

    private fun currentYear(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    internal fun entryJson(line: String): JSONObject? {
        val parsed = parseThreadTimeLine(line) ?: return null
        return JSONObject()
            .put("timestamp_ms", parsed.timestampMs)
            .put("level", parsed.level.toString())
            .put("tag", parsed.tag)
            .put("pid", parsed.pid.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("tid", parsed.tid.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("message", parsed.message)
    }

    internal val THREADTIME_LINE = Regex(
        """(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)""",
    )
}
