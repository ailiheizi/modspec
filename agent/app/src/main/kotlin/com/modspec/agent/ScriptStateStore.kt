package com.modspec.agent

import java.io.File

/**
 * Durable lifecycle state for script packages, stored in
 * `files/scripts/state.json`. The codec is hand-rolled (no Android `org.json`)
 * so persistence is fully testable on the JVM, mirroring [EventJournal].
 *
 * State is updated by the agent process: deploy/enable/disable/remove write
 * directly, and hook-process events (`script_loaded` / `script_hit` /
 * `script_error`) ingested from logcat flow into [recordEvent].
 */
object ScriptStateStore {

    const val STATE_FILE = "state.json"

    data class ScriptRecord(
        val scriptId: String,
        var hash: String = "",
        var generation: Long? = null,
        var engine: String = "",
        var version: String? = null,
        var packages: List<String> = emptyList(),
        var targetPackages: List<String> = emptyList(),
        var lastLoadedMs: Long? = null,
        var lastHitMs: Long? = null,
        var lastError: String? = null,
        var hitCount: Long = 0,
        var errorCount: Long = 0,
    )

    fun stateFile(dir: File): File = File(dir, STATE_FILE)

    fun read(dir: File): Pair<String?, MutableMap<String, ScriptRecord>> {
        val file = stateFile(dir)
        if (!file.exists()) return null to mutableMapOf()
        val text = runCatching { file.readText() }.getOrNull() ?: return null to mutableMapOf()
        return decode(text)
    }

    fun write(dir: File, active: String?, records: Map<String, ScriptRecord>) {
        stateFile(dir).parentFile?.mkdirs()
        stateFile(dir).writeText(encode(active, records))
    }

    fun recordEvent(dir: File, scriptId: String, event: String, message: String) {
        val (active, records) = read(dir)
        val record = records.getOrPut(scriptId) { ScriptRecord(scriptId) }
        when (event) {
            "script_loaded" -> {
                record.lastLoadedMs = System.currentTimeMillis()
                record.lastError = null
            }
            "script_hit" -> {
                record.lastHitMs = System.currentTimeMillis()
                record.hitCount += 1
            }
            "script_error" -> {
                record.lastError = message.take(500)
                record.errorCount += 1
            }
            "script_unloaded" -> {
                record.lastError = null
            }
        }
        write(dir, active, records)
    }

    fun activeScript(dir: File): String? = read(dir).first

    fun setActive(dir: File, scriptId: String?) {
        val (_, records) = read(dir)
        write(dir, scriptId, records)
    }

    fun setRecord(dir: File, record: ScriptRecord) {
        val (active, records) = read(dir)
        records[record.scriptId] = record
        write(dir, active, records)
    }

    fun removeRecord(dir: File, scriptId: String): Boolean {
        val (active, records) = read(dir)
        val removed = records.remove(scriptId) != null
        write(dir, if (active == scriptId) null else active, records)
        return removed
    }

    private fun encode(active: String?, records: Map<String, ScriptRecord>): String = buildString {
        append("{\"active_script\":")
        if (active == null) append("null") else append(jsonQuote(active))
        append(",\"scripts\":{")
        val sorted = records.values.sortedBy { it.scriptId }
        for ((index, record) in sorted.withIndex()) {
            if (index > 0) append(',')
            append(jsonQuote(record.scriptId)).append(":{")
            append("\"hash\":").append(jsonQuote(record.hash))
            append(",\"generation\":").append(record.generation?.toString() ?: "null")
            append(",\"engine\":").append(jsonQuote(record.engine))
            append(",\"version\":").append(record.version?.let(::jsonQuote) ?: "null")
            append(",\"packages\":").append(jsonStringArray(record.packages))
            append(",\"target_packages\":").append(jsonStringArray(record.targetPackages))
            append(",\"last_loaded_ms\":").append(record.lastLoadedMs?.toString() ?: "null")
            append(",\"last_hit_ms\":").append(record.lastHitMs?.toString() ?: "null")
            append(",\"last_error\":").append(record.lastError?.let(::jsonQuote) ?: "null")
            append(",\"hit_count\":").append(record.hitCount)
            append(",\"error_count\":").append(record.errorCount)
            append('}')
        }
        append("}}")
    }

    private fun jsonStringArray(values: List<String>): String = buildString {
        append('[')
        for ((index, value) in values.withIndex()) {
            if (index > 0) append(',')
            append(jsonQuote(value))
        }
        append(']')
    }

    internal fun decode(text: String): Pair<String?, MutableMap<String, ScriptRecord>> {
        val reader = ScriptStateReader(text)
        reader.expect('{')
        var active: String? = null
        val records = mutableMapOf<String, ScriptRecord>()
        while (true) {
            reader.skipWs()
            if (reader.peek() == '}') break
            val key = reader.readString()
            reader.skipWs()
            reader.expect(':')
            reader.skipWs()
            when (key) {
                "active_script" -> active = reader.readNullableString()
                "scripts" -> {
                    reader.expect('{')
                    while (true) {
                        reader.skipWs()
                        if (reader.peek() == '}') {
                            reader.skip()
                            break
                        }
                        val id = reader.readString()
                        reader.skipWs()
                        reader.expect(':')
                        reader.skipWs()
                        reader.expect('{')
                        val record = ScriptRecord(scriptId = id)
                        while (true) {
                            reader.skipWs()
                            if (reader.peek() == '}') {
                                reader.skip()
                                break
                            }
                            val field = reader.readString()
                            reader.skipWs()
                            reader.expect(':')
                            reader.skipWs()
                            when (field) {
                                "hash" -> record.hash = reader.readString()
                                "generation" -> record.generation = reader.readNullableLong()
                                "engine" -> record.engine = reader.readString()
                                "version" -> record.version = reader.readNullableString()
                                "packages" -> record.packages = reader.readStringArray()
                                "target_packages" -> record.targetPackages = reader.readStringArray()
                                "last_loaded_ms" -> record.lastLoadedMs = reader.readNullableLong()
                                "last_hit_ms" -> record.lastHitMs = reader.readNullableLong()
                                "last_error" -> record.lastError = reader.readNullableString()
                                "hit_count" -> record.hitCount = reader.readLong()
                                "error_count" -> record.errorCount = reader.readLong()
                                else -> reader.skipValue()
                            }
                            reader.skipWs()
                            if (reader.peek() == ',') reader.skip()
                        }
                        records[id] = record
                        reader.skipWs()
                        if (reader.peek() == ',') reader.skip()
                    }
                }
                else -> reader.skipValue()
            }
            reader.skipWs()
            if (reader.peek() == ',') reader.skip()
        }
        return active to records
    }

    internal fun jsonQuote(value: String): String = buildString {
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

/** Minimal reader for the restricted JSON dialect used by [ScriptStateStore]. */
private class ScriptStateReader(private val text: String) {
    private var index = 0

    fun peek(): Char = text[index]
    fun skip() {
        index++
    }

    fun skipWs() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    fun expect(char: Char) {
        skipWs()
        require(text[index] == char) { "expected '$char' at $index" }
        index++
    }

    fun readString(): String {
        skipWs()
        require(text[index] == '"') { "expected string at $index" }
        index++
        val out = StringBuilder()
        while (index < text.length && text[index] != '"') {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                when (text[index + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val hex = text.substring(index + 2, (index + 6).coerceAtMost(text.length))
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        index += 4
                    }
                    else -> out.append(text[index + 1])
                }
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        require(index < text.length) { "unterminated string" }
        index++
        return out.toString()
    }

    fun readNullableString(): String? {
        skipWs()
        return if (text[index] == 'n') {
            skipNull()
            null
        } else {
            readString()
        }
    }

    fun readLong(): Long {
        skipWs()
        val start = index
        while (index < text.length && (text[index].isDigit() || text[index] == '-')) index++
        return text.substring(start, index).toLong()
    }

    fun readNullableLong(): Long? {
        skipWs()
        return if (text[index] == 'n') {
            skipNull()
            null
        } else {
            readLong()
        }
    }

    fun readStringArray(): List<String> {
        skipWs()
        expect('[')
        val out = mutableListOf<String>()
        while (true) {
            skipWs()
            if (peek() == ']') {
                skip()
                break
            }
            out += readString()
            skipWs()
            if (peek() == ',') skip()
        }
        return out
    }

    fun skipValue() {
        skipWs()
        when {
            text[index] == '"' -> readString()
            text[index] == '[' -> {
                skip()
                while (index < text.length && text[index] != ']') {
                    if (text[index] == '"') readString() else skip()
                }
                if (index < text.length) skip()
            }
            text[index] == '{' -> {
                skip()
                while (index < text.length && text[index] != '}') {
                    if (text[index] == '"') readString() else skip()
                }
                if (index < text.length) skip()
            }
            text[index] == 'n' -> skipNull()
            else -> {
                while (index < text.length && text[index] != ',' && text[index] != '}') skip()
            }
        }
    }

    private fun skipNull() {
        index += 4 // "null"
    }
}
