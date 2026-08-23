package com.modspec.agent

import android.content.Context
import io.github.libxposed.service.HookedTarget
import org.json.JSONArray
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

data class ShellToggleRow(
    val id: String,
    val title: String,
    val onCommand: String,
    val offCommand: String,
    val statusCommand: String?,
    val statusPattern: String?,
    val currentStatus: Boolean,
)

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
        val toggles = state.optJSONObject("shell_toggles")
            ?.let { obj ->
                obj.keys().asSequence().mapNotNull { id ->
                    val def = obj.getJSONObject(id)
                    val persisted = state.optJSONObject("shell_toggle_state")
                        ?.optBoolean(id, false) ?: false
                    val statusCommand = def.optString("status_command")
                        .takeIf { it.isNotBlank() && it != "null" }
                    val statusPattern = def.optString("status_pattern")
                        .takeIf { it.isNotBlank() && it != "null" }
                    val current = when {
                        statusCommand != null && ShellRunner.canSu() -> {
                            val result = ShellRunner.runSu(statusCommand)
                            val output = result.getOrNull()?.trim().orEmpty()
                            when {
                                // 查询失败：回退到持久化状态
                                result.isFailure -> persisted
                                // 有 pattern：匹配结果为准，但输出空时回退
                                statusPattern != null -> {
                                    if (output.isBlank()) persisted
                                    else Regex(statusPattern).containsMatchIn(output)
                                }
                                // 无 pattern：非空视为开启，空输出回退
                                else -> if (output.isBlank()) persisted else output.isNotBlank()
                            }
                        }
                        else -> persisted
                    }
                    ShellToggleRow(
                        id = id,
                        title = def.optString("title").ifBlank { id },
                        onCommand = def.optString("on_command"),
                        offCommand = def.optString("off_command"),
                        statusCommand = statusCommand,
                        statusPattern = statusPattern,
                        currentStatus = current,
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
