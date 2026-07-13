package com.modspec.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Reads LSPosed module scope from modules_config.db (requires root).
 */
object ScopeReader {
    private const val TAG = "ModspecScope"
    private const val AGENT_PACKAGE = "com.modspec.agent"
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedSnapshot: ModuleSnapshot? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    data class ModuleSnapshot(
        val enabled: Boolean? = null,
        val packages: Set<String> = emptySet(),
        val unavailableReason: String? = null,
    ) {
        val readable: Boolean get() = unavailableReason == null
    }

    fun readAgentModule(context: Context, forceRefresh: Boolean = false): ModuleSnapshot {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedSnapshot?.let { snap ->
                if (now - cachedAtMs < CACHE_TTL_MS) return snap
            }
        }
        return loadAgentModule(context).also {
            cachedSnapshot = it
            cachedAtMs = now
            Log.d(TAG, "scope=$it")
        }
    }

    fun queryAgentScope(context: Context): ModuleSnapshot = readAgentModule(context)

    fun isModuleEnabled(context: Context): Boolean? = readAgentModule(context).enabled

    fun hasFramework(packages: Set<String>): Boolean =
        packages.contains(RecommendedScope.FRAMEWORK) || packages.contains("android")

    private val DB_CANDIDATES = listOf(
        "/data/adb/lspd/config/modules_config.db",
        "/data/adb/lsposed/config/modules_config.db",
        "/data/misc/lsposed/config/modules_config.db",
    )

    private fun resolveDbPath(): String? = DB_CANDIDATES.firstOrNull { ShellRunner.fileExists(it) }

    private fun loadAgentModule(context: Context): ModuleSnapshot {
        val dbPath = resolveDbPath()
        if (dbPath == null) {
            Log.w(TAG, "no modules_config.db in ${DB_CANDIDATES.joinToString()}")
            return ModuleSnapshot(unavailableReason = "未找到 LSPosed 配置库")
        }

        readViaCli(dbPath)?.let { return it }
        readViaAndroidSqlite(context, dbPath)?.let { return it }

        Log.w(TAG, "read failed for $dbPath id=${ShellRunner.runSu("id").getOrNull()}")
        return ModuleSnapshot(unavailableReason = "无法读取作用域（需 root）")
    }

    private fun readViaAndroidSqlite(context: Context, dbPath: String): ModuleSnapshot? {
        val local = File(context.cacheDir, "lsposed_modules_config.db")
        local.delete()
        if (!copyDbTo(local, dbPath)) {
            Log.w(TAG, "copy db failed")
            return null
        }

        return runCatching {
            val db = SQLiteDatabase.openDatabase(local.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                parseSnapshot(db)
            } finally {
                db.close()
            }
        }.getOrNull().also { local.delete() }
    }

    private fun copyDbTo(dest: File, dbPath: String): Boolean {
        val destPath = dest.absolutePath
        val commands = listOf(
            "cp '$dbPath' '$destPath' && chmod 644 '$destPath'",
            "cat '$dbPath' > '$destPath'",
            "busybox cp '$dbPath' '$destPath'",
            "/data/adb/magisk/busybox cp '$dbPath' '$destPath'",
            "base64 '$dbPath' > '$destPath.b64'",
        )
        for (cmd in commands) {
            if (cmd.contains("base64")) {
                val b64File = File("$destPath.b64")
                if (ShellRunner.runSu(cmd).isFailure) continue
                val b64 = runCatching { b64File.readText() }.getOrNull()?.filter { !it.isWhitespace() }.orEmpty()
                b64File.delete()
                if (b64.isEmpty()) continue
                val decoded = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                }.getOrNull() ?: continue
                dest.writeBytes(decoded)
            } else if (ShellRunner.runSu(cmd).isFailure) {
                continue
            }
            if (dest.exists() && dest.length() > 512) return true
        }
        return false
    }

    private fun parseSnapshot(db: SQLiteDatabase): ModuleSnapshot {
        val enabled = db.rawQuery(
            "SELECT enabled FROM modules WHERE module_pkg_name = ? LIMIT 1",
            arrayOf(AGENT_PACKAGE),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getInt(0) == 1
        }
        val scope = db.rawQuery(
            """
            SELECT s.app_pkg_name FROM scope s
            INNER JOIN modules m ON s.mid = m.mid
            WHERE m.module_pkg_name = ?
            ORDER BY s.app_pkg_name
            """.trimIndent(),
            arrayOf(AGENT_PACKAGE),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        return ModuleSnapshot(enabled = enabled, packages = normalize(scope))
    }

    private fun readViaCli(dbPath: String): ModuleSnapshot? {
        val enabledSql = "SELECT enabled FROM modules WHERE module_pkg_name='$AGENT_PACKAGE' LIMIT 1;"
        val scopeSql = """
            SELECT s.app_pkg_name FROM scope s
            INNER JOIN modules m ON s.mid = m.mid
            WHERE m.module_pkg_name='$AGENT_PACKAGE'
            ORDER BY s.app_pkg_name;
        """.trimIndent().replace('\n', ' ')

        val enabledOut = execSql(dbPath, enabledSql) ?: return null
        val scopeOut = execSql(dbPath, scopeSql).orEmpty()
        val enabled = when (enabledOut.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val scope = scopeOut.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return ModuleSnapshot(enabled = enabled, packages = normalize(scope))
    }

    private fun execSql(dbPath: String, sql: String): String? {
        val escaped = sql.replace("\"", "\\\"")
        for (cmd in sqliteCommands(dbPath, escaped)) {
            val result = ShellRunner.runSu(cmd)
            if (result.isSuccess) return result.getOrNull()?.trim()
        }
        return null
    }

    private fun sqliteCommands(dbPath: String, sql: String): List<String> = listOf(
        "sqlite3 \"$dbPath\" \"$sql\"",
        "toybox sqlite3 \"$dbPath\" \"$sql\"",
        "/system/bin/sqlite3 \"$dbPath\" \"$sql\"",
        "/system/xbin/sqlite3 \"$dbPath\" \"$sql\"",
        "/data/adb/magisk/busybox sqlite3 \"$dbPath\" \"$sql\"",
        "busybox sqlite3 \"$dbPath\" \"$sql\"",
    )

    private fun normalize(packages: List<String>): Set<String> =
        packages.map { if (it == "android") RecommendedScope.FRAMEWORK else it }.toSet()
}
