package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [RuleParser] variant resolution (android/oem/rom `when`
 * matching, first-match-wins, default fallback). `androidSdk` is always passed
 * explicitly so the lazy `android.os.Build.VERSION.SDK_INT` default argument is
 * never evaluated on the JVM.
 */
class RuleParserTest {

    private fun ruleWithVariants(vararg variantBlocks: String): String = buildString {
        appendLine("[meta]")
        appendLine("""id = "com.example.test.rule"""")
        appendLine("[compatible]")
        appendLine("""packages = ["com.example.app"]""")
        appendLine()
        appendLine("[[hooks]]")
        appendLine("phase = \"on_package_loaded\"")
        appendLine("[hooks.target]")
        appendLine("class = \"com.example.app.Default\"")
        appendLine("method = \"onCreate\"")
        appendLine("[hooks.action]")
        appendLine("kind = \"skip\"")
        variantBlocks.forEach {
            appendLine()
            appendLine(it)
        }
    }

    private fun variant(
        name: String,
        whenBlock: String = "",
        hookClass: String = "com.example.app.Variant",
    ): String = buildString {
        appendLine("[[variants]]")
        appendLine("""name = "$name"""")
        if (whenBlock.isNotBlank()) appendLine(whenBlock)
        appendLine("[[variants.hooks]]")
        appendLine("phase = \"on_package_loaded\"")
        appendLine("[variants.hooks.target]")
        appendLine("class = \"$hookClass\"")
        appendLine("method = \"onCreate\"")
        appendLine("[variants.hooks.action]")
        appendLine("kind = \"return_const\"")
        appendLine("[variants.hooks.action.value]")
        appendLine("type = \"int\"")
        appendLine("data = 7")
    }

    private fun hookClass(parsed: ParsedRuleFile): String = parsed.hooks.single().className!!

    // (a) android-only variant matches with the matching sdk -------------------

    @Test
    fun android_variant_matches_when_sdk_matches() {
        val rule = ruleWithVariants(
            variant("a33", "[variants.when]\nandroid = \"33\"", hookClass = "com.example.app.V33"),
        )
        val parsed = RuleParser.parse(rule, androidSdk = 33)
        assertEquals("com.example.app.V33", hookClass(parsed))
        assertEquals(33, parsed.variants.single().android)
    }

    // (b) oem variant matches case-insensitively ------------------------------

    @Test
    fun oem_variant_matches_case_insensitively() {
        val rule = ruleWithVariants(
            variant("samsung", "[variants.when]\noem = \"Samsung\""),
        )
        val parsed = RuleParser.parse(rule, androidSdk = 33, deviceOem = "samsung")
        assertEquals("com.example.app.Variant", hookClass(parsed))
    }

    @Test
    fun oem_and_android_conditions_all_required() {
        val rule = ruleWithVariants(
            variant("s33", "[variants.when]\noem = \"Samsung\"\nandroid = \"33\""),
        )
        assertEquals(
            "com.example.app.Variant",
            hookClass(RuleParser.parse(rule, androidSdk = 33, deviceOem = "SAMSUNG")),
        )
        assertEquals(
            "com.example.app.Default",
            hookClass(RuleParser.parse(rule, androidSdk = 34, deviceOem = "Samsung")),
        )
    }

    // (c) oem variant does not match when deviceOem is null --------------------

    @Test
    fun oem_variant_is_skipped_when_device_oem_unknown() {
        val rule = ruleWithVariants(
            variant("samsung", "[variants.when]\noem = \"Samsung\""),
        )
        val parsed = RuleParser.parse(rule, androidSdk = 33)
        assertEquals("com.example.app.Default", hookClass(parsed))
    }

    // (d) rom variant matches only when both rom and sdk match ----------------

    @Test
    fun rom_variant_matches_when_rom_matches() {
        val rule = ruleWithVariants(
            variant("miui", "[variants.when]\nrom = \"MIUI\"\nandroid = \"33\""),
        )
        assertEquals(
            "com.example.app.Variant",
            hookClass(RuleParser.parse(rule, androidSdk = 33, deviceRom = "miui")),
        )
        assertEquals(
            "com.example.app.Default",
            hookClass(RuleParser.parse(rule, androidSdk = 33, deviceRom = "coloros")),
        )
        assertEquals(
            "com.example.app.Default",
            hookClass(RuleParser.parse(rule, androidSdk = 33)),
        )
    }

    // (e) no match falls back to default hooks --------------------------------

    @Test
    fun no_matching_variant_falls_back_to_default_hooks() {
        val rule = ruleWithVariants(
            variant("a33", "[variants.when]\nandroid = \"33\""),
        )
        assertEquals("com.example.app.Default", hookClass(RuleParser.parse(rule, androidSdk = 34)))
    }

    @Test
    fun empty_variants_uses_default_hooks() {
        val parsed = RuleParser.parse(ruleWithVariants(), androidSdk = 33)
        assertEquals("com.example.app.Default", hookClass(parsed))
    }

    // (f) first matching variant wins ------------------------------------------

    @Test
    fun first_matching_variant_wins() {
        val rule = ruleWithVariants(
            variant("first", "[variants.when]\nandroid = \"33\"", hookClass = "com.example.app.First"),
            variant("second", "[variants.when]\nandroid = \"33\"", hookClass = "com.example.app.Second"),
        )
        assertEquals("com.example.app.First", hookClass(RuleParser.parse(rule, androidSdk = 33)))
    }

    @Test
    fun first_matching_variant_wins_with_rom_tie_break() {
        val rule = ruleWithVariants(
            variant("generic", "[variants.when]\nrom = \"AOSP\"", hookClass = "com.example.app.Generic"),
            variant("miui", "[variants.when]\nrom = \"MIUI\"", hookClass = "com.example.app.Miui"),
        )
        assertEquals("com.example.app.Miui", hookClass(RuleParser.parse(rule, androidSdk = 33, deviceRom = "MIUI")))
    }

    @Test
    fun parsed_variant_keeps_oem_and_rom_fields() {
        val rule = ruleWithVariants(
            variant("s33", "[variants.when]\noem = \"Samsung\"\nrom = \"OneUI\""),
        )
        val parsed = RuleParser.parse(rule, androidSdk = 33)
        val parsedVariant = parsed.variants.single()
        assertEquals("Samsung", parsedVariant.oem)
        assertEquals("OneUI", parsedVariant.rom)
        assertNull(parsedVariant.android)
    }
}
