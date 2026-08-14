package com.modspec.agent

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.File

/** Parsed `.rule.toml` document for [RuleEngine]. */
data class ParsedRuleFile(
    val metaId: String,
    val packages: List<String>,
    val hooks: List<ParsedHook>,
    val variants: List<ParsedVariant> = emptyList(),
)

data class ParsedVariant(
    val name: String,
    val android: Int?,
    val oem: String?,
    val rom: String?,
    val hooks: List<ParsedHook>,
)

data class ParsedHook(
    val phase: String,
    val resolver: String = "static",
    val className: String? = null,
    val methodName: String? = null,
    val signature: String? = null,
    val dexQuery: DexQuery? = null,
    val actionKind: String,
    val returnConst: Any?,
)

data class DexQuery(
    val className: String?,
    val methodName: String?,
    val signature: String?,
    val unique: Boolean,
)

object RuleParser {
    fun parseFile(
        file: File,
        androidSdk: Int = android.os.Build.VERSION.SDK_INT,
        deviceOem: String? = null,
        deviceRom: String? = null,
    ): ParsedRuleFile? =
        runCatching { parse(Toml.parse(file.toPath()), androidSdk, deviceOem, deviceRom) }.getOrNull()

    fun parse(
        content: String,
        androidSdk: Int = android.os.Build.VERSION.SDK_INT,
        deviceOem: String? = null,
        deviceRom: String? = null,
    ): ParsedRuleFile =
        parse(Toml.parse(content), androidSdk, deviceOem, deviceRom)

    private fun parse(doc: TomlTable, androidSdk: Int, deviceOem: String?, deviceRom: String?): ParsedRuleFile {
        val meta = doc.getTable("meta") ?: error("missing [meta]")
        val metaId = meta.getString("id") ?: error("missing meta.id")

        val compatible = doc.getTable("compatible")
        val packages = compatible?.getArray("packages")?.toStringList().orEmpty()

        val defaultHooks = parseHooksArray(doc.getArray("hooks"))
        val variants = parseVariants(doc.getArray("variants"))
        val resolvedHooks = resolveHooks(defaultHooks, variants, androidSdk, deviceOem, deviceRom)

        return ParsedRuleFile(metaId, packages, resolvedHooks, variants)
    }

    private fun resolveHooks(
        default: List<ParsedHook>,
        variants: List<ParsedVariant>,
        androidSdk: Int,
        deviceOem: String?,
        deviceRom: String?,
    ): List<ParsedHook> {
        for (variant in variants) {
            if (variant.hooks.isEmpty()) continue
            val androidOk = variant.android == null || variant.android == androidSdk
            val oemOk = variant.oem == null ||
                (deviceOem != null && variant.oem.equals(deviceOem, ignoreCase = true))
            val romOk = variant.rom == null ||
                (deviceRom != null && variant.rom.equals(deviceRom, ignoreCase = true))
            if (androidOk && oemOk && romOk) {
                return variant.hooks
            }
        }
        return default
    }

    private fun parseVariants(array: TomlArray?): List<ParsedVariant> {
        if (array == null) return emptyList()
        return (0 until array.size()).mapNotNull { i ->
            val table = array.getTable(i) ?: return@mapNotNull null
            val whenTable = table.getTable("when")
            ParsedVariant(
                name = table.getString("name") ?: "variant-$i",
                android = whenTable?.getString("android")?.toIntOrNull(),
                oem = whenTable?.getString("oem"),
                rom = whenTable?.getString("rom"),
                hooks = parseHooksArray(table.getArray("hooks")),
            )
        }
    }

    private fun parseHooksArray(array: TomlArray?): List<ParsedHook> {
        if (array == null) return emptyList()
        return (0 until array.size()).mapNotNull { array.getTable(it)?.let(::parseHook) }
    }

    private fun parseHook(table: TomlTable): ParsedHook {
        val phase = table.getString("phase") ?: "on_package_loaded"
        val target = table.getTable("target") ?: error("hook missing target")
        val resolver = target.getString("resolver") ?: "static"

        val action = table.getTable("action") ?: error("hook missing action")
        val kind = action.getString("kind") ?: "skip"
        val returnConst = when (kind) {
            "return_const" -> parseReturnConst(action)
            else -> null
        }

        return when (resolver) {
            "dexkit" -> {
                val query = target.getTable("query")
                    ?: error("dexkit target missing query")
                ParsedHook(
                    phase = phase,
                    resolver = resolver,
                    dexQuery = DexQuery(
                        className = query.getString("class"),
                        methodName = query.getString("method"),
                        signature = query.getString("signature"),
                        unique = query.getBoolean("unique") ?: true,
                    ),
                    actionKind = kind,
                    returnConst = returnConst,
                )
            }
            else -> {
                ParsedHook(
                    phase = phase,
                    resolver = "static",
                    className = target.getString("class")
                        ?: error("static target missing class"),
                    methodName = target.getString("method")
                        ?: error("static target missing method"),
                    signature = target.getString("signature"),
                    actionKind = kind,
                    returnConst = returnConst,
                )
            }
        }
    }

    private fun parseReturnConst(action: TomlTable): Any? {
        val value = action.getTable("value") ?: return null
        return when (value.getString("type")) {
            "void" -> null
            "boolean" -> value.getBoolean("data") ?: false
            "int" -> value.getLong("data")?.toInt() ?: 0
            "long" -> value.getLong("data") ?: 0L
            "float" -> value.getDouble("data")?.toFloat() ?: 0f
            "double" -> value.getDouble("data") ?: 0.0
            "string" -> value.getString("data") ?: ""
            "null" -> null
            else -> null
        }
    }

    private fun TomlArray.toStringList(): List<String> =
        (0 until size()).mapNotNull { getString(it) }
}
