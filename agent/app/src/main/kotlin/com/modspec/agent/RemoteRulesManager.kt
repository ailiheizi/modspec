package com.modspec.agent

import android.content.Context
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import org.json.JSONArray

/**
 * 规则同步 — libxposed 官方路径：[XposedService.openRemoteFile] / [XposedModule.listRemoteFiles]。
 * 对齐 [RemoteBlobManager] 与 libxposed/example。
 */
object RemoteRulesManager {

    const val RULE_SUFFIX = ".rule.toml"
    const val KEY_ACTIVE_RULES = "active_rules"

    fun isAvailable(): Boolean = ModspecApp.xposedService != null

    fun remoteFileName(ruleId: String): String {
        val safe = ruleId.replace('/', '_').replace('\\', '_')
        require(safe.isNotBlank() && safe != "." && safe != "..") { "invalid rule id: $ruleId" }
        return "$safe$RULE_SUFFIX"
    }

    fun deployRule(context: Context, ruleId: String) {
        val local = AgentStorage.ruleFile(context, ruleId)
        require(local.exists()) { "missing local rule: $ruleId" }
        writeRemoteFile(local.readBytes(), remoteFileName(ruleId))
    }

    /** 将本地 files/rules/ 全量同步到 libxposed 远程目录，并清理孤儿文件。 */
    fun syncFromLocal(context: Context, activeRuleIds: Collection<String>? = null) {
        val service = requireService()
        val keepNames = mutableSetOf<String>()
        AgentStorage.rulesDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(RULE_SUFFIX) }
            ?.forEach { file ->
                keepNames += file.name
                service.openRemoteFile(file.name).use { pfd ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        file.inputStream().copyTo(out)
                    }
                }
            }
        service.listRemoteFiles()
            .filter { it.endsWith(RULE_SUFFIX) && it !in keepNames }
            .forEach { service.deleteRemoteFile(it) }
        activeRuleIds?.let { ids ->
            RemotePrefsManager.set(KEY_ACTIVE_RULES, JSONArray(ids.toList()).toString())
        }
    }

    fun publishGeneration(activeRuleIds: Collection<String>? = null) {
        val generation = System.currentTimeMillis()
        RemotePrefsManager.set(ModuleReloader.KEY_RULES_GENERATION, generation)
        activeRuleIds?.let { ids ->
            RemotePrefsManager.set(KEY_ACTIVE_RULES, JSONArray(ids.toList()).toString())
        }
    }

    /**
     * 主路径：RemoteFile + RemotePrefs；降级：legacy tmp 目录 + force-stop 由调用方处理。
     */
    fun publishRules(context: Context, activeRuleIds: Collection<String>? = null): PublishMode {
        return if (isAvailable()) {
            syncFromLocal(context, activeRuleIds)
            publishGeneration(activeRuleIds)
            PublishMode.REMOTE_FILE
        } else {
            AgentStorage.syncRulesToSharedLegacy(context)
            runCatching { publishGeneration(activeRuleIds) }
            PublishMode.LEGACY_TMP
        }
    }

    private fun writeRemoteFile(bytes: ByteArray, remoteName: String) {
        requireService().openRemoteFile(remoteName).use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(bytes) }
        }
    }

    private fun requireService(): XposedService =
        ModspecApp.xposedService
            ?: error("XposedService not bound")

    enum class PublishMode { REMOTE_FILE, LEGACY_TMP }
}
