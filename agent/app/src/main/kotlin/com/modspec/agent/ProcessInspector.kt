package com.modspec.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only process inventory (`ps -A` based, root-assisted).
 *
 * The PC targets packages; this maps process names back to packages where
 * derivable (a process named `com.foo` or `com.foo:xyz` belongs to `com.foo`).
 * Parsing ([parsePsLine]) is pure Kotlin so it is JVM-unit-testable without a
 * device; the Android glue ([list]) only shells out and filters.
 */
object ProcessInspector {

    const val MAX_LIMIT = 2000

    fun list(packageFilter: String?, limit: Int): JSONObject {
        if (!ShellRunner.canSu()) {
            return JSONObject()
                .put("processes", JSONArray())
                .put("total", 0)
                .put("truncated", false)
                .put("source", "none")
        }
        val output = ShellRunner.runSu(
            "ps -A -o USER,PID,STAT,NAME 2>/dev/null",
        ).getOrNull().orEmpty()
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val rows = output.lineSequence()
            .drop(1) // header
            .mapNotNull { parsePsLine(it) }
            .filter { packageFilter == null || it.packageName == packageFilter }
            .take(cap)
            .toList()
        // Count all matches (not just the bounded page) for the `total` field.
        val total = if (packageFilter == null) {
            output.lineSequence().drop(1).count { parsePsLine(it) != null }
        } else {
            rows.size
        }
        val entries = JSONArray()
        for (row in rows) {
            entries.put(JSONObject()
                .put("package", row.packageName ?: JSONObject.NULL)
                .put("pid", row.pid)
                .put("uid", row.uid ?: JSONObject.NULL)
                .put("user", row.user)
                .put("state", row.state)
                .put("name", row.name))
        }
        return JSONObject()
            .put("processes", entries)
            .put("total", total)
            .put("truncated", total > cap)
            .put("source", "ps")
    }

    internal data class PsRow(
        val user: String,
        val pid: Int,
        val state: String,
        val name: String,
        val packageName: String?,
        val uid: Int?,
    )

    /** Parse `USER PID STAT NAME` (toybox `ps -o USER,PID,STAT,NAME`). */
    internal fun parsePsLine(line: String): PsRow? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("USER")) return null
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 4) return null
        val user = parts[0]
        val pid = parts[1].toIntOrNull() ?: return null
        val state = parts[2]
        val name = parts.drop(3).joinToString(" ")
        return PsRow(
            user = user,
            pid = pid,
            state = state,
            name = name,
            packageName = packageOfProcessName(name),
            uid = uidOfUser(user),
        )
    }

    /**
     * Map the `USER` column to a numeric uid. Android toybox renders users as
     * `root`/`system`/`u0_a123` or bare numbers; the `uX_aY` form maps to
     * `X*100000 + 10000 + Y`. Unknown names yield null.
     */
    internal fun uidOfUser(user: String): Int? {
        user.toIntOrNull()?.let { return it }
        return when (user) {
            "root" -> 0
            "system" -> 1000
            "radio" -> 1001
            "shell" -> 2000
            "nobody" -> 9999
            else -> {
                val match = UID_USER.matchEntire(user) ?: return null
                val userId = match.groupValues[1].toIntOrNull() ?: return null
                val appId = match.groupValues[2].toIntOrNull() ?: return null
                userId * 100000 + 10000 + appId
            }
        }
    }

    private val UID_USER = Regex("u(\\d+)_a(\\d+)")

    /** `com.foo:ext` → `com.foo`; leaves `system_server`/kernel names alone. */
    internal fun packageOfProcessName(name: String): String? {
        if (!name.contains('.')) return null
        return name.substringBefore(':', name)
    }
}
