package com.modspec.agent

import android.content.Context
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Agent-side script lifecycle: validate / deploy / list / enable / disable /
 * remove / reload with explicit mutation feedback and request-id idempotency.
 *
 * - Storage: `files/scripts/<safe-id>.zip` (local source of truth), published
 *   to libxposed RemoteFiles as `scripts/<safe-id>.zip` (hook processes load
 *   from there); root fallback `/data/local/tmp/modspec/scripts`.
 * - Active selection is first-class: exactly one script is `active_script`
 *   (enable is exclusive by default), persisted in state.json and remote
 *   preferences, so users can switch off old diagnostic scripts without an
 *   empty-profile workaround.
 * - Mutations carry a `request_id`; a repeated request replays the stored
 *   response instead of duplicating the side effect ("never retry blindly").
 * - Lifecycle state (hash / generation / last load / hit / error) is
 *   persisted by [ScriptStateStore], updated from hook-process events.
 */
class ScriptManager(
    private val context: Context,
    private val scopeEnsurer: (List<String>) -> Pair<String, String>,
    private val restarter: (List<String>) -> JSONObject,
) {

    private val scriptsDir: File = File(context.filesDir, "scripts").also { it.mkdirs() }

    private val dedupe = object : LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean =
            size > 64
    }

    // --- RPC surface ---------------------------------------------------------

    /** `script_validate`: full bundle validation without storing. */
    fun validate(manifestRaw: String, files: List<ScriptFile>): JSONObject {
        val parsed = runCatching { ScriptManifestParser.parse(manifestRaw) }
        if (parsed.isFailure) {
            return JSONObject()
                .put("ok", false)
                .put("errors", JSONArray(listOf(parsed.exceptionOrNull()?.message ?: "invalid manifest")))
        }
        val errors = ScriptBundleValidator.validate(parsed.getOrThrow(), manifestRaw, files)
        val result = JSONObject().put("ok", errors.isEmpty())
        result.put("errors", JSONArray(errors))
        return result
    }

    /** `script_deploy`: validate, store atomically, publish, optionally activate. */
    @Synchronized
    fun deploy(
        requestId: String,
        scriptId: String,
        manifestRaw: String,
        files: List<ScriptFile>,
        ensureScope: Boolean,
        activate: Boolean,
    ): JSONObject {
        dedupe[requestId]?.let { return it }

        val parsed = runCatching { ScriptManifestParser.parse(manifestRaw) }
            .getOrElse { throw IllegalArgumentException("invalid manifest: ${it.message}") }
        if (parsed.metaId != scriptId) {
            throw IllegalArgumentException("script_id does not match meta.id")
        }
        val errors = ScriptBundleValidator.validate(parsed, manifestRaw, files)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.first())
        }

        var scopeStatus = "not_requested"
        var scopeMessage = "script stored"
        if (ensureScope) {
            val scope = scopeEnsurer(parsed.packages)
            scopeStatus = scope.first
            scopeMessage = scope.second
            if (scopeStatus != "applied" && scopeStatus != "already") {
                throw ScriptManagerException(-32010, "scope not ensured: $scopeMessage")
            }
        }

        val destination = localZipFile(scriptId)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        destination.parentFile?.mkdirs()
        temporary.writeBytes(ScriptZip.encode(manifestRaw, files))
        val committed = runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!committed) {
            temporary.delete()
            throw IllegalStateException("failed to commit script bundle")
        }

        // Update the local state FIRST so publishGeneration's
        // activeScriptFromState() reads the new active script — otherwise the
        // remote prefs keep pointing at the previous active script.
        val contentHash = ScriptHash.of(manifestRaw, files)
        val state = ScriptStateStore.read(scriptsDir)
        val record = state.second.getOrPut(scriptId) { ScriptStateStore.ScriptRecord(scriptId) }
        record.hash = contentHash
        record.packages = parsed.packages
        record.targetPackages = parsed.targetPackages
        record.engine = parsed.runtime
        ScriptStateStore.write(scriptsDir, if (activate) scriptId else state.first, state.second)

        val published = publish(parsed, activate)
        record.generation = published.generation
        ScriptStateStore.write(scriptsDir, if (activate) scriptId else state.first, state.second)

        // Native companion: deploy the frida.js + gadget config when the
        // bundle declares the `frida` capability (gadget itself is installed
        // on demand by the PC; deploying the script is idempotent).
        if ("frida" in parsed.capabilities && parsed.fridaScript != null) {
            val fridaJs = files.firstOrNull { it.name == parsed.fridaScript }?.content
            if (fridaJs != null && activate) {
                FridaGadget.deployScript(context, scriptId, fridaJs)
            }
        }

        EventJournal.append(
            event = "script_uploaded",
            generation = published.generation,
            scriptId = scriptId,
            packageName = parsed.packages.firstOrNull(),
            message = "stored mode=${published.mode.name.lowercase()} scope=$scopeStatus",
        )

        return dedupe.getOrPut(requestId) {
            JSONObject()
                .put("script_id", scriptId)
                .put("stored", true)
                .put("publish_mode", published.mode.name.lowercase())
                .put("generation", published.generation)
                .put("engine", parsed.runtime)
                .put("content_hash", contentHash)
                .put("scope_status", scopeStatus)
                .put("scope_packages", JSONArray(parsed.packages))
                .put("message", scopeMessage)
        }
    }

    /** `script_list`: stored scripts plus persisted lifecycle state. */
    fun list(): JSONObject {
        val (active, records) = ScriptStateStore.read(scriptsDir)
        val scripts = JSONArray()
        for (record in records.values.sortedBy { it.scriptId }) {
            scripts.put(
                JSONObject()
                    .put("script_id", record.scriptId)
                    .put("name", record.scriptId.substringAfterLast('/'))
                    .put("engine", record.engine)
                    .putOpt("version", record.version)
                    .put("content_hash", record.hash)
                    .put("active", record.scriptId == active)
                    .putOpt("generation", record.generation)
                    .putOpt("last_loaded_ms", record.lastLoadedMs)
                    .putOpt("last_hit_ms", record.lastHitMs)
                    .putOpt("last_error", record.lastError)
                    .put("hit_count", record.hitCount)
                    .put("error_count", record.errorCount)
                    .put("packages", JSONArray(record.packages))
                    .put("target_packages", JSONArray(record.targetPackages)),
            )
        }
        return JSONObject()
            .put("scripts", scripts)
            .put("active_script", active ?: JSONObject.NULL)
    }

    /** `script_enable`: first-class active selection (exclusive by default). */
    @Synchronized
    fun enable(requestId: String, scriptId: String, exclusive: Boolean): JSONObject {
        dedupe[requestId]?.let { return it }
        val (active, records) = ScriptStateStore.read(scriptsDir)
        val record = records[scriptId] ?: throw ScriptManagerException(-32011, "script not stored: $scriptId")
        val disabled = mutableListOf<String>()
        if (exclusive && active != null && active != scriptId) {
            disabled += active
        }
        ScriptStateStore.write(scriptsDir, scriptId, records)
        val generation = publishGeneration()
        EventJournal.append(
            event = "script_enabled",
            generation = generation,
            scriptId = scriptId,
            packageName = record.packages.firstOrNull(),
            message = if (disabled.isEmpty()) "active=$scriptId" else "active=$scriptId disabled=${disabled.joinToString()}",
        )
        return dedupe.getOrPut(requestId) {
            JSONObject()
                .put("script_id", scriptId)
                .put("enabled", true)
                .put("disabled", JSONArray(disabled))
                .put("generation", generation)
        }
    }

    /** `script_disable`: deactivate without removing files. */
    @Synchronized
    fun disable(requestId: String, scriptId: String): JSONObject {
        dedupe[requestId]?.let { return it }
        val (active, records) = ScriptStateStore.read(scriptsDir)
        if (scriptId !in records) {
            throw ScriptManagerException(-32011, "script not stored: $scriptId")
        }
        val wasActive = active == scriptId
        ScriptStateStore.write(scriptsDir, if (wasActive) null else active, records)
        val generation = publishGeneration()
        EventJournal.append(
            event = "script_disabled",
            generation = generation,
            scriptId = scriptId,
            packageName = records[scriptId]?.packages?.firstOrNull(),
            message = if (wasActive) "deactivated=$scriptId" else "already inactive: $scriptId",
        )
        return dedupe.getOrPut(requestId) {
            JSONObject()
                .put("script_id", scriptId)
                .put("disabled", wasActive)
                .put("generation", generation)
        }
    }

    /** `script_remove`: delete files and state. */
    @Synchronized
    fun remove(requestId: String, scriptId: String): JSONObject {
        dedupe[requestId]?.let { return it }
        val (_, records) = ScriptStateStore.read(scriptsDir)
        if (scriptId !in records) {
            throw ScriptManagerException(-32011, "script not stored: $scriptId")
        }
        val removed = ScriptStateStore.removeRecord(scriptsDir, scriptId)
        localZipFile(scriptId).delete()
        val remoteName = remoteFileName(scriptId)
        if (remoteName != null) {
            runCatching {
                requireService().deleteRemoteFile(remoteName)
            }
            runCatching { File(ScriptManager.LEGACY_SCRIPTS_DIR, remoteName.substringAfterLast('/')).delete() }
        }
        val generation = publishGeneration()
        EventJournal.append(
            event = "script_disabled",
            generation = generation,
            scriptId = scriptId,
            message = "removed=$scriptId",
        )
        return dedupe.getOrPut(requestId) {
            JSONObject()
                .put("script_id", scriptId)
                .put("removed", removed)
                .put("generation", generation)
        }
    }

    /** `script_reload`: re-publish the stored bundle; optionally restart hook processes. */
    @Synchronized
    fun reload(requestId: String, scriptId: String, restart: Boolean): JSONObject {
        dedupe[requestId]?.let { return it }
        val (_, records) = ScriptStateStore.read(scriptsDir)
        val record = records[scriptId] ?: throw ScriptManagerException(-32011, "script not stored: $scriptId")
        val destination = localZipFile(scriptId)
        if (!destination.isFile) throw ScriptManagerException(-32012, "script bundle missing on disk: $scriptId")
        val parsed = runCatching {
            val (manifestRaw, files) = ScriptZip.decode(destination.readBytes())
            val manifest = ScriptManifestParser.parse(manifestRaw)
            val errors = ScriptBundleValidator.validate(manifest, manifestRaw, files)
            if (errors.isNotEmpty()) throw IllegalArgumentException(errors.first())
            manifest to publish(
                manifest,
                active = ScriptStateStore.activeScript(scriptsDir) == scriptId,
            )
        }.getOrElse { throw ScriptManagerException(-32013, "stored bundle invalid: ${it.message}") }
        val published = parsed.second

        EventJournal.append(
            event = "script_reload_started",
            generation = published.generation,
            scriptId = scriptId,
            packageName = record.packages.firstOrNull(),
            message = "re-published script $scriptId",
        )

        val response = JSONObject()
            .put("script_id", scriptId)
            .put("reload_started", true)
            .put("generation", published.generation)
        if (restart) {
            val restartResult = restarter(record.packages)
            response.put("restarted", restartResult.optJSONArray("restarted") ?: JSONArray())
            response.put("needs_trigger", restartResult.optJSONArray("needs_trigger") ?: JSONArray())
            response.put("not_installed", restartResult.optJSONArray("not_installed") ?: JSONArray())
            response.put("launch_failed", restartResult.optJSONArray("launch_failed") ?: JSONArray())
            response.put("failed", restartResult.optJSONObject("failed") ?: JSONObject())
        }
        return dedupe.getOrPut(requestId) { response }
    }

    // --- publishing ----------------------------------------------------------

    data class PublishResult(val mode: PublishMode, val generation: Long)

    enum class PublishMode { REMOTE_FILE, LEGACY_TMP }

    private fun publish(manifest: ScriptManifest, active: Boolean): PublishResult {
        val now = System.currentTimeMillis()
        val previous = if (ModspecApp.xposedService != null) {
            runCatching {
                RemotePrefsManager.getGroup().getLong(KEY_SCRIPTS_GENERATION, 0L)
            }.getOrDefault(0L)
        } else 0L
        val generation = maxOf(now, previous + 1)
        return if (ModspecApp.xposedService != null) {
            syncRemote(active)
            publishGeneration(generation, active)
            PublishResult(PublishMode.REMOTE_FILE, generation)
        } else {
            syncLegacy()
            runCatching { publishGeneration(generation, active) }
            PublishResult(PublishMode.LEGACY_TMP, generation)
        }
    }

    private fun syncRemote(active: Boolean) {
        val service = requireService()
        val keepNames = mutableSetOf<String>()
        scriptsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.forEach { file ->
                // Flat top-level name (libxposed RemoteFile rejects names
                // containing '/'), matching remoteFileName()/ScriptEngine.
                keepNames += file.name
                service.openRemoteFile(file.name).use { pfd ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        file.inputStream().copyTo(out)
                    }
                }
            }
        service.listRemoteFiles()
            .filter { it.endsWith(SUFFIX) && it !in keepNames }
            .forEach { service.deleteRemoteFile(it) }
        if (active) {
            RemotePrefsManager.set(KEY_ACTIVE_SCRIPT, activeScriptFromState())
        }
    }

    private fun syncLegacy() {
        if (!ShellRunner.canSu()) return
        val shared = File(LEGACY_SCRIPTS_DIR)
        val cmd = buildString {
            append("mkdir -p '${shared.absolutePath}' && ")
            append("cp -f '${scriptsDir.absolutePath}'/*.zip '${shared.absolutePath}/' 2>/dev/null; ")
            append("chmod -R a+rX '${shared.absolutePath}'")
        }
        ShellRunner.runSu(cmd).getOrNull()
    }

    private fun activeScriptFromState(): String? = ScriptStateStore.activeScript(scriptsDir)

    private fun publishGeneration(generation: Long = System.currentTimeMillis(), active: Boolean = true): Long {
        RemotePrefsManager.set(KEY_SCRIPTS_GENERATION, generation)
        if (active) {
            RemotePrefsManager.set(KEY_ACTIVE_SCRIPT, activeScriptFromState())
        }
        return generation
    }

    private fun localZipFile(scriptId: String): File {
        val safe = safeScriptId(scriptId) ?: throw IllegalArgumentException("invalid script id: $scriptId")
        return File(scriptsDir, "$safe$SUFFIX")
    }

    private fun requireService(): XposedService =
        ModspecApp.xposedService ?: error("XposedService not bound")

    companion object {
        const val KEY_SCRIPTS_GENERATION = "scripts_generation"
        const val KEY_ACTIVE_SCRIPT = "active_script"
        const val SUFFIX = ".zip"
        const val LEGACY_SCRIPTS_DIR = "/data/local/tmp/modspec/scripts"

        /**
         * Flat RemoteFile name for a script bundle, mirroring rules
         * (`<safe-id>.zip`): libxposed RemoteFiles reject names containing `/`,
         * and `%2F` keeps the id safely inside a single segment.
         */
        fun remoteFileName(scriptId: String): String? {
            val safe = safeScriptId(scriptId) ?: return null
            return "$safe$SUFFIX"
        }

        /**
         * Flatten a script id into a single-segment file name, mirroring
         * [AgentStorage.safeRuleName] (`/` → `%2F`): the libxposed RemoteFile
         * store and the local `files/scripts/` dir are flat, so a nested id
         * like `xiaomi/security-center/macro-gate` must not become a multi-
         * level path (which fails to open when the parent dirs do not exist).
         */
        fun safeScriptId(scriptId: String): String? {
            if (scriptId.isBlank() || scriptId == "." || scriptId == "..") return null
            if (scriptId.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
            if (!scriptId.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == '/' }) {
                return null
            }
            return scriptId.replace("%", "%25").replace("/", "%2F")
        }
    }
}

/** RPC-level error carrying a JSON-RPC code. */
class ScriptManagerException(val code: Int, message: String) : Exception(message)

/** Track script lifecycle events ingested from hook processes into state.json. */
object ScriptStateTracker {
    @Volatile
    private var appContext: Context? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    internal fun context(): Context =
        appContext ?: throw IllegalStateException("ScriptStateTracker not bound")

    fun onEvent(context: Context, scriptId: String, event: String, message: String) {
        val dir = File(context.filesDir, "scripts")
        ScriptStateStore.recordEvent(dir, scriptId, event, message)
    }
}
