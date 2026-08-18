package com.modspec.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Agent 本地存储（source of truth）+ legacy 共享目录降级。
 * Hook 进程主路径：libxposed [RemoteRulesManager] openRemoteFile。
 */
object AgentStorage {
    const val RULES_DIR = "rules"
    const val PROFILES_DIR = "profiles"
    const val STATE_FILE = "state.json"

    /** @deprecated 仅作 XposedService 未绑定时的 root 降级 */
    const val SHARED_RULES_DIR = "/data/local/tmp/modspec/rules"
    const val SHARED_RELOAD_MARKER = "$SHARED_RULES_DIR/.reload"

    fun filesDir(context: Context): File = context.filesDir

    fun rulesDir(context: Context): File =
        File(filesDir(context), RULES_DIR).also { it.mkdirs() }

    fun profilesDir(context: Context): File =
        File(filesDir(context), PROFILES_DIR).also { it.mkdirs() }

    fun stateFile(context: Context): File = File(filesDir(context), STATE_FILE)

    fun readState(context: Context): JSONObject {
        val file = stateFile(context)
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }
            .getOrDefault(JSONObject())
    }

    fun writeState(context: Context, state: JSONObject) {
        stateFile(context).writeText(state.toString(2))
    }

    /** Legacy：root cp 到 /data/local/tmp，供无 XposedService 时降级。 */
    fun syncRulesToSharedLegacy(context: Context) {
        if (!ShellRunner.canSu()) return
        val local = rulesDir(context)
        val shared = File(SHARED_RULES_DIR)
        val cmd = buildString {
            append("mkdir -p '${shared.absolutePath}' && ")
            append("cp -f '${local.absolutePath}'/*.rule.toml '${shared.absolutePath}/' 2>/dev/null; ")
            append("echo ${System.currentTimeMillis()} > '${shared.absolutePath}/.reload'; ")
            append("chmod -R a+rX '${shared.absolutePath}'")
        }
        ShellRunner.runSu(cmd).getOrNull()
    }

    fun ruleAssetPath(ruleId: String): String = "rules/$ruleId.rule.toml"

    fun ruleFile(context: Context, ruleId: String): File {
        val safeName = safeRuleName(ruleId)
        return File(rulesDir(context), "$safeName.rule.toml")
    }

    fun safeRuleName(ruleId: String): String =
        ruleId.replace("%", "%25").replace("/", "%2F")

    fun activeRuleIds(state: JSONObject): List<String> =
        state.optJSONArray("active_rules")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it, null) } }
            ?: emptyList()
}
