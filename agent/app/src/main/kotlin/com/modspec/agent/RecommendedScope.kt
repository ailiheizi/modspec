package com.modspec.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * LSPosed scope for modspec-agent.
 *
 * Hook 目标只来自已部署到 files/rules/ 下的规则文件，不扫 profile 里的 reload/module_ref。
 * `scope.list` 仅声明 `system`（系统框架）。
 */
object RecommendedScope {
    const val FRAMEWORK = "system"

    val DECLARED: List<String> = listOf(FRAMEWORK)

    fun isFramework(pkg: String): Boolean = pkg == FRAMEWORK

    data class Requirement(
        val needsFramework: Boolean = false,
        val userPackages: Set<String> = emptySet(),
        val source: String? = null,
    )

    /** Environment check + state self-heal — never trust stale `required_scope` cache alone. */
    fun forCheck(context: Context, forceRefresh: Boolean = false): Requirement {
        val deployed = collectFromDeployedRules(context)
        val state = AgentStorage.readState(context)
        val source = state.optString("active_profile", null)
        val healed = deployed.copy(source = source)
        if (forceRefresh) {
            refreshScopeCache(context, healed)
        }
        return healed
    }

    fun collectFromDeployedRules(context: Context): Requirement {
        val user = linkedSetOf<String>()
        var needsFramework = false

        AgentStorage.rulesDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".rule.toml") }
            ?.forEach { file ->
                RuleParser.parseFile(file)?.let { rule ->
                    user.addAll(rule.packages)
                    if (rule.hooks.any { it.phase == "on_system_server" }) {
                        needsFramework = true
                    }
                }
            }

        return Requirement(
            needsFramework = needsFramework,
            userPackages = user,
        )
    }

    private fun refreshScopeCache(context: Context, requirement: Requirement) {
        val state = AgentStorage.readState(context)
        val cached = state.optJSONArray("required_scope")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
            ?: emptySet()
        val needsCached = state.optBoolean("required_framework", false)
        if (cached == requirement.userPackages && needsCached == requirement.needsFramework) {
            return
        }
        state.put("required_scope", JSONArray(requirement.userPackages.toList()))
        state.put("required_framework", requirement.needsFramework)
        AgentStorage.writeState(context, state)
    }

    private fun JSONObject.optString(name: String, default: String?): String? =
        if (has(name) && !isNull(name)) getString(name) else default
}
