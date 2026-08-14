package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM tests for script manifest parsing, bundle validation, zip and state. */
class ScriptManifestTest {

    private val validManifest = """
        script_version = "1"
        [meta]
        id = "xiaomi/security-center/macro-gate"
        name = "Macro gate"
        author = "test"
        [compatible]
        packages = ["com.miui.securitycenter"]
        target_packages = ["com.ChillyRoom.DungeonShooter"]
        [engine]
        runtime = "js"
        [limits]
        execution_ms = 8000
        [permissions]
        capabilities = ["emit", "log"]
    """.trimIndent()

    private fun parse(content: String): ScriptManifest = ScriptManifestParser.parse(content)

    @Test
    fun parses_full_manifest() {
        val manifest = parse(validManifest)
        assertEquals("1", manifest.scriptVersion)
        assertEquals("xiaomi/security-center/macro-gate", manifest.metaId)
        assertEquals(listOf("com.miui.securitycenter"), manifest.packages)
        assertEquals(listOf("com.ChillyRoom.DungeonShooter"), manifest.targetPackages)
        assertEquals("js", manifest.runtime)
        assertEquals(8000, manifest.defaultExecutionMs())
        assertEquals(listOf("emit", "log"), manifest.capabilities)
    }

    @Test
    fun default_entrypoint_depends_on_runtime() {
        assertEquals("src/main.js", parse(validManifest).resolvedEntrypoint())
        val lua = validManifest.replace("runtime = \"js\"", "runtime = \"lua\"")
        assertEquals("src/main.lua", parse(lua).resolvedEntrypoint())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalid_toml_is_rejected() {
        parse("not [valid toml {{{")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missing_meta_is_rejected() {
        parse("script_version = \"1\"")
    }

    @Test
    fun valid_bundle_passes_validation() {
        val manifest = parse(validManifest)
        val errors = ScriptBundleValidator.validate(
            manifest,
            validManifest,
            listOf(ScriptFile("src/main.js", "modspec.log('hi');")),
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun missing_entrypoint_is_rejected() {
        val errors = ScriptBundleValidator.validate(
            parse(validManifest),
            validManifest,
            listOf(ScriptFile("src/other.js", "x")),
        )
        assertTrue(errors.any { it.contains("entrypoint not found") })
    }

    @Test
    fun unknown_capability_is_rejected() {
        val manifest = validManifest.replace(
            "capabilities = [\"emit\", \"log\"]",
            "capabilities = [\"shell\"]",
        )
        val errors = ScriptBundleValidator.validate(
            parse(manifest),
            manifest,
            listOf(ScriptFile("src/main.js", "x")),
        )
        assertTrue(errors.any { it.contains("undeclared capability") })
    }

    @Test
    fun path_traversal_is_rejected() {
        val errors = ScriptBundleValidator.validate(
            parse(validManifest),
            validManifest,
            listOf(
                ScriptFile("src/main.js", "x"),
                ScriptFile("../evil.js", "y"),
            ),
        )
        assertTrue(errors.any { it.contains("invalid file name") })
    }

    @Test
    fun oversized_file_is_rejected() {
        val errors = ScriptBundleValidator.validate(
            parse(validManifest),
            validManifest,
            listOf(ScriptFile("src/main.js", "x".repeat(ScriptManifest.MAX_FILE_BYTES + 1))),
        )
        assertTrue(errors.any { it.contains("exceeds") })
    }

    @Test
    fun unsupported_runtime_is_rejected() {
        val manifest = validManifest.replace("runtime = \"js\"", "runtime = \"python\"")
        val errors = ScriptBundleValidator.validate(
            parse(manifest),
            manifest,
            listOf(ScriptFile("src/main.py", "print(1)")),
        )
        assertTrue(errors.any { it.contains("unsupported engine runtime") })
    }

    @Test
    fun zip_round_trip_is_deterministic() {
        val files = listOf(
            ScriptFile("src/main.js", "modspec.log('a');"),
            ScriptFile("src/lib.js", "var x = 1;"),
        )
        val first = ScriptZip.encode(validManifest, files)
        val second = ScriptZip.encode(validManifest, files.reversed())
        assertTrue(first.contentEquals(second))

        val (manifestRaw, decoded) = ScriptZip.decode(first)
        assertEquals(validManifest.trimIndent(), manifestRaw.trimIndent())
        assertEquals(files.map { it.name }.sorted(), decoded.map { it.name }.sorted())
        assertEquals("modspec.log('a');", decoded.first { it.name == "src/main.js" }.content)
    }

    @Test
    fun content_hash_is_deterministic_and_sensitive() {
        val files = listOf(ScriptFile("src/main.js", "x"))
        val hash = ScriptHash.of(validManifest, files)
        assertEquals(hash, ScriptHash.of(validManifest, files))
        assertFalse(hash == ScriptHash.of(validManifest, listOf(ScriptFile("src/main.js", "y"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zip_without_manifest_is_rejected() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("src/main.js")).also {
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        ScriptZip.decode(bytes.toByteArray())
    }

    @Test
    fun state_store_round_trips() {
        val dir = Files.createTempDirectory("modspec-scripts").toFile()
        try {
            ScriptStateStore.setActive(dir, "xiaomi/security-center/macro-gate")
            ScriptStateStore.setRecord(
                dir,
                ScriptStateStore.ScriptRecord(
                    scriptId = "xiaomi/security-center/macro-gate",
                    hash = "aabbcc",
                    generation = 42L,
                    engine = "js",
                    packages = listOf("com.miui.securitycenter"),
                    targetPackages = listOf("com.ChillyRoom.DungeonShooter"),
                ),
            )
            ScriptStateStore.recordEvent(dir, "xiaomi/security-center/macro-gate", "script_loaded", "ok")
            ScriptStateStore.recordEvent(dir, "xiaomi/security-center/macro-gate", "script_hit", "hit")
            ScriptStateStore.recordEvent(dir, "xiaomi/security-center/macro-gate", "script_error", "boom")

            val (active, records) = ScriptStateStore.read(dir)
            assertEquals("xiaomi/security-center/macro-gate", active)
            val record = records.getValue("xiaomi/security-center/macro-gate")
            assertEquals("aabbcc", record.hash)
            assertEquals(42L, record.generation)
            assertEquals(1L, record.hitCount)
            assertEquals(1L, record.errorCount)
            assertEquals("boom", record.lastError)
            assertNull(record.lastLoadedMs?.let { null })

            ScriptStateStore.removeRecord(dir, "xiaomi/security-center/macro-gate")
            val (afterActive, afterRecords) = ScriptStateStore.read(dir)
            assertNull(afterActive)
            assertTrue(afterRecords.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
