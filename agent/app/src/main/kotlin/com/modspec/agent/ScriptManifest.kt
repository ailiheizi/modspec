package com.modspec.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Parsed script manifest (`manifest.toml`). Pure JVM (tomlj), shared between
 * the agent process (validation/deploy) and the hook process (load).
 *
 * Mirrors `crates/modspec-core/src/script.rs`; both sides enforce the same
 * rules so a malformed bundle can never reach a hooked process.
 */
data class ScriptManifest(
    val scriptVersion: String,
    val metaId: String,
    val metaName: String,
    val author: String?,
    val description: String?,
    val packages: List<String>,
    val targetPackages: List<String>,
    val oem: List<String>,
    val minAndroid: Int?,
    val runtime: String,
    val entrypoint: String?,
    val executionMs: Int?,
    val callbackMs: Int?,
    val waitClassMs: Int?,
    val capabilities: List<String>,
    val fridaScript: String?,
) {
    fun hasFrida(): Boolean = fridaScript != null
    fun resolvedEntrypoint(): String =
        entrypoint ?: if (runtime == "lua") "src/main.lua" else "src/main.js"

    fun defaultExecutionMs(): Int = executionMs ?: DEFAULT_EXECUTION_MS
    fun defaultCallbackMs(): Int = callbackMs ?: DEFAULT_CALLBACK_MS
    fun defaultWaitClassMs(): Int = waitClassMs ?: DEFAULT_WAIT_CLASS_MS

    companion object {
        const val VERSION = "1"
        val ALLOWED_CAPABILITIES = setOf("emit", "log", "toast", "frida", "native_hook")
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_BUNDLE_BYTES = 4 * 1024 * 1024
        const val MAX_FILES = 64
        const val DEFAULT_EXECUTION_MS = 10_000
        const val DEFAULT_CALLBACK_MS = 50
        const val DEFAULT_WAIT_CLASS_MS = 15_000
    }
}

/** One bundled source file. */
data class ScriptFile(val name: String, val content: String)

/** Parses and validates `manifest.toml` into a [ScriptManifest]. */
object ScriptManifestParser {
    fun parse(content: String): ScriptManifest {
        val doc = org.tomlj.Toml.parse(content)
        val errors = doc.errors()
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("invalid manifest TOML: ${errors.joinToString("; ") { it.message ?: "parse error" }}")
        }
        val meta = doc.getTable("meta")
            ?: throw IllegalArgumentException("missing [meta] section")
        val compatible = doc.getTable("compatible")
        val engine = doc.getTable("engine")
        val limits = doc.getTable("limits")
        val permissions = doc.getTable("permissions")
        val frida = doc.getTable("frida")
        return ScriptManifest(
            scriptVersion = doc.getString("script_version") ?: "",
            metaId = meta.getString("id") ?: "",
            metaName = meta.getString("name") ?: "",
            author = meta.getString("author"),
            description = meta.getString("description"),
            packages = compatible?.getArray("packages")?.toList()
                ?.mapNotNull { it as? String } ?: emptyList(),
            targetPackages = compatible?.getArray("target_packages")?.toList()
                ?.mapNotNull { it as? String } ?: emptyList(),
            oem = compatible?.getArray("oem")?.toList()
                ?.mapNotNull { it as? String } ?: emptyList(),
            minAndroid = compatible?.getLong("min_android")?.toInt(),
            runtime = engine?.getString("runtime") ?: "js",
            entrypoint = engine?.getString("entrypoint"),
            executionMs = limits?.getLong("execution_ms")?.toInt(),
            callbackMs = limits?.getLong("callback_ms")?.toInt(),
            waitClassMs = limits?.getLong("wait_class_ms")?.toInt(),
            capabilities = permissions?.getArray("capabilities")?.toList()
                ?.mapNotNull { it as? String } ?: emptyList(),
            fridaScript = frida?.getString("script"),
        )
    }
}

/**
 * Deterministic bundle validation shared by the agent's `script_validate` /
 * `script_deploy` RPCs and the hook-process loader. Returns an empty list when
 * the bundle is acceptable.
 */
object ScriptBundleValidator {
    fun validate(manifest: ScriptManifest, manifestRaw: String, files: List<ScriptFile>): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.scriptVersion != ScriptManifest.VERSION) {
            errors += "unsupported script_version: ${manifest.scriptVersion} (expected ${ScriptManifest.VERSION})"
        }
        if (manifest.metaId.isBlank()) errors += "script meta.id is required"
        if (!validScriptId(manifest.metaId)) errors += "invalid script id: ${manifest.metaId}"
        if (manifest.metaName.isBlank()) errors += "script meta.name is required"
        if (manifest.runtime !in setOf("js", "lua")) {
            errors += "unsupported engine runtime: ${manifest.runtime} (expected js|lua)"
        }
        for (packageName in manifest.packages) {
            if (!validPackageName(packageName)) errors += "invalid compatible package: $packageName"
        }
        for (packageName in manifest.targetPackages) {
            if (!validPackageName(packageName)) errors += "invalid target package: $packageName"
        }
        for (oem in manifest.oem) {
            if (oem.isBlank() || !oem.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                errors += "invalid oem: $oem"
            }
        }

        if (manifestRaw.toByteArray().size > ScriptManifest.MAX_MANIFEST_BYTES) {
            errors += "manifest exceeds ${ScriptManifest.MAX_MANIFEST_BYTES} bytes"
        }
        if (files.isEmpty()) errors += "script must bundle at least one source file"
        if (files.size > ScriptManifest.MAX_FILES) errors += "script bundles more than ${ScriptManifest.MAX_FILES} files"

        val seen = HashSet<String>()
        var total = manifestRaw.toByteArray().size
        for (file in files) {
            if (!validFileName(file.name)) errors += "invalid file name in bundle: ${file.name}"
            if (!seen.add(file.name)) errors += "duplicate file in bundle: ${file.name}"
            if (file.content.toByteArray().size > ScriptManifest.MAX_FILE_BYTES) {
                errors += "file ${file.name} exceeds ${ScriptManifest.MAX_FILE_BYTES} bytes"
            }
            total += file.content.toByteArray().size
        }
        if (total > ScriptManifest.MAX_BUNDLE_BYTES) {
            errors += "script bundle exceeds ${ScriptManifest.MAX_BUNDLE_BYTES} bytes"
        }
        val entrypoint = manifest.resolvedEntrypoint()
        if (entrypoint !in seen) errors += "entrypoint not found in bundle: $entrypoint"

        for (capability in manifest.capabilities) {
            if (capability !in ScriptManifest.ALLOWED_CAPABILITIES) {
                errors += "undeclared capability not allowed: $capability (allowed: ${ScriptManifest.ALLOWED_CAPABILITIES.joinToString(", ")})"
            }
        }
        if ("frida" in manifest.capabilities) {
            val fridaScript = manifest.fridaScript
            if (fridaScript == null) {
                errors += "capability `frida` requires a [frida] section with `script`"
            } else {
                if (!validFileName(fridaScript)) errors += "invalid frida script path: $fridaScript"
                if (fridaScript !in seen) errors += "frida script not found in bundle: $fridaScript"
            }
        }
        return errors
    }

    fun validScriptId(value: String): Boolean =
        value.isNotBlank() && value.split('/').all { segment ->
            segment.isNotEmpty() &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }

    fun validPackageName(value: String): Boolean {
        if (value == "system" || value == "android") return true
        val segments = value.split('.')
        return segments.size >= 2 && segments.all { segment ->
            segment.isNotEmpty() && segment.all { it.isLetterOrDigit() || it == '_' }
        }
    }

    fun validFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 256) return false
        if (name.startsWith('/') || name.contains('\\') || name.contains('\u0000')) return false
        return name.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }
    }
}

/** Deterministic content hash (SHA-256 hex) over manifest + sorted files. */
object ScriptHash {
    fun of(manifestRaw: String, files: List<ScriptFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("manifest=".toByteArray())
        digest.update(manifestRaw.toByteArray())
        digest.update('\n'.code.toByte())
        val sorted = files.sortedBy { it.name }
        for (file in sorted) {
            digest.update("file=".toByteArray())
            digest.update(file.name.toByteArray())
            digest.update('='.code.toByte())
            digest.update(file.content.toByteArray())
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Deterministic zip codec for script bundles: fixed entry order, no
 * timestamps, no compression variability — the zip bytes are stable for a
 * given bundle (plus the stored hash is checked independently on load).
 */
object ScriptZip {
    fun encode(manifestRaw: String, files: List<ScriptFile>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            put(zip, "manifest.toml", manifestRaw.toByteArray())
            for (file in files.sortedBy { it.name }) {
                put(zip, file.name, file.content.toByteArray())
            }
        }
        return out.toByteArray()
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Decode a zip into (manifestRaw, files). Throws on malformed content. */
    fun decode(bytes: ByteArray): Pair<String, List<ScriptFile>> {
        var manifestRaw: String? = null
        val files = mutableListOf<ScriptFile>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val content = zip.readBytes().toString(Charsets.UTF_8)
                if (entry.name == "manifest.toml") {
                    manifestRaw = content
                } else if (!entry.isDirectory) {
                    files += ScriptFile(entry.name, content)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val raw = manifestRaw
            ?: throw IllegalArgumentException("bundle has no manifest.toml")
        return raw to files
    }
}
