package com.modspec.agent

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher

/**
 * Bridges [HookRegistry] to the libxposed API 102 builder: one
 * `module.hook(executable)` per method, `PROTECTIVE` exception mode, and the
 * [XposedInterface.Chain] → [HookRegistry.HookChain] mapping.
 */
class XposedHookInstaller(private val module: XposedModule) {

    fun install(
        executable: Executable,
        dispatcher: (HookRegistry.HookChain) -> Any?,
    ): HookRegistry.InstalledHook {
        val handle = module.hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> dispatcher(ChainAdapter(chain)) }
        return InstalledXposedHook(handle)
    }

    private class ChainAdapter(
        private val chain: XposedInterface.Chain,
    ) : HookRegistry.HookChain {
        override val executable: Executable get() = chain.executable
        override val receiver: Any? get() = chain.thisObject
        override val args: MutableList<Any?> get() = chain.args
        override fun proceed(): Any? = chain.proceed()
        override fun proceedWith(args: List<Any?>): Any? = chain.proceed(args.toTypedArray())
    }

    private class InstalledXposedHook(
        private val handle: XposedInterface.HookHandle,
    ) : HookRegistry.InstalledHook {
        override fun unhook() {
            runCatching { handle.unhook() }
        }
    }
}

/**
 * Android [ScriptNotifier]: writes structured JSON events to the LSPosed log /
 * logcat via `module.log` — the only hook→agent channel available in API 102
 * (ingested by [LogTailReader] into [EventJournal]).
 */
class AndroidScriptNotifier(private val module: XposedModule) : ScriptNotifier {

    override fun scriptEvent(
        scriptId: String,
        generation: Long,
        event: String,
        message: String,
        packageName: String?,
    ) {
        val payload = buildString {
            append("{\"event\":").append(jsonQuote(event))
            append(",\"message\":").append(jsonQuote(message))
            append(",\"script_id\":").append(jsonQuote(scriptId))
            append(",\"generation\":").append(generation)
            if (packageName != null) {
                append(",\"package\":").append(jsonQuote(packageName))
            }
            append('}')
        }
        Log.i(TAG, payload)
        module.log(Log.INFO, TAG, payload)
    }

    override fun hookHit(
        scriptId: String,
        generation: Long,
        method: String,
        packageName: String,
    ) {
        scriptEvent(
            scriptId,
            generation,
            "script_hit",
            "hook fired: $method",
            packageName,
        )
    }

    private fun jsonQuote(value: String): String = jsonQuoteFor(value)

    companion object {
        const val TAG = "ModspecScript"

        fun jsonQuoteFor(value: String): String = buildString {
            append('"')
            for (char in value) {
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                    }
                }
            }
            append('"')
        }
    }
}

/**
 * DexKit-backed [ScriptDexQueries] for scripts: exact/partial strings, class
 * and method names, parameter and return constraints. Ambiguous or empty
 * lookups return null (the host turns them into deterministic `script_error`
 * diagnostics) instead of being swallowed silently.
 *
 * DexKit is native; inside a hooked process the module APK's libs are not on
 * the host's native library path, so `System.loadLibrary("dexkit")` fails
 * there. [ensureNative] falls back to extracting `libdexkit.so` from the
 * module APK (via [XposedInterface.getModuleApplicationInfo]) into
 * `/data/local/tmp/modspec/lib/` (root) and loading it by absolute path.
 */
class DexKitScriptQueries(
    private val moduleApkPath: String?,
) : ScriptDexQueries {

    @Volatile
    private var nativeReady = false

    /** Load the DexKit native library (module classloader, then system lib dir). */
    private fun ensureNative() {
        if (nativeReady) return
        runCatching { System.loadLibrary("dexkit") }
        if (!isNativeReady()) {
            // The agent (root) deploys the so next to the module APK
            // (`apk_data_file` label — readable/executable by every process).
            val shared = moduleApkPath?.let { DexKitLib.sharedLibPath(it) }
            if (shared != null) {
                try {
                    System.load(shared)
                } catch (error: Throwable) {
                    Log.e(
                        AndroidScriptNotifier.TAG,
                        "dexkit: System.load($shared) failed: ${error.javaClass.name}: ${error.message}",
                    )
                }
            }
        }
        nativeReady = isNativeReady()
        if (!nativeReady) {
            Log.e(
                AndroidScriptNotifier.TAG,
                "dexkit native library unavailable (loadLibrary failed; module apk=$moduleApkPath)",
            )
        }
    }

    private fun isNativeReady(): Boolean = try {
        DexKitBridge.create(arrayOf<ByteArray>()).use { true }
    } catch (_: Throwable) {
        false
    }

    override fun findClass(
        apkPath: String,
        classLoader: ClassLoader,
        pkg: String?,
        usingStrings: List<String>?,
        methodName: String?,
    ): Class<*>? {
        if (apkPath.isBlank()) return null
        return runCatching {
            ensureNative()
            DexKitBridge.create(apkPath).use { bridge ->
                val matcher = ClassMatcher()
                usingStrings?.forEach { matcher.addUsingString(it) }
                methodName?.let { matcher.addMethod(MethodMatcher().name(it)) }
                val query = FindClass.create()
                if (!pkg.isNullOrBlank()) query.searchPackages(pkg)
                val classes = bridge.findClass(query.matcher(matcher))
                when (classes.size) {
                    1 -> classes.first().getInstance(classLoader)
                    else -> null
                }
            }
        }.onFailure { error ->
            Log.e(AndroidScriptNotifier.TAG, "dexkit findClass failed (pkg=$pkg strings=${usingStrings ?: "*"} method=$methodName): ${error.javaClass.name}: ${error.message}")
        }.getOrNull()
    }

    override fun findMethod(
        apkPath: String,
        classLoader: ClassLoader,
        clazz: String?,
        methodName: String?,
        paramTypes: List<String>?,
        returnType: String?,
        usingStrings: List<String>?,
    ): Method? {
        if (apkPath.isBlank()) return null
        return runCatching {
            ensureNative()
            DexKitBridge.create(apkPath).use { bridge ->
                val matcher = MethodMatcher()
                if (!clazz.isNullOrBlank()) matcher.declaredClass(clazz, StringMatchType.Equals)
                if (!methodName.isNullOrBlank()) matcher.name(methodName)
                paramTypes?.forEach { matcher.addParamType(it) }
                if (!returnType.isNullOrBlank()) matcher.returnType(returnType)
                usingStrings?.forEach { matcher.addUsingString(it) }
                val methods = bridge.findMethod(FindMethod.create().matcher(matcher))
                when (methods.size) {
                    1 -> methods.first().getMethodInstance(classLoader)
                    else -> null
                }
            }
        }.onFailure { error ->
            Log.e(AndroidScriptNotifier.TAG, "dexkit findMethod failed (clazz=$clazz method=$methodName params=$paramTypes): ${error.javaClass.name}: ${error.message}")
        }.getOrNull()
    }
}
