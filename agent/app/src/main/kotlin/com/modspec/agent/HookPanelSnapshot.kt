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

data class HookPanelSnapshot(
    val serviceLabel: String,
    val serviceConnected: Boolean,
    val apiVersion: Int,
    val activeProfileId: String?,
    val deployedRules: List<DeployedRuleRow>,
    val runningProcesses: List<HookProcessRow>,
    val logLines: List<String>,
    val primaryAction: PrimaryAction,
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
        return HookPanelSnapshot(
            serviceLabel = XposedServiceCoordinator.statusLabel(),
            serviceConnected = connected,
            apiVersion = api,
            activeProfileId = profileId,
            deployedRules = rules,
            runningProcesses = processes,
            logLines = LogTailReader.tail(8),
            primaryAction = primary,
        )
    }
}
