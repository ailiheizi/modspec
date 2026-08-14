package com.modspec.agent

import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Script engine running inside a hooked process (one instance per process,
 * companion state shared across module instances like [RuleEngine]).
 *
 * Lifecycle: a generation bump or active-script change in remote preferences
 * (written by the agent's [ScriptManager]) is picked up here; the bundle is
 * loaded from a RemoteFile zip (`scripts/<id>.zip`, with a root fallback
 * under `/data/local/tmp/modspec/scripts`) and the entrypoint runs on a
 * dedicated executor thread with a manifest execution budget.
 *
 * The entrypoint blocks freely (it may call `waitForClass`), then registers
 * hooks through the shared [HookRegistry]; callbacks then fire on the target
 * app's calling threads under the registry's protective execution + circuit
 * breaker.
 */
class ScriptEngine(private val module: XposedModule) {

    private var lastGeneration: Long = -1L
    private var loadedScriptId: String? = null
    private var activeBundle: LoadedScriptBundle? = null
    private var session: ScriptSession? = null

    @Synchronized
    fun onPackageLoaded(param: PackageLoadedParam) {
        reloadIfNeeded()
        val bundle = activeBundle ?: return
        if (param.packageName !in bundle.manifest.packages) return
        val active = session
        if (active != null && active.scriptId == bundle.manifest.metaId &&
            active.processPackage == param.packageName
        ) {
            return
        }
        disposeSession()
        // The Frida gadget is dlopen'd only in the script's own hook process,
        // never in system_server or unrelated scoped processes: gadget startup
        // (listen-mode conflicts in particular) is fatal and must be contained.
        if (isSafeGadgetHost(bundle, param.packageName)) {
            loadFridaCompanion(bundle)
        }
        session = ScriptSession(module, bundle, param, lastGeneration.coerceAtLeast(0L))
        session?.start()
    }

    /**
     * The gadget may only start in the script's declared hook process. System
     * processes (`system`/`android`/`system_server`) are always excluded —
     * a gadget crash there soft-reboots the device.
     */
    private fun isSafeGadgetHost(bundle: LoadedScriptBundle, packageName: String): Boolean {
        if (packageName in SAFE_PROCESSES) return false
        return "frida" in bundle.manifest.capabilities &&
            packageName in bundle.manifest.packages
    }

    @Synchronized
    fun reloadIfNeeded() {
        val prefs = module.getRemotePreferences(RemotePrefsManager.DEFAULT_GROUP)
        val generation = prefs.getLong(ScriptManager.KEY_SCRIPTS_GENERATION, 0L)
        val activeId = prefs.getString(ScriptManager.KEY_ACTIVE_SCRIPT, null)
        if (generation <= lastGeneration && activeId == loadedScriptId && activeBundle != null) {
            return
        }
        shutdown()
        lastGeneration = generation
        loadedScriptId = activeId
        if (activeId.isNullOrBlank()) return
        val bundle = loadBundle(activeId)
        if (bundle == null) {
            scriptError(activeId, generation, "failed to load script bundle $activeId")
            activeBundle = null
        } else {
            activeBundle = bundle
        }
    }

    /**
     * When the active script declares the `frida` capability, dlopen the
     * on-demand gadget so it auto-executes the deployed frida.js (config
     * written by the agent). Best-effort: a missing gadget degrades to a
     * script_message instead of failing the Java-side script.
     */
    private fun loadFridaCompanion(bundle: LoadedScriptBundle) {
        val manifest = bundle.manifest
        if ("frida" !in manifest.capabilities || manifest.fridaScript == null) return
        val gadget = FridaGadget.gadgetPath()
        // NOTE: do not pre-check existence via File.isFile — SELinux denies
        // directory search on /data/local/tmp for system_app domains (while
        // untrusted_app is allowed), so the check itself would fail. dlopen
        // handles absence gracefully.
        val loaded = runCatching { System.load(gadget) }.isSuccess
        notifier().scriptEvent(
            manifest.metaId,
            lastGeneration,
            if (loaded) "frida_ready" else "script_message",
            if (loaded) "frida gadget loaded: $gadget" else "frida gadget not available: $gadget",
            manifest.packages.firstOrNull(),
        )
    }

    private fun notifier(): AndroidScriptNotifier = AndroidScriptNotifier(module)

    @Synchronized
    fun shutdown() {
        disposeSession()
        activeBundle = null
        lastGeneration = -1L
    }

    private fun disposeSession() {
        session?.let {
            runCatching { it.dispose() }
            session = null
        }
    }

    /** Load and validate the active script bundle from RemoteFiles (or legacy dir). */
    private fun loadBundle(scriptId: String): LoadedScriptBundle? {
        val remoteName = ScriptManager.remoteFileName(scriptId) ?: return null
        return runCatching {
            if ((module.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) != 0L) {
                module.openRemoteFile(remoteName).use { pfd ->
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
                    decodeBundle(bytes, scriptId)
                }
            } else {
                val legacy = File(
                    ScriptManager.LEGACY_SCRIPTS_DIR,
                    remoteName,
                )
                if (legacy.isFile) decodeBundle(legacy.readBytes(), scriptId) else null
            }
        }.getOrNull()
    }

    private fun decodeBundle(bytes: ByteArray, scriptId: String): LoadedScriptBundle? {
        val (manifestRaw, files) = runCatching { ScriptZip.decode(bytes) }.getOrNull() ?: return null
        val manifest = runCatching { ScriptManifestParser.parse(manifestRaw) }.getOrNull() ?: return null
        if (manifest.metaId != scriptId) return null
        val errors = ScriptBundleValidator.validate(manifest, manifestRaw, files)
        if (errors.isNotEmpty()) {
            scriptError(scriptId, lastGeneration, "bundle rejected: ${errors.first()}")
            return null
        }
        return LoadedScriptBundle(manifest, manifestRaw, files)
    }

    private fun scriptError(scriptId: String, generation: Long, message: String) {
        val payload = "{\"event\":\"script_error\",\"message\":${jsonQuote(message)}," +
            "\"script_id\":${jsonQuote(scriptId)},\"generation\":$generation}"
        Log.e(AndroidScriptNotifier.TAG, payload)
        module.log(Log.ERROR, AndroidScriptNotifier.TAG, payload)
    }

    private fun jsonQuote(value: String): String = AndroidScriptNotifier.jsonQuoteFor(value)

    companion object {
        /** Processes where the Frida gadget must never start (fatal on crash). */
        val SAFE_PROCESSES = setOf("system", "android", "system_server")
    }
}

/** A validated bundle ready for a [ScriptSession]. */
data class LoadedScriptBundle(
    val manifest: ScriptManifest,
    val manifestRaw: String,
    val files: List<ScriptFile>,
)

/**
 * One running script instance in this process: registry + host + runtime,
 * with the entrypoint executed on a dedicated thread under the manifest
 * execution budget.
 */
class ScriptSession(
    private val module: XposedModule,
    private val bundle: LoadedScriptBundle,
    param: PackageLoadedParam,
    generation: Long,
) {
    val scriptId: String = bundle.manifest.metaId
    val processPackage: String = param.packageName
    private val eventGeneration: Long = generation

    private val notifier = AndroidScriptNotifier(module)
    private val installer = XposedHookInstaller(module)
    private val registry = HookRegistry(
        installHook = { executable, dispatcher -> installer.install(executable, dispatcher) },
        events = object : HookRegistry.HookEvents {
            override fun onCallbackError(scriptId: String, hookId: String, error: Throwable) {
                android.util.Log.e(
                    AndroidScriptNotifier.TAG,
                    "hook $hookId failed: ${error.javaClass.name}: ${error.message}",
                    error,
                )
                notifier.scriptEvent(
                    scriptId,
                    eventGeneration,
                    "script_error",
                    "hook $hookId failed: ${error.javaClass.name}: ${error.message}",
                    processPackage,
                )
            }

            override fun onCallbackSlow(scriptId: String, hookId: String, elapsedMs: Long) {
                notifier.scriptEvent(
                    scriptId,
                    eventGeneration,
                    "script_error",
                    "hook $hookId took ${elapsedMs}ms (budget ${manifest.callbackMs})",
                    processPackage,
                )
            }

            override fun onReplaceSuperseded(scriptId: String, hookId: String) {
                notifier.scriptEvent(
                    scriptId,
                    eventGeneration,
                    "script_message",
                    "hook $hookId superseded by a newer replace on the same method",
                    processPackage,
                )
            }

            override fun onCircuitOpen(scriptId: String, consecutiveFailures: Int) {
                notifier.scriptEvent(
                    scriptId,
                    eventGeneration,
                    "script_error",
                    "circuit open after $consecutiveFailures consecutive failures",
                    processPackage,
                )
            }
        },
    )

    private val manifest: ScriptManifest = bundle.manifest
    private val limits: ScriptLimitsView = ScriptLimitsView.fromManifest(manifest)
    private val host: ScriptHost = ScriptHost(
        scriptId = manifest.metaId,
        scriptName = manifest.metaName,
        engine = EngineKind.fromName(manifest.runtime) ?: EngineKind.JS,
        generation = eventGeneration,
        processPackage = processPackage,
        targetPackages = manifest.targetPackages,
        classLoader = param.defaultClassLoader,
        apkPath = runCatching { param.applicationInfo.sourceDir }.getOrDefault(""),
        registry = registry,
        notifier = notifier,
        dexQueries = DexKitScriptQueries(
            moduleApkPath = runCatching { module.getModuleApplicationInfo().sourceDir }
                .getOrNull(),
        ),
        limits = limits,
    )
    private val bridge = ScriptBridgeImpl(host)
    private val runtime: ScriptRuntime =
        if (manifest.runtime == "lua") LuaRuntime(bridge) else RhinoRuntime(bridge)

    private val started = AtomicBoolean(false)
    private var entryThread: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val source = bundle.files.firstOrNull { it.name == manifest.resolvedEntrypoint() }
        if (source == null) {
            host.scriptError("entrypoint missing: ${manifest.resolvedEntrypoint()}")
            return
        }
        val compileErrors = runtime.compile(source.content, source.name)
        if (compileErrors.isNotEmpty()) {
            host.scriptError("compile failed: ${compileErrors.joinToString("; ")}")
            return
        }
        val deadline = System.currentTimeMillis() + limits.executionMs
        entryThread = Thread({
            val error = runtime.run(source.content, source.name, deadline)
            if (error != null) {
                host.scriptError("entrypoint failed: $error")
            }
        }, "modspec-script-${manifest.metaId}").also { it.isDaemon = true; it.start() }
        notifier.scriptEvent(
            scriptId,
            eventGeneration,
            "script_loaded",
            "engine=${manifest.runtime} entrypoint=${source.name}",
            processPackage,
        )
    }

    fun dispose() {
        val thread = entryThread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(2000L) }
        }
        host.dispose()
        runCatching { runtime.dispose() }
    }
}
