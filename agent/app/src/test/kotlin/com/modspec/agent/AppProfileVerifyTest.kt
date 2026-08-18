package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VerifyEvaluator] drift computation backing the `verify` RPC.
 * The evaluator works on plain Kotlin snapshots (no org.json — the mockable
 * android.jar stubs org.json in unit tests), so these tests feed it synthetic
 * state/profile data directly, mirroring InspectorParsingTest's pattern.
 */
class AppProfileVerifyTest {

    private fun state(
        activeProfile: String? = "prod",
        activeRules: List<String> = listOf("com.example.net.rule"),
        items: Map<String, VerifyEvaluator.ItemSnapshot> = emptyMap(),
    ): VerifyEvaluator.StateSnapshot =
        VerifyEvaluator.StateSnapshot(activeProfile, activeRules, items)

    private fun item(status: String, lastError: String? = null): VerifyEvaluator.ItemSnapshot =
        VerifyEvaluator.ItemSnapshot(status, lastError)

    private fun ruleRefMod(id: String, rule: String, enabled: Boolean = true): VerifyEvaluator.ProfileMod =
        VerifyEvaluator.ProfileMod(id = id, type = "rule_ref", enabled = enabled, rule = rule, apps = emptyList())

    private fun scopeMod(id: String, apps: List<String>, enabled: Boolean = true): VerifyEvaluator.ProfileMod =
        VerifyEvaluator.ProfileMod(id = id, type = "scope", enabled = enabled, rule = null, apps = apps)

    private fun driftItems(
        state: VerifyEvaluator.StateSnapshot,
        mods: List<VerifyEvaluator.ProfileMod> = emptyList(),
        expectedProfileId: String? = null,
        lsposedAvailable: Boolean = true,
    ): List<VerifyEvaluator.DriftItem> =
        VerifyEvaluator.evaluate(state, mods, expectedProfileId, lsposedAvailable)

    // --- profile mismatch -----------------------------------------------------

    @Test
    fun profile_mismatch_reports_profile_drift() {
        val drift = driftItems(
            state(activeProfile = "other"),
            expectedProfileId = "prod",
        )
        assertEquals(1, drift.size)
        val entry = drift.single()
        assertEquals("prod", entry.modId)
        assertEquals("profile", entry.kind)
        assertEquals("prod", entry.expected)
        assertEquals("other", entry.actual)
        assertEquals("active profile mismatch", entry.reason)
    }

    @Test
    fun matching_profile_has_no_profile_drift() {
        assertTrue(driftItems(state(activeProfile = "prod"), expectedProfileId = "prod").isEmpty())
    }

    // --- item status drift ----------------------------------------------------

    @Test
    fun failed_item_reports_drift_with_last_error() {
        val drift = driftItems(
            state(items = mapOf("net" to item("failed", lastError = "timeout"))),
        )
        assertEquals(1, drift.size)
        assertEquals("net", drift.single().modId)
        assertEquals("item", drift.single().kind)
        assertEquals("timeout", drift.single().reason)
    }

    @Test
    fun manual_and_drifted_items_report_drift() {
        val drift = driftItems(
            state(items = mapOf(
                "a" to item("manual"),
                "b" to item("drifted"),
            )),
        )
        assertEquals(setOf("a", "b"), drift.map { it.modId }.toSet())
        assertEquals("item not in applied state", drift.first { it.modId == "b" }.reason)
    }

    @Test
    fun applied_and_disabled_items_do_not_report_drift() {
        val drift = driftItems(
            state(items = mapOf(
                "ok" to item("applied"),
                "off" to item("disabled"),
            )),
        )
        assertTrue(drift.isEmpty())
    }

    // --- rule_ref vs active_rules cross-check --------------------------------

    @Test
    fun rule_ref_missing_from_active_rules_reports_drift() {
        val drift = driftItems(
            state(activeRules = listOf("com.example.other.rule")),
            mods = listOf(ruleRefMod("net", "com.example.net.rule")),
        )
        assertEquals(1, drift.size)
        val entry = drift.single()
        assertEquals("net", entry.modId)
        assertEquals("rule_ref", entry.kind)
        assertEquals("com.example.net.rule", entry.expected)
        assertEquals("com.example.other.rule", entry.actual)
        assertEquals("rule not active on device", entry.reason)
    }

    @Test
    fun rule_ref_present_in_active_rules_has_no_drift() {
        val drift = driftItems(
            state(activeRules = listOf("com.example.net.rule")),
            mods = listOf(ruleRefMod("net", "com.example.net.rule")),
        )
        assertTrue(drift.isEmpty())
    }

    @Test
    fun disabled_rule_ref_is_not_required() {
        val drift = driftItems(
            state(activeRules = emptyList()),
            mods = listOf(ruleRefMod("net", "com.example.net.rule", enabled = false)),
        )
        assertTrue(drift.isEmpty())
    }

    // --- scope ----------------------------------------------------------------

    @Test
    fun scope_reports_unavailable_when_cli_missing() {
        val drift = driftItems(
            state(),
            mods = listOf(scopeMod("sc", listOf("com.example.app"))),
            lsposedAvailable = false,
        )
        assertEquals(1, drift.size)
        val entry = drift.single()
        assertEquals("sc", entry.modId)
        assertEquals("scope", entry.kind)
        assertEquals("com.example.app", entry.expected)
        assertEquals("lsposed_cli_unavailable", entry.actual)
        assertEquals("lsposed cli unavailable", entry.reason)
    }

    @Test
    fun scope_is_skipped_when_cli_available() {
        val drift = driftItems(
            state(),
            mods = listOf(scopeMod("sc", listOf("com.example.app"))),
            lsposedAvailable = true,
        )
        assertTrue(drift.isEmpty())
    }

    // --- composition ----------------------------------------------------------

    @Test
    fun clean_profile_has_empty_drift() {
        val drift = driftItems(
            state(
                activeProfile = "prod",
                activeRules = listOf("com.example.net.rule"),
                items = mapOf("net" to item("applied")),
            ),
            mods = listOf(ruleRefMod("net", "com.example.net.rule")),
            expectedProfileId = "prod",
        )
        assertTrue(drift.isEmpty())
    }
}
