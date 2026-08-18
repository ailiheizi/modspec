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
    private lateinit var scriptEngine: ScriptEngine

    /**
     * Callbacks are synchronized on the module instance so a hot-reload
     * swap of the engines (shutdown + rebuild) cannot race an in-flight
     * package/system load or a listener reload.
     */
    @Synchronized
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded")
        ruleEngine = RuleEngine(this)
        scriptEngine = ScriptEngine(this)
        registerListeners()
        ruleEngine.reloadIfNeeded()
        scriptEngine.reloadIfNeeded()
    }

    @Synchronized
    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.i(TAG, "onPackageLoaded pkg=${param.packageName}")
        ruleEngine.onPackageLoaded(param)
        scriptEngine.onPackageLoaded(param)
    }

    @Synchronized
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        ruleEngine.onSystemServerStarting(param)
    }

    @Synchronized
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        ruleEngine.shutdown()
        scriptEngine.shutdown()
        return true
    }

    @Synchronized
    override fun onHotReloaded(param: HotReloadedParam) {
        ruleEngine = RuleEngine(this)
        scriptEngine = ScriptEngine(this)
        registerListeners()
        ruleEngine.reloadIfNeeded()
        scriptEngine.reloadIfNeeded()
    }

    /** libxposed 推荐：配置变更走 RemotePreferences，hook 进程监听后重载。 */
    private fun registerListeners() {
        runCatching {
            getRemotePreferences(RemotePrefsManager.DEFAULT_GROUP)
                .registerOnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        ModuleReloader.KEY_RULES_GENERATION -> ruleEngine.reloadIfNeeded()
                        ScriptManager.KEY_SCRIPTS_GENERATION,
                        ScriptManager.KEY_ACTIVE_SCRIPT -> scriptEngine.reloadIfNeeded()
                    }
                }
        }
    }

    companion object {
        private const val TAG = "ModspecModule"
        const val AGENT_PACKAGE = "com.modspec.agent"
    }
}
