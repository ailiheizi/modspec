package com.modspec.agent

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModspecModule : XposedModule() {

    private lateinit var ruleEngine: RuleEngine

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded")
        ruleEngine = RuleEngine(this)
        registerRulesListener()
        ruleEngine.reloadIfNeeded()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.i(TAG, "onPackageLoaded pkg=${param.packageName}")
        ruleEngine.onPackageLoaded(param)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        ruleEngine.onSystemServerStarting(param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        ruleEngine.shutdown()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        ruleEngine = RuleEngine(this)
        registerRulesListener()
        ruleEngine.reloadIfNeeded()
    }

    /** libxposed 推荐：配置变更走 RemotePreferences，hook 进程监听后重载规则。 */
    private fun registerRulesListener() {
        runCatching {
            getRemotePreferences(RemotePrefsManager.DEFAULT_GROUP)
                .registerOnSharedPreferenceChangeListener { _, key ->
                    if (key == ModuleReloader.KEY_RULES_GENERATION) {
                        ruleEngine.reloadIfNeeded()
                    }
                }
        }
    }

    companion object {
        private const val TAG = "ModspecModule"
        const val AGENT_PACKAGE = "com.modspec.agent"
    }
}

