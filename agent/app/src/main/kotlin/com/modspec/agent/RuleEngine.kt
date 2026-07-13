package com.modspec.agent

import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 从 libxposed RemoteFile 加载规则并注册 hook（主路径）。
 * 降级：/data/local/tmp/modspec/rules（legacy）。
 */
class RuleEngine(private val module: XposedModule) {

    private var lastGeneration: Long = -1L

    fun reloadIfNeeded() {
        val prefs = module.getRemotePreferences(RemotePrefsManager.DEFAULT_GROUP)
        val generation = prefs.getLong(ModuleReloader.KEY_RULES_GENERATION, 0L)
        if (generation <= lastGeneration && Companion.compiledRules.isNotEmpty()) return

        shutdown()
        Companion.compiledRules.clear()

        val androidSdk = android.os.Build.VERSION.SDK_INT
        val loaded = loadFromRemoteFiles(androidSdk)
            || loadFromLegacySharedDir(androidSdk)

        lastGeneration = generation
        log("loaded ${Companion.compiledRules.size} rule file(s), gen=$generation, remote=$loaded")
    }

    private fun loadFromRemoteFiles(androidSdk: Int): Boolean {
        if (!hasRemoteCap()) return false
        return runCatching {
            val names = module.listRemoteFiles().filter { it.endsWith(RemoteRulesManager.RULE_SUFFIX) }
            if (names.isEmpty()) return false
            for (name in names) {
                module.openRemoteFile(name).use { pfd ->
                    val text = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        .bufferedReader().readText()
                    RuleParser.parse(text, androidSdk)?.let { Companion.compiledRules += it }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun loadFromLegacySharedDir(androidSdk: Int): Boolean {
        val rulesDir = File(AgentStorage.SHARED_RULES_DIR)
        if (!rulesDir.isDirectory) return false
        rulesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(RemoteRulesManager.RULE_SUFFIX) }
            ?.forEach { file ->
                RuleParser.parseFile(file, androidSdk)?.let { Companion.compiledRules += it }
            }
        return Companion.compiledRules.isNotEmpty()
    }

    private fun hasRemoteCap(): Boolean =
        (module.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) != 0L

    fun onPackageLoaded(param: PackageLoadedParam) {
        reloadIfNeeded()
        val pkg = param.packageName
        val classLoader = param.defaultClassLoader
        val apkPath = runCatching { param.applicationInfo.sourceDir }.getOrDefault("")

        for (rule in Companion.compiledRules) {
            if (rule.packages.isNotEmpty() && pkg !in rule.packages) continue
            for (hook in rule.hooks) {
                if (hook.phase != PHASE_PACKAGE_LOADED) continue
                registerHook(classLoader, apkPath, hook, rule.metaId)
            }
        }
    }

    fun onSystemServerStarting(param: SystemServerStartingParam) {
        reloadIfNeeded()
        val loader = param.classLoader
        for (rule in Companion.compiledRules) {
            for (hook in rule.hooks) {
                if (hook.phase != PHASE_SYSTEM_SERVER) continue
                registerHook(loader, apkPath = "", hook, rule.metaId)
            }
        }
    }

    fun shutdown() {
        Companion.activeHandles.forEach { runCatching { it.unhook() } }
        Companion.activeHandles.clear()
        lastGeneration = -1L
    }

    private fun registerHook(
        classLoader: ClassLoader,
        apkPath: String,
        hook: ParsedHook,
        ruleId: String,
    ) {
        val method = resolveMethod(classLoader, apkPath, hook) ?: return

        val handle = module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                when (hook.actionKind) {
                    "skip" -> null
                    "observe" -> chain.proceed()
                    "return_const" -> resolveConst(hook.returnConst)
                    else -> chain.proceed()
                }
            }

        Companion.activeHandles.add(handle)
        log("hooked ${method.declaringClass.name}.${method.name} ($ruleId, ${hook.resolver})")
    }

    private fun resolveMethod(
        classLoader: ClassLoader,
        apkPath: String,
        hook: ParsedHook,
    ): Method? {
        return when (hook.resolver) {
            "dexkit" -> {
                val query = hook.dexQuery ?: return null
                DexKitResolver.findMethod(apkPath, classLoader, query)
            }
            else -> findStaticMethod(
                classLoader,
                hook.className ?: return null,
                hook.methodName ?: return null,
                hook.signature,
            )
        }
    }

    private fun resolveConst(value: Any?): Any? = value

    private fun findStaticMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        signature: String?,
    ): Method? {
        val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return null
        if (signature != null) {
            val paramTypes = SignatureParser.parseParams(signature)
            return runCatching { clazz.getDeclaredMethod(methodName, *paramTypes) }.getOrNull()
                ?: clazz.methods.firstOrNull { m ->
                    m.name == methodName && m.parameterTypes.contentEquals(paramTypes)
                }
        }
        return clazz.declaredMethods.firstOrNull { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
            ?: clazz.methods.firstOrNull { it.name == methodName }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        module.log(Log.INFO, TAG, message)
    }

    companion object {
        private const val TAG = "ModspecRuleEngine"
        private const val PHASE_PACKAGE_LOADED = "on_package_loaded"
        private const val PHASE_SYSTEM_SERVER = "on_system_server_starting"

        private val compiledRules = mutableListOf<ParsedRuleFile>()
        private val activeHandles = mutableListOf<XposedInterface.HookHandle>()
    }
}

/** JNI-style descriptor → Java [Class] list. */
private object SignatureParser {
    fun parseParams(signature: String): Array<Class<*>> {
        val start = signature.indexOf('(')
        val end = signature.indexOf(')')
        if (start < 0 || end < 0) return emptyArray()
        val params = signature.substring(start + 1, end)
        val types = mutableListOf<Class<*>>()
        var i = 0
        while (i < params.length) {
            val (type, consumed) = readType(params, i)
            types += type
            i += consumed
        }
        return types.toTypedArray()
    }

    private fun readType(desc: String, offset: Int): Pair<Class<*>, Int> {
        return when (desc[offset]) {
            'Z' -> Boolean::class.javaPrimitiveType!! to 1
            'B' -> Byte::class.javaPrimitiveType!! to 1
            'C' -> Char::class.javaPrimitiveType!! to 1
            'S' -> Short::class.javaPrimitiveType!! to 1
            'I' -> Int::class.javaPrimitiveType!! to 1
            'J' -> Long::class.javaPrimitiveType!! to 1
            'F' -> Float::class.javaPrimitiveType!! to 1
            'D' -> Double::class.javaPrimitiveType!! to 1
            'V' -> Void.TYPE to 1
            'L' -> {
                val end = desc.indexOf(';', offset)
                val name = desc.substring(offset + 1, end).replace('/', '.')
                Class.forName(name) to (end - offset + 1)
            }
            '[' -> {
                val (component, consumed) = readType(desc, offset + 1)
                java.lang.reflect.Array.newInstance(component, 0).javaClass to (consumed + 1)
            }
            else -> error("bad signature at $offset in $desc")
        }
    }
}
