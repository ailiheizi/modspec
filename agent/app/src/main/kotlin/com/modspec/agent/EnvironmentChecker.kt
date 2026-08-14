package com.modspec.agent

import android.content.Context

/**
 * Runtime diagnostics for modspec-agent prerequisites.
 *
 * Patterns borrowed from:
 * - HMA-OSS: companion app + XposedService binding before remote config
 * - libxposed universal template: XposedServiceHelper listener in Application
 * - LSPosed_mod CLI wiki: CLI is optional fork feature, enabled in Manager settings
 */
object EnvironmentChecker {

    enum class Status { OK, WARN, FAIL }

    private const val CACHE_TTL_MS = 60_000L
    @Volatile
    private var cachedReport: Report? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    fun run(context: Context, forceRefresh: Boolean = false): Report {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedReport?.let { report ->
                if (now - cachedAtMs < CACHE_TTL_MS) return report
            }
        }
        return buildReport(context, forceRefresh).also {
            cachedReport = it
            cachedAtMs = now
        }
    }

    data class Item(
        val id: String,
        val title: String,
        val why: String,
        val status: Status,
        val detail: String,
        val hint: String? = null,
    )

    data class Report(val items: List<Item>) {
        fun toJsonArray(): org.json.JSONArray {
            val array = org.json.JSONArray()
            items.forEach { item ->
                array.put(
                    org.json.JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("why", item.why)
                        .put("status", item.status.name.lowercase())
                        .put("detail", item.detail)
                        .put("hint", item.hint),
                )
            }
            return array
        }
    }

    private fun buildReport(context: Context, forceRefresh: Boolean): Report = Report(
        listOf(
            checkRoot(),
            checkLsposedFramework(),
            checkModuleEnabled(context, forceRefresh),
            checkModuleScope(context, forceRefresh),
            checkXposedService(),
            checkAgentService(),
            checkPairing(context),
        ) + checkRemoteRules(context),
    )

    private fun checkRoot(): Item {
        val workingSu = detectWorkingSu()
        val ok = workingSu != null && ShellRunner.canSu()
        return Item(
            id = "root",
            title = "Root / su",
            why = "执行 module_prefs、清理缓存、进程 reload 等 profile 后处理。",
            status = if (ok) Status.OK else Status.FAIL,
            detail = when {
                ok -> "可用（$workingSu，uid=0）"
                workingSu != null -> "su 存在但无法获得 root：$workingSu"
                else -> "未找到可用的 su（Magisk / KernelSU 等）"
            },
            hint = if (ok) null else "在 Magisk/KSU 中为 ModSpec Agent 授权 root，并确认 shell 可执行 su。",
        )
    }

    private fun checkLsposedFramework(): Item {
        val lspdDir = listOf("/data/adb/lspd", "/data/adb/lsposed").firstOrNull { ShellRunner.fileExists(it) }
        val dbPath = "/data/adb/lspd/config/modules_config.db"
        val dbExists = ShellRunner.fileExists(dbPath)
        val version = readLsposedVersion(lspdDir)
        val ok = lspdDir != null || dbExists
        return Item(
            id = "lsposed_framework",
            title = "LSPosed 框架",
            why = "Hook 规则（rule_ref）与 XposedService 都依赖 LSPosed/Zygisk 已刷入并运行。",
            status = when {
                ok && dbExists -> Status.OK
                ok -> Status.WARN
                else -> Status.FAIL
            },
            detail = buildString {
                if (lspdDir != null) append("目录 $lspdDir")
                if (version != null) {
                    if (isNotEmpty()) append(" · ")
                    append("版本 $version")
                }
                if (dbExists) {
                    if (isNotEmpty()) append(" · ")
                    append("config DB 存在")
                }
                if (isEmpty()) append("未检测到 /data/adb/lspd")
            },
            hint = if (ok) null else "在 Magisk 中启用 Zygisk + LSPosed 模块并重启。",
        )
    }

    private fun checkModuleEnabled(context: Context, forceRefresh: Boolean): Item {
        val enabled = ScopeReader.readAgentModule(context, forceRefresh).enabled
        return when {
            enabled == true -> Item(
                id = "module_enabled",
                title = "本模块已在 LSPosed 启用",
                why = "未启用时 Hook 不会注入目标应用，rule_ref / remote_prefs 均无效。",
                status = Status.OK,
                detail = "com.modspec.agent 已启用",
            )
            enabled == false -> Item(
                id = "module_enabled",
                title = "本模块已在 LSPosed 启用",
                why = "未启用时 Hook 不会注入目标应用，rule_ref / remote_prefs 均无效。",
                status = Status.FAIL,
                detail = "模块已登记但未勾选启用",
                hint = "打开 LSPosed Manager → 模块 → 启用 ModSpec Agent。",
            )
            ModspecApp.xposedService != null -> Item(
                id = "module_enabled",
                title = "本模块已在 LSPosed 启用",
                why = "未启用时 Hook 不会注入目标应用，rule_ref / remote_prefs 均无效。",
                status = Status.OK,
                detail = "XposedService 已连接（推断已启用）",
            )
            else -> Item(
                id = "module_enabled",
                title = "本模块已在 LSPosed 启用",
                why = "未启用时 Hook 不会注入目标应用，rule_ref / remote_prefs 均无效。",
                status = Status.FAIL,
                detail = "无法确认（无 DB 记录且服务未绑定）",
                hint = "在 LSPosed Manager 启用本模块后，再打开一次本应用。",
            )
        }
    }

    private fun checkModuleScope(context: Context, forceRefresh: Boolean): Item {
        val snapshot = ScopeReader.readAgentModule(context, forceRefresh)
        val scope = snapshot.packages
        val requirement = RecommendedScope.forCheck(context, forceRefresh)
        val hasFramework = ScopeReader.hasFramework(scope)
        val missingApps = requirement.userPackages.filter { it !in scope }.sorted()
        val scopeWhy = "rule_ref 需要在目标应用进程内注入 Hook。"

        if (!snapshot.readable) {
            val rootOk = ShellRunner.canSu()
            val serviceOk = ModspecApp.xposedService != null
            val manualTargets = requirement.userPackages
            return Item(
                id = "module_scope",
                title = "Hook 作用域",
                why = scopeWhy,
                status = if (rootOk && serviceOk) Status.OK else Status.WARN,
                detail = buildString {
                    append("本机无法自动读取 LSPosed 配置库")
                    if (manualTargets.isNotEmpty()) {
                        append("；若已部署规则，请确认已勾选：系统框架")
                        append(manualTargets.joinToString(prefix = " + ", separator = " + "))
                    } else {
                        append("；请确认已勾选系统框架")
                    }
                },
                hint = if (rootOk && serviceOk) null else "授权 root 并重新打开 App 绑定 XposedService。",
            )
        }

        return when {
            !hasFramework -> Item(
                id = "module_scope",
                title = "Hook 作用域",
                why = scopeWhy,
                status = Status.WARN,
                detail = "未勾选系统框架（当前：${scope.joinToString().ifBlank { "空" }}）",
                hint = "在 LSPosed Manager 勾选「系统框架」。",
            )
            missingApps.isNotEmpty() -> Item(
                id = "module_scope",
                title = "Hook 作用域",
                why = scopeWhy,
                status = Status.WARN,
                detail = "系统框架已勾选；规则目标还需：${missingApps.joinToString()}",
                hint = "在 LSPosed Manager 为 ModSpec Agent 勾选上述包名。",
            )
            requirement.userPackages.isNotEmpty() -> Item(
                id = "module_scope",
                title = "Hook 作用域",
                why = scopeWhy,
                status = Status.OK,
                detail = "系统框架 + ${requirement.userPackages.joinToString()}",
            )
            else -> Item(
                id = "module_scope",
                title = "Hook 作用域",
                why = scopeWhy,
                status = Status.OK,
                detail = "系统框架已勾选",
            )
        }
    }

    private fun checkXposedService(): Item {
        val service = ModspecApp.xposedService
        return if (service != null) {
            Item(
                id = "xposed_service",
                title = "XposedService 连接",
                why = "remote_prefs、动态 scope、规则热更新需要 Manager 绑定 libxposed 服务（打开本应用一次）。",
                status = Status.OK,
                detail = "已绑定，可读写 remote preferences",
            )
        } else {
            Item(
                id = "xposed_service",
                title = "XposedService 连接",
                why = "remote_prefs、动态 scope、规则热更新需要 Manager 绑定 libxposed 服务（打开本应用一次）。",
                status = Status.WARN,
                detail = "未绑定",
                hint = "确认模块已启用后，完全退出并重新打开 ModSpec Agent（不要只切后台）。",
            )
        }
    }

    private fun checkAgentService(): Item {
        val running = AgentService.isRunning
        return Item(
            id = "agent_rpc",
            title = "Agent RPC 服务",
            why = "PC 端 modspec-cli 通过局域网 HTTP/WebSocket 下发 profile 与查询状态。",
            status = if (running) Status.OK else Status.FAIL,
            detail = if (running) {
                "运行中 · HTTP :${RpcPorts.HTTP} · WS :${RpcPorts.WS}"
            } else {
                "未运行"
            },
            hint = if (running) null else "打开本应用会自动启动前台服务；若被系统杀死请在电池优化中放行。",
        )
    }

    private fun checkPairing(context: Context): Item {
        val code = PairingStore.getPairingCode(context).orEmpty()
        return Item(
            id = "pairing",
            title = "配对码",
            why = "首次联调时 PC 需输入 6 位码以信任设备并写入 default_device。",
            status = if (code.length == 6) Status.OK else Status.WARN,
            detail = if (code.length == 6) "当前 $code" else "未生成",
        )
    }

    private fun checkRemoteRules(context: Context): List<Item> {
        val state = AgentStorage.readState(context)
        val ruleIds = AgentStorage.activeRuleIds(state)
        if (ruleIds.isEmpty()) {
            return listOf(
                Item(
                    id = "remote_rules",
                    title = "远端规则同步",
                    why = "rule_ref 依赖规则文件写入 rules/ 目录并被 Hook 进程加载。",
                    status = Status.OK,
                    detail = "no active rules",
                ),
            )
        }
        val missing = ruleIds.filter { !AgentStorage.ruleFile(context, it).exists() }
        if (missing.isEmpty()) {
            return listOf(
                Item(
                    id = "remote_rules",
                    title = "远端规则同步",
                    why = "rule_ref 依赖规则文件写入 rules/ 目录并被 Hook 进程加载。",
                    status = Status.OK,
                    detail = "active rules: ${ruleIds.size} 个文件均已存在",
                ),
            )
        }
        return missing.map { ruleId ->
            Item(
                id = "remote_rules",
                title = "远端规则同步",
                why = "rule_ref 依赖规则文件写入 rules/ 目录并被 Hook 进程加载。",
                status = Status.FAIL,
                detail = "active rule 缺少规则文件：$ruleId",
                hint = "rule file missing; re-apply profile or modspec rule run",
            )
        }
    }

    private fun detectWorkingSu(): String? = ShellRunner.workingSuPath()

    private fun readLsposedVersion(lspdDir: String?): String? {
        if (lspdDir != null) {
            ShellRunner.runSu("cat '$lspdDir/version' 2>/dev/null").getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return ShellRunner.runSu(
            "dumpsys package org.lsposed.manager 2>/dev/null | grep versionName | head -n1",
        ).getOrNull()
            ?.substringAfter("versionName=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
