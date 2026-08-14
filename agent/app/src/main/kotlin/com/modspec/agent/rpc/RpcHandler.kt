package com.modspec.agent.rpc

import android.content.Context
import com.modspec.agent.AppInspector
import com.modspec.agent.AppProfileApplier
import com.modspec.agent.EnvironmentChecker
import com.modspec.agent.AgentStorage
import com.modspec.agent.DexKitLib
import com.modspec.agent.FridaGadget
import com.modspec.agent.NativeHookLib
import com.modspec.agent.EventJournal
import com.modspec.agent.EventTailer
import com.modspec.agent.DeviceInspector
import com.modspec.agent.LogQuery
import com.modspec.agent.LogTailReader
import com.modspec.agent.LsposedCli
import com.modspec.agent.ModuleReloader
import com.modspec.agent.ModspecModule
import com.modspec.agent.PairingStore
import com.modspec.agent.ModspecApp
import com.modspec.agent.ProcessInspector
import com.modspec.agent.ScriptFile
import com.modspec.agent.ScriptManager
import com.modspec.agent.ScriptManagerException
import com.modspec.agent.ScriptStateTracker
import com.modspec.agent.ShellRunner
import com.modspec.agent.VerifyEvaluator
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 2.0 handler for modspec-cli ↔ agent transport.
 */
class RpcHandler(context: Context) {

    private val appContext = context.applicationContext
    private val profileApplier = AppProfileApplier(appContext)
    private val scriptManager = ScriptManager(
        appContext,
        scopeEnsurer = { packages -> ensureScope(packages) },
        restarter = { packages -> restartTargetsForScript(packages) },
    )

    @Volatile
    private var serverSupervisor: ServerSupervisor? = null

    init {
        // Initialize before deploy/restart can append orchestration events.
        // EventTailer.ensureStarted() binds the same path idempotently later.
        EventJournal.init(File(appContext.filesDir, "events"))
        ScriptStateTracker.bind(appContext)
        // Share the DexKit native library with hooked processes (root exec dir).
        // Runs off the init path: the su probe can block for tens of seconds.
        Thread({ DexKitLib.ensureShared(appContext) }, "modspec-dexkit-lib").start()
    }

    /** Bind the server supervisor so status/diagnostics can report server health. */
    fun attachServerSupervisor(supervisor: ServerSupervisor) {
        serverSupervisor = supervisor
    }

    fun isAuthorized(token: String?): Boolean = PairingStore.validateAuthToken(appContext, token)

    fun handleRequest(body: String): String {
        val request = JSONObject(body)
        val id = request.optString("id", UUID.randomUUID().toString())
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        return try {
            val result = when (method) {
                METHOD_PING -> JSONObject().put("pong", true)
                METHOD_GET_STATUS -> getStatus()
                METHOD_INSPECT_DEVICE -> inspectDevice(params)
                METHOD_APP_LIST -> appList(params)
                METHOD_APP_INFO -> appInfo(params)
                METHOD_PROCESS_LIST -> processList(params)
                METHOD_TRIGGER_APP -> triggerApp(params)
                METHOD_GET_LOGS -> getLogs(params)
                METHOD_MODULE_DIAGNOSTICS -> moduleDiagnostics()
                METHOD_APPLY_PROFILE -> applyProfile(params)
                METHOD_TOGGLE_MOD -> toggleMod(params)
                METHOD_VERIFY -> verify(params)
                METHOD_REAPPLY -> reapply(params)
                METHOD_COLLECT_LOGS -> collectLogs(params)
                METHOD_DEPLOY_RULE -> deployRule(params)
                METHOD_RESTART_TARGETS -> restartTargets(params)
                METHOD_SOFT_RESTART -> softRestart(params)
                METHOD_SCRIPT_VALIDATE -> scriptValidate(params)
                METHOD_SCRIPT_DEPLOY -> scriptDeploy(params)
                METHOD_SCRIPT_LIST -> scriptManager.list()
                METHOD_SCRIPT_ENABLE -> scriptEnable(params)
                METHOD_SCRIPT_DISABLE -> scriptDisable(params)
                METHOD_SCRIPT_REMOVE -> scriptRemove(params)
                METHOD_SCRIPT_RELOAD -> scriptReload(params)
                METHOD_INSTALL_FRIDA_GADGET -> installFridaGadget(params)
                else -> throw RpcException(-32601, "Method not found: $method")
            }
            jsonRpcResult(id, result)
        } catch (error: RpcException) {
            jsonRpcError(id, error.code, error.message)
        } catch (error: ScriptManagerException) {
            jsonRpcError(id, error.code, error.message ?: "script error")
        } catch (error: Exception) {
            jsonRpcError(id, -32603, error.message ?: "Internal error")
        }
    }

    fun handlePair(body: String): String {
        val request = JSONObject(body)
        val code = request.optString("code")
        if (!PairingStore.validateCode(appContext, code)) {
            return JSONObject()
                .put("error", "invalid pairing code")
                .toString()
        }
        return JSONObject()
            .put("request_id", UUID.randomUUID().toString())
            .put("device_id", PairingStore.getOrCreateDeviceId(appContext))
            .put("device_name", PairingStore.getDeviceName(appContext))
            .put("model", android.os.Build.MODEL)
            .put("auth_token", PairingStore.rotateAuthToken(appContext))
            .put("android_version", android.os.Build.VERSION.SDK_INT)
            .toString()
    }

    fun reapplyFromIntent(onlyFailed: Boolean): String {
        val jobId = profileApplier.reapply(onlyFailed)
        return JSONObject().put("job_id", jobId).toString()
    }

    private fun getStatus(): JSONObject {
        val state = AgentStorage.readState(appContext)
        val env = EnvironmentChecker.run(appContext)
        return JSONObject()
            .put("device_id", PairingStore.getOrCreateDeviceId(appContext))
            .put("model", android.os.Build.MODEL)
            .put("android_version", android.os.Build.VERSION.SDK_INT)
            .put("agent_version", DeviceInspector.agentVersion(appContext))
            .put("lsposed_version", ModspecApp.xposedService?.frameworkName)
            .put("root_available", ShellRunner.canSu())
            .put("xposed_service_bound", ModspecApp.xposedService != null)
            .put("environment", env.toJsonArray())
            .put("state", state)
            .put("server_http_alive", serverSupervisor?.isAlive("http") ?: false)
            .put("server_ws_alive", serverSupervisor?.isAlive("ws") ?: false)
    }

    private fun inspectDevice(params: JSONObject): JSONObject = DeviceInspector.inspect(
        appContext,
        includeApps = params.optBoolean("include_apps", false),
        appLimit = params.optInt("app_limit", 200),
    )

    private fun appList(params: JSONObject): JSONObject {
        val scope = params.optString("scope", "all")
        if (scope !in setOf("all", "system", "user")) {
            throw RpcException(-32602, "invalid scope: $scope (expected all|system|user)")
        }
        return AppInspector.list(
            appContext,
            scope = scope,
            limit = params.optInt("limit", 200),
            filter = params.optString("filter").takeIf { it.isNotBlank() },
        )
    }

    private fun appInfo(params: JSONObject): JSONObject {
        val packageName = params.getString("package")
        requireSafePackage(packageName)
        return AppInspector.info(appContext, packageName)
    }

    private fun processList(params: JSONObject): JSONObject {
        val packageFilter = params.optString("package").takeIf { it.isNotBlank() }
        if (packageFilter != null) requireSafePackage(packageFilter)
        return ProcessInspector.list(packageFilter, params.optInt("limit", 200))
    }

    /**
     * Explicit app launch. Default is the launcher activity only; an explicit
     * `package/.Class` component may be supplied by the user. The Agent never
     * guesses and starts arbitrary exported components silently, and never
     * touches `system`/`android`/`system_server`.
     */
    @Synchronized
    private fun triggerApp(params: JSONObject): JSONObject {
        val packageName = params.getString("package")
        if (packageName == "system" || packageName == "android" || packageName == "system_server") {
            throw RpcException(-32602, "refusing to trigger system target: $packageName")
        }
        requireSafePackage(packageName)
        // Tolerate legacy PC clients that serialize an omitted component as
        // `"component": null` (JSONObject.optString turns that into "null").
        val component = params.optString("component")
            .takeIf { it.isNotBlank() && it != "null" }
        if (component != null) {
            validateComponent(packageName, component)
            val quotedComponent = ShellRunner.shellQuote(component)
            val output = ShellRunner.runSu("am start -n $quotedComponent 2>&1")
                .getOrElse { it.message.orEmpty() }
            return if (output.contains("Error") || output.contains("Exception")) {
                JSONObject()
                    .put("package", packageName)
                    .put("launched", false)
                    .put("method", "component")
                    .put("needs_trigger", false)
                    .put("message", output.trim().take(300))
            } else {
                JSONObject()
                    .put("package", packageName)
                    .put("launched", true)
                    .put("method", "component")
                    .put("needs_trigger", false)
                    .put("message", "started $component")
            }
        }

        val quoted = ShellRunner.shellQuote(packageName)
        val launchOutput = ShellRunner.runSu(
            "monkey -p $quoted -c android.intent.category.LAUNCHER 1 2>&1",
        ).getOrElse { it.message.orEmpty() }
        return when {
            launchOutput.contains("Events injected: 1") -> JSONObject()
                .put("package", packageName)
                .put("launched", true)
                .put("method", "launcher")
                .put("needs_trigger", false)
                .put("message", "launcher activity started")
            launchOutput.contains("No activities found", ignoreCase = true) ||
                launchOutput.contains("no activities", ignoreCase = true) -> JSONObject()
                .put("package", packageName)
                .put("launched", false)
                .put("method", "none")
                .put("needs_trigger", true)
                .put("message", "no launcher activity; supply --component explicitly")
            else -> JSONObject()
                .put("package", packageName)
                .put("launched", false)
                .put("method", "none")
                .put("needs_trigger", false)
                .put("message", "launcher command failed: ${launchOutput.trim().take(300)}")
        }
    }

    private fun validateComponent(packageName: String, component: String) {
        val parts = component.split('/', limit = 2)
        val componentPackage = if (parts.size == 2) parts[0] else ""
        val componentClass = if (parts.size == 2) parts[1] else ""
        if (componentPackage != packageName) {
            throw RpcException(-32602, "component $component does not belong to $packageName")
        }
        if (!COMPONENT_CLASS.matches(componentClass)) {
            throw RpcException(-32602, "invalid component class: $component")
        }
    }

    private fun getLogs(params: JSONObject): JSONObject {
        val packageFilter = params.optString("package").takeIf { it.isNotBlank() }
        if (packageFilter != null) requireSafePackage(packageFilter)
        val tag = params.optString("tag").takeIf { it.isNotBlank() }
        val sinceMs = if (params.has("since_ms") && !params.isNull("since_ms")) {
            params.getLong("since_ms")
        } else null
        return LogQuery.fetch(
            packageFilter = packageFilter,
            tag = tag,
            limit = params.optInt("limit", 200),
            sinceMs = sinceMs,
        )
    }

    private fun moduleDiagnostics(): JSONObject {
        EventTailer.ensureStarted(appContext)
        val state = AgentStorage.readState(appContext)
        val scope = runCatching { ModspecApp.xposedService?.scope?.toList() }
            .getOrDefault(emptyList())
        val generation = runCatching {
            if (ModspecApp.xposedService != null) {
                com.modspec.agent.RemotePrefsManager.getGroup()
                    .getLong(ModuleReloader.KEY_RULES_GENERATION, 0L)
            } else 0L
        }.getOrDefault(0L)
        val serverHealth = serverSupervisor?.snapshot().orEmpty()
        val healthByName = serverHealth.associateBy { it.name }
        return JSONObject()
            .put("lsposed_framework", ModspecApp.xposedService?.frameworkName ?: JSONObject.NULL)
            .put("xposed_service_bound", ModspecApp.xposedService != null)
            .put("scope", JSONArray(scope.orEmpty().sorted()))
            .put("active_rules", JSONArray(AgentStorage.activeRuleIds(state)))
            .put("rules_generation", generation.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("lsposed_cli_available", LsposedCli.isAvailable())
            .put("root_available", ShellRunner.canSu())
            .put("event_source", EventJournal.sourceLabel())
            .put("tailer_running", EventTailer.isRunning())
            .put("server_http_alive", healthByName["http"]?.alive ?: false)
            .put("server_ws_alive", healthByName["ws"]?.alive ?: false)
            .put("server_http_restarts", healthByName["http"]?.restarts ?: 0)
            .put("server_ws_restarts", healthByName["ws"]?.restarts ?: 0)
            .put("server_last_error", serverHealth.firstNotNullOfOrNull { it.lastError } ?: JSONObject.NULL)
    }

    private fun applyProfile(params: JSONObject): JSONObject {
        val profile = params.getJSONObject("profile")
        val dryRun = params.optBoolean("dry_run", false)
        val jobId = profileApplier.applyFromJson(profile, dryRun)
        return JSONObject().put("job_id", jobId)
    }

    private fun reapply(params: JSONObject): JSONObject {
        val onlyFailed = params.optBoolean("only_failed", false)
        val jobId = profileApplier.reapply(onlyFailed)
        return JSONObject().put("job_id", jobId)
    }

    private fun toggleMod(params: JSONObject): JSONObject {
        val modId = params.getString("mod_id")
        val enabled = params.getBoolean("enabled")
        val state = AgentStorage.readState(appContext)
        val items = state.optJSONObject("items") ?: JSONObject()
        val item = items.optJSONObject(modId) ?: JSONObject()
        item.put("enabled", enabled)
        items.put(modId, item)
        state.put("items", items)
        AgentStorage.writeState(appContext, state)
        return JSONObject().put("ok", true)
    }

    private fun verify(params: JSONObject): JSONObject {
        val state = AgentStorage.readState(appContext)
        val expectedProfileId = params.optString("profile_id").takeIf { it.isNotBlank() }
        val activeProfileId = state.optString("active_profile").takeIf { it.isNotBlank() }
        val profile = readProfile(expectedProfileId ?: activeProfileId)
        val drift = VerifyEvaluator.evaluate(
            state = VerifyEvaluator.extractState(state),
            mods = profile?.let(VerifyEvaluator::extractMods).orEmpty(),
            expectedProfileId = expectedProfileId,
            lsposedAvailable = LsposedCli.isAvailable(),
        )
        val driftJson = JSONArray()
        drift.forEach { entry ->
            driftJson.put(
                JSONObject()
                    .put("mod_id", entry.modId)
                    .put("kind", entry.kind)
                    .put("expected", entry.expected)
                    .put("actual", entry.actual)
                    .put("reason", entry.reason),
            )
        }
        return JSONObject().put("drift", driftJson)
    }

    private fun readProfile(profileId: String?): JSONObject? {
        if (profileId == null) return null
        val profileFile = AgentStorage.profilesDir(appContext).resolve("$profileId.json")
        if (!profileFile.exists()) return null
        return runCatching { JSONObject(profileFile.readText()) }.getOrNull()
    }

    private fun collectLogs(params: JSONObject): JSONObject {
        EventTailer.ensureStarted(appContext)
        // Compatibility/legacy path: if the ring has no history yet, backfill a
        // one-shot `logcat -d` so hook-process events reach the journal even
        // when the agent process was cold. Deduped by content signature.
        if (EventJournal.isEmpty()) {
            runCatching { LogTailReader.ingestFromLogcatDump() }
        }
        val afterEventId = if (params.has("after_event_id") && !params.isNull("after_event_id")) {
            params.getLong("after_event_id")
        } else null
        val sinceMs = if (params.has("since_ms") && !params.isNull("since_ms")) {
            params.getLong("since_ms")
        } else null
        val limit = params.optInt("limit", 200)
        val ruleId = params.optString("rule_id").takeIf { it.isNotBlank() }
        val scriptId = params.optString("script_id").takeIf { it.isNotBlank() }
        val minGeneration = if (params.has("min_generation") && !params.isNull("min_generation")) {
            params.getLong("min_generation")
        } else null
        val exactGeneration = if (params.has("exact_generation") && !params.isNull("exact_generation")) {
            params.getLong("exact_generation")
        } else null

        val collected = EventJournal.collect(
            afterEventId = afterEventId,
            limit = limit,
            ruleId = ruleId,
            scriptId = scriptId,
            minGeneration = minGeneration,
            exactGeneration = exactGeneration,
        )
        val entries = JSONArray()
        for (event in collected.entries) {
            // Deprecated millisecond filter (legacy PC clients); after_event_id
            // remains the reliable cursor and is checked first.
            if (sinceMs != null && event.timestampMs < sinceMs) continue
            entries.put(event.toJson())
        }
        return JSONObject()
            .put("entries", entries)
            .put("next_event_id", collected.nextEventId)
            .put("first_event_id", collected.firstEventId ?: JSONObject.NULL)
            .put("truncated", collected.truncated)
            .put("source", EventJournal.sourceLabel())
    }

    @Synchronized
    private fun deployRule(params: JSONObject): JSONObject {
        val ruleId = params.getString("rule_id")
        val content = params.getString("content")
        if (!RULE_ID.matches(ruleId)) throw RpcException(-32602, "invalid rule id")
        if (content.toByteArray().size > MAX_RULE_BYTES) {
            throw RpcException(-32602, "rule exceeds $MAX_RULE_BYTES bytes")
        }
        val requestedPackages = params.optJSONArray("packages")?.toStringList().orEmpty()
        val parsed = com.modspec.agent.RuleParser.parse(content)
        if (parsed.metaId != ruleId) {
            throw RpcException(-32602, "rule_id does not match meta.id")
        }
        if (parsed.packages.toSet() != requestedPackages.toSet()) {
            throw RpcException(-32602, "packages do not match rule compatible.packages")
        }
        requestedPackages.forEach(::requireSafePackage)

        var scopeStatus = "not_requested"
        var scopeMessage = "rule stored"
        if (params.optBoolean("ensure_scope", true)) {
            val scope = ensureScope(requestedPackages)
            scopeStatus = scope.first
            scopeMessage = scope.second
            if (scopeStatus != "applied" && scopeStatus != "already") {
                throw RpcException(-32010, "scope not ensured: $scopeMessage")
            }
        }

        val destination = AgentStorage.ruleFile(appContext, ruleId)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(content)
        val committed = runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.isSuccess
        if (!committed) {
            temporary.delete()
            throw IllegalStateException("failed to commit rule file")
        }

        val state = AgentStorage.readState(appContext)
        val active = AgentStorage.activeRuleIds(state).toMutableSet().apply { add(ruleId) }
        state.put("active_rules", JSONArray(active.toList()))
        AgentStorage.writeState(appContext, state)
        val published = com.modspec.agent.RemoteRulesManager.publishRules(appContext, active)

        EventJournal.append(
            event = "rule_uploaded",
            generation = published.generation,
            ruleId = ruleId,
            packageName = requestedPackages.firstOrNull(),
            message = "stored mode=${published.mode.name.lowercase()} scope=$scopeStatus",
        )

        return JSONObject()
            .put("rule_id", ruleId)
            .put("stored", true)
            .put("publish_mode", published.mode.name.lowercase())
            .put("generation", published.generation)
            .put("scope_status", scopeStatus)
            .put("scope_packages", JSONArray(requestedPackages))
            .put("message", scopeMessage)
    }

    /**
     * Restart target apps with diagnostics:
     *  - validate the package is installed BEFORE force-stop (never touch an
     *    unknown/absent package) → `not_installed`
     *  - force-stop success + launcher started → `restarted`
     *  - force-stop success + no launcher activity → `needs_trigger`
     *  - force-stop success + launcher command errored → `launch_failed`
     *  - force-stop itself failed → `failed` (per-package message)
     * `system`/`android` are never force-stopped.
     */
    @Synchronized
    private fun restartTargets(params: JSONObject): JSONObject {
        val packages = params.getJSONArray("packages").toStringList()
        val restarted = JSONArray()
        val needsTrigger = JSONArray()
        val notInstalled = JSONArray()
        val launchFailed = JSONArray()
        val failed = JSONObject()
        for (packageName in packages) {
            requireSafePackage(packageName)
            if (packageName == "system" || packageName == "android") {
                failed.put(packageName, "system-server cannot be force-stopped by restart_targets")
                continue
            }
            val quoted = ShellRunner.shellQuote(packageName)
            if (!isPackageInstalled(packageName)) {
                notInstalled.put(packageName)
                EventJournal.append(
                    event = "target_not_installed",
                    packageName = packageName,
                    message = "package $packageName is not installed; deploy/restart skipped",
                )
                continue
            }
            val stopped = ShellRunner.runSu("am force-stop $quoted")
            if (stopped.isFailure) {
                val message = stopped.exceptionOrNull()?.message ?: "force-stop failed"
                failed.put(packageName, message)
                EventJournal.append(
                    event = "restart_failed",
                    packageName = packageName,
                    message = "force-stop failed: $message",
                )
                continue
            }
            val launchResult = ShellRunner.runSu(
                "monkey -p $quoted -c android.intent.category.LAUNCHER 1 2>&1",
            )
            val launchOutput = launchResult.getOrElse { error -> error.message.orEmpty() }
            when {
                launchOutput.contains("Events injected: 1") -> {
                    restarted.put(packageName)
                    EventJournal.append(
                        event = "target_restarted",
                        packageName = packageName,
                        message = "force-stopped and relaunched",
                    )
                }
                launchOutput.contains("No activities found", ignoreCase = true) ||
                    launchOutput.contains("no activities", ignoreCase = true) -> {
                    needsTrigger.put(packageName)
                    EventJournal.append(
                        event = "target_stopped",
                        packageName = packageName,
                        message = "force-stopped; no launcher activity, trigger manually",
                    )
                }
                else -> {
                    launchFailed.put(packageName)
                    EventJournal.append(
                        event = "launch_failed",
                        packageName = packageName,
                        message = "force-stopped but launcher command failed: ${launchOutput.trim().take(200)}",
                    )
                }
            }
        }
        return JSONObject()
            .put("restarted", restarted)
            .put("needs_trigger", needsTrigger)
            .put("not_installed", notInstalled)
            .put("launch_failed", launchFailed)
            .put("failed", failed)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        val output = ShellRunner.runSu("pm path ${ShellRunner.shellQuote(packageName)} 2>/dev/null")
            .getOrNull()
            .orEmpty()
        return output.contains("package:")
    }

    private fun requireSafePackage(packageName: String) {
        if (packageName != "system" && packageName != "android" && !PACKAGE_NAME.matches(packageName)) {
            throw RpcException(-32602, "invalid package name: $packageName")
        }
    }

    private fun ensureScope(packages: List<String>): Pair<String, String> {
        var serviceFailure: String? = null
        val service = ModspecApp.xposedService
        if (service != null) {
            val existing = runCatching { service.scope.toSet() }.getOrDefault(emptySet())
            val missing = packages.filterNot(existing::contains)
            if (missing.isEmpty()) return "already" to "scope already contains all target packages"

            val latch = CountDownLatch(1)
            runCatching {
                service.requestScope(missing, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        if (!approved.containsAll(missing)) {
                            serviceFailure = "scope request approved only: ${approved.joinToString()}"
                        }
                        latch.countDown()
                    }

                    override fun onScopeRequestFailed(message: String) {
                        serviceFailure = message
                        latch.countDown()
                    }
                })
            }.onFailure {
                serviceFailure = it.message ?: "scope request failed"
                latch.countDown()
            }
            if (!latch.await(20, TimeUnit.SECONDS)) {
                return "failed" to "scope request timed out; approve it on the device"
            }
            if (serviceFailure == null) return "applied" to "scope approved and rule generation published"
            return "manual" to "scope was not fully approved through XposedService: $serviceFailure"
        }

        // The root CLI is only a compatibility path when the formal service is unavailable.
        // Never use it to override a user's explicit service rejection/partial approval.
        if (!LsposedCli.isAvailable()) {
            return "manual" to (serviceFailure ?: "XposedService/LSPosed CLI unavailable; verify scope manually")
        }
        val result = LsposedCli.setScope(
            ModspecModule.AGENT_PACKAGE,
            packages,
            LsposedCli.ScopeMode.APPEND,
        )
        return if (result.isSuccess) {
            "applied" to "scope appended through LSPosed CLI"
        } else {
            "failed" to (result.exceptionOrNull()?.message ?: serviceFailure ?: "scope update failed")
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, null) }

    @Synchronized
    private fun softRestart(params: JSONObject): JSONObject {
        val rulesOnly = params.optBoolean("rules_only", false)
        val result = if (rulesOnly) {
            ModuleReloader.reloadRules(appContext)
        } else {
            ModuleReloader.softRestart(appContext)
        }
        return JSONObject()
            .put("hot_reload_ok", result.hotReloadOk)
            .put("hot_reload_failed", result.hotReloadFailed)
            .put("hot_reload_unsupported", result.hotReloadUnsupported)
            .put("running_targets", result.runningTargets)
            .put("restarted_packages", org.json.JSONArray(result.restartedPackages))
            .put("message", result.message)
    }

    private fun jsonRpcResult(id: String, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()

    // --- script engine RPCs ---------------------------------------------------

    private fun scriptValidate(params: JSONObject): JSONObject {
        val manifest = params.optString("manifest").takeIf { it.isNotBlank() }
            ?: throw RpcException(-32602, "missing manifest")
        return scriptManager.validate(manifest, scriptFilesFrom(params))
    }

    @Synchronized
    private fun scriptDeploy(params: JSONObject): JSONObject {
        val requestId = params.optString("request_id")
            .takeIf { it.isNotBlank() }
            ?: throw RpcException(-32602, "missing request_id")
        val scriptId = params.getString("script_id")
        val manifest = params.optString("manifest").takeIf { it.isNotBlank() }
            ?: throw RpcException(-32602, "missing manifest")
        val files = scriptFilesFrom(params)
        return scriptManager.deploy(
            requestId = requestId,
            scriptId = scriptId,
            manifestRaw = manifest,
            files = files,
            ensureScope = params.optBoolean("ensure_scope", true),
            activate = params.optBoolean("activate", false),
        )
    }

    private fun scriptEnable(params: JSONObject): JSONObject {
        val requestId = requireRequestId(params)
        val scriptId = params.getString("script_id")
        return scriptManager.enable(
            requestId = requestId,
            scriptId = scriptId,
            exclusive = params.optBoolean("exclusive", true),
        )
    }

    private fun scriptDisable(params: JSONObject): JSONObject {
        val requestId = requireRequestId(params)
        return scriptManager.disable(requestId, params.getString("script_id"))
    }

    private fun scriptRemove(params: JSONObject): JSONObject {
        val requestId = requireRequestId(params)
        return scriptManager.remove(requestId, params.getString("script_id"))
    }

    private fun scriptReload(params: JSONObject): JSONObject {
        val requestId = requireRequestId(params)
        return scriptManager.reload(
            requestId = requestId,
            scriptId = params.getString("script_id"),
            restart = params.optBoolean("restart", false),
        )
    }

    private fun requireRequestId(params: JSONObject): String =
        params.optString("request_id").takeIf { it.isNotBlank() }
            ?: throw RpcException(-32602, "missing request_id")

    /** PC-side on-demand gadget delivery: install from the staged location. */
    private fun installFridaGadget(@Suppress("UNUSED_PARAMETER") params: JSONObject): JSONObject {
        val frida = FridaGadget.install(appContext)
        val native = NativeHookLib.install(appContext)
        if (!frida && !native) {
            throw RpcException(
                -32603,
                "native components install failed: stage /data/local/tmp/ and retry, or root unavailable",
            )
        }
        return JSONObject()
            .put("installed", true)
            .put("frida", frida)
            .put("native_hook", native)
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
    }

    private fun scriptFilesFrom(params: JSONObject): List<ScriptFile> {
        val files = params.optJSONArray("files") ?: return emptyList()
        val result = mutableListOf<ScriptFile>()
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            result += ScriptFile(
                name = file.getString("name"),
                content = file.getString("content"),
            )
        }
        return result
    }

    /** Reused by [ScriptManager] for `script_reload --restart`. */
    private fun restartTargetsForScript(packages: List<String>): JSONObject {
        val payload = JSONObject().put("packages", JSONArray(packages))
        return restartTargets(payload)
    }    private fun jsonRpcError(id: String, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private class RpcException(val code: Int, override val message: String) : Exception(message)

    companion object {
        const val HTTP_PORT = 8764
        const val WS_PORT = 8765

        const val METHOD_PING = "ping"
        const val METHOD_GET_STATUS = "get_status"
        const val METHOD_INSPECT_DEVICE = "inspect_device"
        const val METHOD_APP_LIST = "app_list"
        const val METHOD_APP_INFO = "app_info"
        const val METHOD_PROCESS_LIST = "process_list"
        const val METHOD_TRIGGER_APP = "trigger_app"
        const val METHOD_GET_LOGS = "get_logs"
        const val METHOD_MODULE_DIAGNOSTICS = "module_diagnostics"
        const val METHOD_APPLY_PROFILE = "apply_profile"
        const val METHOD_TOGGLE_MOD = "toggle_mod"
        const val METHOD_VERIFY = "verify"
        const val METHOD_REAPPLY = "reapply"
        const val METHOD_COLLECT_LOGS = "collect_logs"
        const val METHOD_SOFT_RESTART = "soft_restart"
        const val METHOD_DEPLOY_RULE = "deploy_rule"
        const val METHOD_RESTART_TARGETS = "restart_targets"
        const val METHOD_SCRIPT_VALIDATE = "script_validate"
        const val METHOD_SCRIPT_DEPLOY = "script_deploy"
        const val METHOD_SCRIPT_LIST = "script_list"
        const val METHOD_SCRIPT_ENABLE = "script_enable"
        const val METHOD_SCRIPT_DISABLE = "script_disable"
        const val METHOD_SCRIPT_REMOVE = "script_remove"
        const val METHOD_SCRIPT_RELOAD = "script_reload"
        const val METHOD_INSTALL_FRIDA_GADGET = "install_frida_gadget"

        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val COMPONENT_CLASS = Regex("\\.?[A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
        private val RULE_ID = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
        private const val MAX_RULE_BYTES = 1024 * 1024
    }
}
