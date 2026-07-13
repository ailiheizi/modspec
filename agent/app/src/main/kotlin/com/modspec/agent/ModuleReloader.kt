package com.modspec.agent

import android.content.Context
import android.os.Bundle
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 软重启 / 规则重载 — 对齐 libxposed 官方 example：
 * - 规则变更：RemotePreferences + 共享 rules 目录（hook 进程监听）
 * - 模块/APK 更新：XposedService.hotReloadModule（API 102+）
 * - 兜底：force-stop 作用域内目标应用
 */
object ModuleReloader {

    const val KEY_RULES_GENERATION = "rules_generation"

    data class Result(
        val hotReloadOk: Int = 0,
        val hotReloadFailed: Int = 0,
        val hotReloadUnsupported: Int = 0,
        val runningTargets: Int = 0,
        val restartedPackages: List<String> = emptyList(),
        val message: String,
    )

    /** 仅推送规则；无 XposedService 时仍同步文件并 force-stop 目标应用。 */
    fun reloadRules(context: Context): Result {
        publishRulesGeneration(context)
        val stopped = if (ModspecApp.xposedService == null) forceStopScopedPackages(context) else emptyList()
        val message = if (ModspecApp.xposedService != null) {
            "规则已同步，Hook 进程将通过 RemotePreferences 重载"
        } else {
            "规则已同步；XposedService 未连接，已重启 ${stopped.size} 个目标应用作兜底"
        }
        return Result(restartedPackages = stopped, message = message)
    }

    /**
     * 完整软重启：同步规则 → hot reload 所有运行中目标 → 必要时 force-stop。
     * 模式参考 libxposed/example MainActivity 的 reload 按钮。
     */
    fun softRestart(context: Context, waitSeconds: Long = 25): Result {
        publishRulesGeneration(context)
        val service = ModspecApp.xposedService
            ?: return fallbackForceStop(context, "XposedService 未连接")

        if (service.apiVersion < 102) {
            return fallbackForceStop(context, "框架 API ${service.apiVersion} < 102，不支持 hot reload")
        }

        val targets = service.runningTargets
        if (targets.isEmpty()) {
            return fallbackForceStop(context, "暂无运行中的 Hook 进程")
        }

        val extras = Bundle().apply {
            putLong(KEY_RULES_GENERATION, System.currentTimeMillis())
        }
        val latch = CountDownLatch(targets.size)
        val ok = AtomicInteger()
        val failed = AtomicInteger()
        val unsupported = AtomicInteger()
        val details = mutableListOf<String>()

        for (target in targets) {
            service.hotReloadModule(target, extras, object : XposedService.HotReloadCallback {
                override fun onHotReloadResult(t: HookedTarget, result: HotReloadResult) {
                    when (result.status) {
                        HotReloadResult.Status.SUCCEEDED -> ok.incrementAndGet()
                        HotReloadResult.Status.UNSUPPORTED -> unsupported.incrementAndGet()
                        else -> failed.incrementAndGet()
                    }
                    details += "${t.processName}: ${result.status}"
                    latch.countDown()
                }
            })
        }

        latch.await(waitSeconds, TimeUnit.SECONDS)
        val stopped = if (failed.get() > 0 || unsupported.get() > 0) {
            forceStopScopedPackages(context)
        } else {
            emptyList()
        }

        return Result(
            hotReloadOk = ok.get(),
            hotReloadFailed = failed.get(),
            hotReloadUnsupported = unsupported.get(),
            runningTargets = targets.size,
            restartedPackages = stopped,
            message = buildMessage(ok.get(), failed.get(), unsupported.get(), stopped, details),
        )
    }

    private fun publishRulesGeneration(context: Context) {
        val state = AgentStorage.readState(context)
        val ruleIds = AgentStorage.activeRuleIds(state)
        RemoteRulesManager.publishRules(context, ruleIds)
    }

    fun listRunningTargets(): List<HookProcessRow> {
        val service = ModspecApp.xposedService ?: return emptyList()
        return service.runningTargets.map { HookProcessRow(it.processName, it.uid) }
    }

    fun runningTargetSummary(): String {
        val service = ModspecApp.xposedService
        if (service == null) return XposedServiceCoordinator.statusLabel()
        val framework = "${service.frameworkName} API ${service.apiVersion}"
        val targets = listRunningTargets()
        return if (targets.isEmpty()) {
            "已连接 · $framework\n暂无运行中的 Hook 进程"
        } else {
            "已连接 · $framework\n${targets.joinToString { it.processName }}"
        }
    }

    private fun fallbackForceStop(context: Context, reason: String): Result {
        val stopped = forceStopScopedPackages(context)
        return Result(
            restartedPackages = stopped,
            message = "$reason，已重启 ${stopped.size} 个目标应用",
        )
    }

    fun forceStopScopedPackages(context: Context): List<String> {
        if (!ShellRunner.canSu()) return emptyList()
        val packages = RecommendedScope.forCheck(context).userPackages
        val stopped = mutableListOf<String>()
        for (pkg in packages) {
            if (ShellRunner.runSu("am force-stop $pkg").isSuccess) {
                stopped.add(pkg)
            }
        }
        return stopped
    }

    private fun buildMessage(
        ok: Int,
        failed: Int,
        unsupported: Int,
        stopped: List<String>,
        details: List<String>,
    ): String = buildString {
        append("hot reload 成功 $ok")
        if (failed > 0) append("，失败 $failed")
        if (unsupported > 0) append("，不支持 $unsupported")
        if (stopped.isNotEmpty()) append("；已重启 ${stopped.joinToString()}")
        if (details.isNotEmpty()) append("（${details.joinToString("；")}）")
    }
}
