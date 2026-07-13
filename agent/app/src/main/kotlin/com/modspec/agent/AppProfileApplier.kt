package com.modspec.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Applies profile mods in the agent app process (has Context + root shell).
 * Writes rules/state to disk; [RuleEngine] in hooked processes picks up changes.
 */
class AppProfileApplier(private val context: Context) {

    fun applyFromJson(profile: JSONObject, dryRun: Boolean): String {
        val jobId = "apply_${UUID.randomUUID().toString().take(8)}"
        val profileId = profile.getJSONObject("meta").getString("id")
        val mods = profile.optJSONArray("mods") ?: JSONArray()

        if (dryRun) {
            return jobId
        }

        val profileFile = AgentStorage.profilesDir(context)
            .resolve("$profileId.json")
        profileFile.writeText(profile.toString(2))

        val state = AgentStorage.readState(context)
        state.put("active_profile", profileId)
        val items = state.optJSONObject("items") ?: JSONObject().also { state.put("items", it) }
        val activeRules = JSONArray()

        val sorted = topologicalSort(mods)
        for (mod in sorted) {
            val modId = mod.getString("id")
            val enabled = mod.optBoolean("enabled", true)
            val type = mod.getString("type")
            val item = JSONObject()
                .put("enabled", enabled)
                .put("status", if (enabled) "applied" else "disabled")

            if (!enabled) {
                items.put(modId, item)
                continue
            }

            try {
                when (type) {
                    "module_ref", "lsposed_module" -> {
                        if (!applyModuleRef(mod)) {
                            item.put("status", "manual")
                            item.put(
                                "note",
                                "请在 LSPosed Manager 手动启用 ${mod.getString("package")}",
                            )
                        }
                    }
                    "scope" -> {
                        if (!applyScope(mod)) {
                            item.put("status", "manual")
                            item.put("note", "请在 LSPosed Manager 手动配置作用域")
                        }
                    }
                    "rule_ref" -> {
                        applyRuleRef(mod)
                        activeRules.put(mod.optString("rule"))
                    }
                    "reload" -> applyReload(mod)
                    "post_action" -> applyPostAction(mod)
                    "module_prefs" -> applyModulePrefs(mod)
                    "remote_prefs" -> applyRemotePrefs(mod)
                    "remote_blob" -> applyRemoteBlob(mod)
                    "dynamic_scope" -> applyDynamicScope(mod)
                    else -> item.put("status", "skipped")
                }
                if (!item.has("status")) item.put("status", "applied")
            } catch (e: Exception) {
                item.put("status", "failed")
                item.put("last_error", e.message ?: e.toString())
            }
            items.put(modId, item)
        }

        state.put("active_rules", activeRules)
        val scopeReq = RecommendedScope.collectFromDeployedRules(context)
        state.put("required_scope", JSONArray(scopeReq.userPackages.toList()))
        state.put("required_framework", scopeReq.needsFramework)
        state.put("last_apply", java.time.Instant.now().toString())
        AgentStorage.writeState(context, state)
        val ruleIds = (0 until activeRules.length()).mapNotNull { activeRules.optString(it, null) }
        RemoteRulesManager.publishRules(context, ruleIds)
        return jobId
    }

    fun reapply(onlyFailed: Boolean): String {
        val state = AgentStorage.readState(context)
        val profileId = state.optString("active_profile", null) ?: return "reapply_no_profile"
        val profileFile = AgentStorage.profilesDir(context).resolve("$profileId.json")
        if (!profileFile.exists()) return "reapply_missing_profile"
        val profile = JSONObject(profileFile.readText())
        if (onlyFailed) {
            // Re-run only mods marked failed in state
            val items = state.optJSONObject("items") ?: JSONObject()
            val mods = profile.getJSONArray("mods")
            val filtered = JSONArray()
            for (i in 0 until mods.length()) {
                val mod = mods.getJSONObject(i)
                val status = items.optJSONObject(mod.getString("id"))
                    ?.optString("status")
                if (status == "failed") filtered.put(mod)
            }
            profile.put("mods", filtered)
        }
        return applyFromJson(profile, dryRun = false).let { "reapply_$it" }
    }

    /** @return false when CLI unavailable — caller should mark mod as manual. */
    private fun applyModuleRef(mod: JSONObject): Boolean {
        if (!LsposedCli.isAvailable()) return false
        val pkg = mod.getString("package")
        LsposedCli.enableModule(pkg).getOrThrow()
        val scope = mod.optJSONArray("scope")?.toStringList().orEmpty()
        if (scope.isNotEmpty()) {
            LsposedCli.setScope(pkg, scope).getOrThrow()
        }
        return true
    }

    /** @return false when CLI unavailable — caller should mark mod as manual. */
    private fun applyScope(mod: JSONObject): Boolean {
        if (!LsposedCli.isAvailable()) return false
        val module = mod.getString("module")
        val apps = mod.getJSONArray("apps").toStringList()
        LsposedCli.setScope(module, apps).getOrThrow()
        return true
    }

    private fun applyRuleRef(mod: JSONObject) {
        val ruleId = mod.getString("rule")
        val dest = AgentStorage.ruleFile(context, ruleId)
        val assetPath = AgentStorage.ruleAssetPath(ruleId)
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun applyReload(mod: JSONObject) {
        val packages = mod.getJSONArray("packages").toStringList()
        for (pkg in packages) {
            ShellRunner.runSu("am force-stop $pkg").getOrNull()
        }
    }

    private fun applyPostAction(mod: JSONObject) {
        val commands = mod.getJSONArray("commands").toStringList()
        for (cmd in commands) {
            ShellRunner.runSu(cmd).getOrThrow()
        }
    }

    private fun applyModulePrefs(mod: JSONObject) {
        val module = mod.getString("module")
        val prefs = mod.getJSONObject("prefs")
        ModulePrefsWriter.writeFromJson(module, prefs)
    }

    private fun applyRemotePrefs(mod: JSONObject) {
        val key = mod.getString("key")
        val value = mod.opt("value")
        RemotePrefsManager.setFromJson(key, value)
    }

    private fun applyRemoteBlob(mod: JSONObject) {
        val path = mod.getString("path")
        val source = mod.getString("source")
        RemoteBlobManager.deploy(context, path, source)
    }

    private fun applyDynamicScope(mod: JSONObject) {
        val packages = mod.getJSONArray("packages").toStringList()
        val service = ModspecApp.xposedService
            ?: error("XposedService not bound")
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: Exception? = null
        service.requestScope(packages, object : io.github.libxposed.service.XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: MutableList<String>) {
                latch.countDown()
            }
            override fun onScopeRequestFailed(message: String) {
                error = IllegalStateException(message)
                latch.countDown()
            }
        })
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        error?.let { throw it }
    }

    private fun topologicalSort(mods: JSONArray): List<JSONObject> {
        val list = (0 until mods.length()).map { mods.getJSONObject(it) }
        val byId = list.associateBy { it.getString("id") }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<JSONObject>()

        fun visit(id: String) {
            if (id in visited) return
            visited.add(id)
            val mod = byId[id] ?: return
            mod.optJSONArray("depends_on")?.toStringList()?.forEach { visit(it) }
            result.add(mod)
        }

        list.forEach { visit(it.getString("id")) }
        return result
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, null) }
}
