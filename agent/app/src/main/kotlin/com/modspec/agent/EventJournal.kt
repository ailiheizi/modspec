package com.modspec.agent

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Agent-owned, bounded, structured event ring + durable journal.
 *
 * Replaces the fragile logcat millisecond cursor for interactive sessions:
 * every event is assigned a monotonic [HookEvent.eventId] at append time, and
 * the PC polls with the opaque cursor `after_event_id`/`next_event_id`, so a
 * batch boundary can neither duplicate nor lose events while the ring retains
 * history. If the PC's cursor predates the oldest retained event, [truncated]
 * is surfaced so the caller knows history was rotated away.
 *
 * Sources that feed the ring:
 * - Agent-orchestration events (deploy/restart) are appended directly.
 * - Hook-process events are ingested from logcat by [EventTailer] (hook
 *   processes can only reach logcat / LSPosed log via libxposed `module.log`
 *   in API 102; there is no hook→service write primitive), which is then
 *   persisted here.
 *
 * Storage is bounded ([MAX_EVENTS] in memory, [MAX_JOURNAL_BYTES] on disk) and
 * thread-safe (all mutations happen under an internal lock). The journal uses
 * append-only writes with an atomic rotate (temp file + rename) when it grows
 * past the bound, mirroring the ring.
 */
object EventJournal {

    const val MAX_EVENTS = 2000
    const val MAX_JOURNAL_BYTES = 512L * 1024L
    private const val JOURNAL_FILE = "events.ndjson"

    private val lock = Any()
    private val entries = ArrayDeque<HookEvent>()
    private val seenSignatures = HashSet<String>()
    private val seenOrder = ArrayDeque<String>()
    private var nextEventId = 1L
    private var journalFile: File? = null
    private var journalBytes = 0L
    private var journalError: String? = null

    /** Bind the journal to a directory and seed the ring from disk. Idempotent. */
    fun init(dir: File) {
        synchronized(lock) {
            val requestedFile = File(dir, JOURNAL_FILE).absoluteFile
            if (journalFile?.absoluteFile == requestedFile) return
            // Reset in-memory state so re-binding (e.g. a new filesDir, or a
            // fresh unit test) does not inherit stale entries/ids.
            entries.clear()
            seenSignatures.clear()
            seenOrder.clear()
            nextEventId = 1L
            journalBytes = 0L
            journalError = null
            journalFile = requestedFile
            seedLocked()
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    /** Whether the journal is durable-backed (vs. degraded in-memory only). */
    fun sourceLabel(): String =
        synchronized(lock) { if (journalError == null) "journal" else "ring_only" }

    fun journalError(): String? = synchronized(lock) { journalError }

    /**
     * Append an agent-orchestration event. Never deduplicated (each call is a
     * distinct occurrence). Returns the stored event with its assigned id.
     */
    fun append(
        timestampMs: Long = System.currentTimeMillis(),
        level: String = "I",
        tag: String = "ModspecAgent",
        event: String,
        generation: Long? = null,
        ruleId: String? = null,
        scriptId: String? = null,
        packageName: String? = null,
        message: String,
        raw: String? = null,
    ): HookEvent {
        val draft = HookEvent(
            eventId = 0L,
            timestampMs = timestampMs,
            level = level,
            tag = tag,
            event = event,
            generation = generation,
            ruleId = ruleId,
            scriptId = scriptId,
            packageName = packageName,
            message = message,
            raw = raw,
        )
        return append(draft)
    }

    /** Append a fully-formed event (tests / callers that already built one). */
    fun append(draft: HookEvent): HookEvent =
        synchronized(lock) { insertLocked(draft, dedupe = false)!! }

    /**
     * Ingest a hook-process event (e.g. parsed from logcat). Deduplicated by
     * content signature so logcat dump/tail overlaps do not double-count.
     */
    fun appendIfNew(draft: HookEvent): HookEvent? =
        synchronized(lock) { insertLocked(draft, dedupe = true) }

    /**
     * Incremental collection. Returns only events with `event_id > afterEventId`
     * (plus filters), the cursor to echo next, the oldest retained id, and
     * whether the requested cursor already fell out of the ring.
     */
    fun collect(
        afterEventId: Long?,
        limit: Int,
        ruleId: String?,
        scriptId: String?,
        minGeneration: Long?,
        exactGeneration: Long?,
    ): EventCollectResult = synchronized(lock) {
        val safeLimit = limit.coerceIn(1, 1000)
        val firstEventId = entries.firstOrNull()?.eventId
        val truncated = afterEventId != null && firstEventId != null && afterEventId < firstEventId
        val result = ArrayList<HookEvent>()
        for (event in entries) {
            if (afterEventId != null && event.eventId <= afterEventId) continue
            if (ruleId != null && event.ruleId != ruleId) continue
            if (scriptId != null && event.scriptId != scriptId) continue
            if (minGeneration != null && (event.generation ?: -1L) < minGeneration) continue
            if (exactGeneration != null && event.generation != exactGeneration) continue
            result.add(event)
            if (result.size >= safeLimit) break
        }
        val next = result.lastOrNull()?.eventId ?: (afterEventId ?: 0L)
        EventCollectResult(result, next, firstEventId, truncated)
    }

    private fun insertLocked(draft: HookEvent, dedupe: Boolean): HookEvent? {
        if (dedupe) {
            val signature = signatureOf(draft)
            if (seenSignatures.contains(signature)) return null
            seenSignatures.add(signature)
            seenOrder.addLast(signature)
            while (seenOrder.size > MAX_EVENTS) {
                seenSignatures.remove(seenOrder.removeFirst())
            }
        }
        val assigned = draft.copy(eventId = nextEventId++)
        entries.addLast(assigned)
        if (entries.size > MAX_EVENTS) entries.removeFirst()
        persistLocked(assigned)
        return assigned
    }

    private fun signatureOf(event: HookEvent): String = buildString {
        append(event.timestampMs).append('|')
        append(event.level).append('|')
        append(event.event).append('|')
        append(event.generation).append('|')
        append(event.ruleId).append('|')
        append(event.scriptId).append('|')
        append(event.packageName).append('|')
        append(event.message)
    }

    private fun persistLocked(event: HookEvent) {
        val file = journalFile ?: return
        try {
            file.parentFile?.mkdirs()
            val line = toJsonLine(event)
            file.appendText("$line\n")
            journalBytes += line.length + 1L
            if (journalBytes > MAX_JOURNAL_BYTES) rotateLocked()
        } catch (error: Exception) {
            journalError = error.message ?: "journal write failed"
        }
    }

    /** Atomically rewrite the journal to mirror the current ring (bounded). */
    private fun rotateLocked() {
        val file = journalFile ?: return
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(buildString {
                for (event in entries) {
                    append(toJsonLine(event)).append('\n')
                }
            })
            if (tmp.renameTo(file)) {
                journalBytes = file.length()
            } else {
                tmp.delete()
                val raf = RandomAccessFile(file, "rw")
                raf.setLength(0)
                for (event in entries) {
                    raf.writeBytes(toJsonLine(event) + "\n")
                }
                raf.close()
                journalBytes = file.length()
            }
        } catch (error: Exception) {
            journalError = error.message ?: "journal rotate failed"
        }
    }

    private fun seedLocked() {
        val file = journalFile ?: return
        if (!file.exists()) return
        try {
            var maxId = 0L
            for (line in file.readLines()) {
                val event = parseJsonLine(line) ?: continue
                if (event.eventId > maxId) maxId = event.eventId
                if (entries.size >= MAX_EVENTS) entries.removeFirst()
                entries.addLast(event)
            }
            nextEventId = maxId + 1
            seenSignatures.clear()
            seenOrder.clear()
            for (event in entries) {
                val signature = signatureOf(event)
                seenSignatures.add(signature)
                seenOrder.addLast(signature)
            }
            journalBytes = file.length()
        } catch (error: Exception) {
            journalError = error.message ?: "journal seed failed"
        }
    }

    /**
     * Journal lines use a hand-rolled JSON object codec that does NOT depend on
     * Android's `org.json` (which is stubbed in JVM unit tests and would make
     * persistence untestable). It is our internal durable format, not the RPC
     * wire format (that one uses [HookEvent.toJson] on-device).
     */
    internal fun parseJsonLine(line: String): HookEvent? {
        val parsed = runCatching { JsonLineReader(line) }.getOrElse { return null }
        val eventId = parsed.number("event_id") ?: return null
        val timestampMs = parsed.number("timestamp_ms") ?: return null
        return HookEvent(
            eventId = eventId,
            timestampMs = timestampMs,
            level = parsed.string("level") ?: "I",
            tag = parsed.string("tag") ?: "ModspecAgent",
            event = parsed.string("event") ?: "log",
            generation = parsed.number("generation"),
            ruleId = parsed.string("rule_id"),
            scriptId = parsed.string("script_id"),
            packageName = parsed.string("package"),
            message = parsed.string("message") ?: "",
            raw = parsed.string("raw"),
        )
    }

    internal fun toJsonLine(event: HookEvent): String = buildString {
        append("{\"event_id\":").append(event.eventId)
        append(",\"timestamp_ms\":").append(event.timestampMs)
        append(",\"level\":").append(jsonQuote(event.level))
        append(",\"tag\":").append(jsonQuote(event.tag))
        append(",\"event\":").append(jsonQuote(event.event))
        event.generation?.let { append(",\"generation\":").append(it) }
        event.ruleId?.let { append(",\"rule_id\":").append(jsonQuote(it)) }
        event.scriptId?.let { append(",\"script_id\":").append(jsonQuote(it)) }
        event.packageName?.let { append(",\"package\":").append(jsonQuote(it)) }
        append(",\"message\":").append(jsonQuote(event.message))
        event.raw?.let { append(",\"raw\":").append(jsonQuote(it)) }
        append('}')
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
        append('"')
    }
}

/**
 * Minimal single-object JSON reader for our own journal lines (key/value pairs
 * only, values are either a quoted string or a long). Anything else is
 * rejected, so corrupt lines are skipped rather than crashing the seed.
 */
private class JsonLineReader(line: String) {
    private val text = line
    private var index = 0

    init {
        skipWs()
        require(text[index] == '{') { "not an object" }
        index++
    }

    fun string(key: String): String? {
        val raw = value(key) ?: return null
        if (!raw.startsWith('"')) return null
        return unescape(raw)
    }

    fun number(key: String): Long? {
        val raw = value(key) ?: return null
        return raw.toLongOrNull()
    }

    private fun value(key: String): String? {
        skipWs()
        if (text[index] == ',') index++
        skipWs()
        if (text[index] == '}') return null
        // key
        skipWs()
        require(text[index] == '"') { "expected key" }
        val keyStart = index
        index++
        while (text[index] != '"') {
            if (text[index] == '\\') index++
            index++
        }
        // Keys in our own journal format are plain ASCII; no escaping to undo.
        val parsedKey = text.substring(keyStart + 1, index)
        index++
        skipWs()
        require(text[index] == ':') { "expected colon" }
        index++
        skipWs()
        if (parsedKey != key) {
            skipValue()
            return value(key)
        }
        // value
        val valueStart = index
        if (text[index] == '"') {
            index++
            while (text[index] != '"') {
                if (text[index] == '\\') index++
                index++
            }
            index++
        } else {
            while (text[index] != ',' && text[index] != '}') index++
        }
        return text.substring(valueStart, index)
    }

    private fun skipValue() {
        skipWs()
        if (text[index] == '"') {
            index++
            while (text[index] != '"') {
                if (text[index] == '\\') index++
                index++
            }
            index++
        } else {
            while (index < text.length && text[index] != ',' && text[index] != '}') index++
        }
        if (index < text.length && text[index] == ',') index++
    }

    private fun skipWs() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun unescape(raw: String): String {
        if (raw.length < 2) return raw
        val body = raw.substring(1, raw.length - 1)
        if ('\\' !in body) return body
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val char = body[i]
            if (char == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val hex = body.substring(i + 2, (i + 6).coerceAtMost(body.length))
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> out.append(body[i + 1])
                }
                i += 2
            } else {
                out.append(char)
                i++
            }
        }
        return out.toString()
    }
}

/** A single structured event with a monotonic, cursor-safe id. */
data class HookEvent(
    val eventId: Long,
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val event: String,
    val generation: Long?,
    val ruleId: String?,
    val scriptId: String?,
    val packageName: String?,
    val message: String,
    val raw: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("timestamp_ms", timestampMs)
        .put("level", level)
        .put("tag", tag)
        .put("event", event)
        .putOpt("generation", generation)
        .putOpt("rule_id", ruleId)
        .putOpt("script_id", scriptId)
        .putOpt("package", packageName)
        .put("message", message)
        .putOpt("raw", raw)
}

/** Result of one incremental [EventJournal.collect] poll. */
data class EventCollectResult(
    val entries: List<HookEvent>,
    val nextEventId: Long,
    val firstEventId: Long?,
    val truncated: Boolean,
)
