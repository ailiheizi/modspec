package com.modspec.agent

import android.content.Context
import io.github.libxposed.service.HookedTarget
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeployedRuleRow(
    val ruleId: String,
    val displayName: String,
    val packages: List<String>,
    val hookCount: Int,
    val updatedAt: String,
    val inActiveProfile: Boolean,
)

data class HookProcessRow(
    val processName: String,
    val uid: Int?,
)

/**
 * Declarative shell toggle with three independent status channels:
 * - applied: 设置已应用（查持久化配置，如 settings get）
 * - effective: 实时生效（查运行时状态，如 dumpsys）
 * - precondition: 前置条件（如 softApEnabled）
 * Any channel whose query fails or returns empty output is `null`（未知）——
 * 不做持久化回退，未知就明示未知。
 */
data class ShellToggleRow(
    val id: String,
    val title: String,
    val description: String?,
    val aliases: List<String>,
    val categoryId: String?,
    val categoryTitles: List<String>,
    val onCommand: String,
    val offCommand: String,
    val appliedStatusCommand: String?,
    val appliedStatusPattern: String?,
    val effectiveStatusCommand: String?,
    val effectiveStatusPattern: String?,
    val preconditionCommand: String?,
    val preconditionPattern: String?,
    val requiresHint: String?,
    val autoPrereqCommand: String?,
    /** 用户上次手动切换的意图（持久化），仅用于 appliedStatus 未知时保持开关位置。 */
    val persistedIntent: Boolean,
    val appliedStatus: Boolean?,
    val effectiveStatus: Boolean?,
    val preconditionMet: Boolean?,
) {
    /** Case-insensitive keyword match over title/description/aliases/id. */
    fun matchesQuery(rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        return sequence {
            yield(title)
            yield(id)
            description?.let { yield(it) }
            yieldAll(aliases)
        }.any { it.contains(query, ignoreCase = true) }
    }
}

/**
 * Query one status channel. Rules:
 * - command 未配置 / 无 root / 执行失败 / 输出为空 → null（未知）
 * - 有 pattern：输出匹配 → true，不匹配 → false
 * - 无 pattern：输出非空 → true
 */
internal fun queryStatusChannel(rootAvailable: Boolean, command: String?, pattern: String?): Boolean? {
    if (command.isNullOrBlank() || !rootAvailable) return null
    val result = ShellRunner.runSu(command)
    val output = result.getOrNull()?.trim().orEmpty()
    if (result.isFailure || output.isEmpty()) return null
    if (pattern.isNullOrBlank()) return true
    return runCatching { Regex(pattern).containsMatchIn(output) }.getOrDefault(false)
}

/** Read a string field, treating JSON null / blank as absent; later keys act as legacy fallbacks. */
internal fun optDefString(def: JSONObject, vararg keys: String): String? {
    for (key in keys) {
        val value = def.optString(key)
        if (value.isNotBlank() && value != "null") return value
    }
    return null
}

data class HookPanelSnapshot(
    val serviceLabel: String,
    val serviceConnected: Boolean,
    val apiVersion: Int,
    val activeProfileId: String?,
    val deployedRules: List<DeployedRuleRow>,
    val runningProcesses: List<HookProcessRow>,
    val logLines: List<String>,
    val primaryAction: PrimaryAction,
    val shellToggles: List<ShellToggleRow>,
)

enum class PrimaryAction { SOFT_RESTART, RULES_ONLY, DISABLED }

object HookPanelLoader {

    fun load(context: Context): HookPanelSnapshot {
        val service = ModspecApp.xposedService
        val connected = service != null
        val api = service?.apiVersion ?: 0
        val state = AgentStorage.readState(context)
        val profileId = state.optString("active_profile").takeIf { it.isNotBlank() }
        val activeRules = state.optJSONArray("active_rules")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it, null) } }
            ?.toSet()
            ?: emptySet()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val rules = AgentStorage.rulesDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(RemoteRulesManager.RULE_SUFFIX) }
            ?.mapNotNull { file ->
                val parsed = RuleParser.parseFile(file) ?: return@mapNotNull null
                val ruleId = parsed.metaId
                DeployedRuleRow(
                    ruleId = ruleId,
                    displayName = ruleId,
                    packages = parsed.packages,
                    hookCount = parsed.hooks.size,
                    updatedAt = fmt.format(Date(file.lastModified())),
                    inActiveProfile = ruleId in activeRules,
                )
            }
            ?.sortedBy { it.ruleId }
            ?: emptyList()
        val processes = ModuleReloader.listRunningTargets()
        val primary = when {
            connected && api >= 102 -> PrimaryAction.SOFT_RESTART
            ShellRunner.canSu() -> PrimaryAction.RULES_ONLY
            else -> PrimaryAction.DISABLED
        }
        val rootAvailable = ShellRunner.canSu()
        val toggles = state.optJSONObject("shell_toggles")
            ?.let { obj ->
                obj.keys().asSequence().mapNotNull { id ->
                    val def = obj.getJSONObject(id)
                    val persistedIntent = state.optJSONObject("shell_toggle_state")
                        ?.optBoolean(id, false) ?: false
                    // 旧 profile 的 status_command/status_pattern 作为 applied 通道的回退
                    val appliedCommand = optDefString(def, "applied_status_command", "status_command")
                    val appliedPattern = optDefString(def, "applied_status_pattern", "status_pattern")
                    val effectiveCommand = optDefString(def, "effective_status_command")
                    val effectivePattern = optDefString(def, "effective_status_pattern")
                    val preconditionCommand = optDefString(def, "requires_command")
                    val preconditionPattern = optDefString(def, "requires_pattern")
                    val categoryTitles = def.optJSONArray("category_titles")
                        ?.let { arr ->
                            (0 until arr.length())
                                .mapNotNull { arr.optString(it, null) }
                                .filter { it.isNotBlank() }
                        }
                        .orEmpty()
                    ShellToggleRow(
                        id = id,
                        title = def.optString("title").ifBlank { id },
                        description = optDefString(def, "description"),
                        aliases = def.optJSONArray("aliases")
                            ?.let { arr ->
                                (0 until arr.length())
                                    .mapNotNull { arr.optString(it, null) }
                                    .filter { it.isNotBlank() }
                            }
                            .orEmpty(),
                        categoryId = optDefString(def, "category"),
                        categoryTitles = categoryTitles,
                        onCommand = def.optString("on_command"),
                        offCommand = def.optString("off_command"),
                        appliedStatusCommand = appliedCommand,
                        appliedStatusPattern = appliedPattern,
                        effectiveStatusCommand = effectiveCommand,
                        effectiveStatusPattern = effectivePattern,
                        preconditionCommand = preconditionCommand,
                        preconditionPattern = preconditionPattern,
                        requiresHint = optDefString(def, "requires_hint"),
                        autoPrereqCommand = optDefString(def, "auto_prereq_command"),
                        persistedIntent = persistedIntent,
                        appliedStatus = queryStatusChannel(rootAvailable, appliedCommand, appliedPattern),
                        effectiveStatus = queryStatusChannel(rootAvailable, effectiveCommand, effectivePattern),
                        preconditionMet = queryStatusChannel(rootAvailable, preconditionCommand, preconditionPattern),
                    )
                }.toList()
            }
            ?: emptyList()
        return HookPanelSnapshot(
            serviceLabel = XposedServiceCoordinator.statusLabel(),
            serviceConnected = connected,
            apiVersion = api,
            activeProfileId = profileId,
            deployedRules = rules,
            runningProcesses = processes,
            logLines = LogTailReader.tail(8),
            primaryAction = primary,
            shellToggles = toggles,
        )
    }
}
