package com.modspec.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-JVM drift computation for the `verify` RPC. Evaluation operates on the
 * plain Kotlin snapshots below (no org.json), so the decision logic is unit
 * testable on the JVM (the mockable android.jar stubs org.json in unit tests).
 * [extractState]/[extractMods] are thin org.json adapters used by RpcHandler.
 */
object VerifyEvaluator {

    data class ItemSnapshot(val status: String, val lastError: String?)

    data class StateSnapshot(
        val activeProfile: String?,
        val activeRules: List<String>,
        val items: Map<String, ItemSnapshot>,
    )

    data class ProfileMod(
        val id: String,
        val type: String,
        val enabled: Boolean,
        val rule: String?,
        val apps: List<String>,
    )

    data class DriftItem(
        val modId: String,
        val kind: String,
        val expected: String,
        val actual: String,
        val reason: String,
    )

    fun extractState(state: JSONObject): StateSnapshot {
        val items = state.optJSONObject("items") ?: JSONObject()
        return StateSnapshot(
            activeProfile = state.optString("active_profile").takeIf { it.isNotBlank() },
            activeRules = state.optJSONArray("active_rules")?.toStringList() ?: emptyList(),
            items = items.keys().asSequence().mapNotNull { key ->
                val item = items.optJSONObject(key) ?: return@mapNotNull null
                key to ItemSnapshot(
                    status = item.optString("status", "applied"),
                    lastError = item.optString("last_error").takeIf { it.isNotBlank() },
                )
            }.toMap(),
        )
    }

    fun extractMods(profile: JSONObject): List<ProfileMod> {
        val mods = profile.optJSONArray("mods") ?: return emptyList()
        return (0 until mods.length()).mapNotNull { index ->
            val mod = mods.optJSONObject(index) ?: return@mapNotNull null
            ProfileMod(
                id = mod.optString("id"),
                type = mod.optString("type"),
                enabled = mod.optBoolean("enabled", true),
                rule = mod.optString("rule").takeIf { it.isNotBlank() },
                apps = mod.optJSONArray("apps")?.toStringList() ?: emptyList(),
            )
        }
    }

    /**
     * @param state snapshot extracted from AgentStorage state.json
     * @param mods profile mods (empty when the profile file is missing)
     * @param expectedProfileId requested `profile_id` param, null when only the
     *   current active profile should be verified
     * @param lsposedAvailable whether LsposedCli is usable on-device
     */
    fun evaluate(
        state: StateSnapshot,
        mods: List<ProfileMod>,
        expectedProfileId: String?,
        lsposedAvailable: Boolean,
    ): List<DriftItem> {
        val drift = mutableListOf<DriftItem>()

        if (expectedProfileId != null && expectedProfileId != state.activeProfile) {
            drift += DriftItem(
                modId = expectedProfileId,
                kind = "profile",
                expected = expectedProfileId,
                actual = state.activeProfile.orEmpty(),
                reason = "active profile mismatch",
            )
        }

        state.items.forEach { (modId, item) ->
            if (item.status in ITEM_DRIFT_STATUSES) {
                drift += DriftItem(
                    modId = modId,
                    kind = "item",
                    expected = "applied",
                    actual = item.status,
                    reason = item.lastError ?: "item not in applied state",
                )
            }
        }

        val activeRules = state.activeRules.toSet()
        val activeRulesText = activeRules.sorted().joinToString(", ")

        for (mod in mods) {
            if (!mod.enabled) continue
            when (mod.type) {
                "rule_ref" -> {
                    val rule = mod.rule
                    if (rule != null && rule !in activeRules) {
                        drift += DriftItem(
                            modId = mod.id,
                            kind = "rule_ref",
                            expected = rule,
                            actual = activeRulesText,
                            reason = "rule not active on device",
                        )
                    }
                }
                "scope" -> {
                    if (!lsposedAvailable) {
                        drift += DriftItem(
                            modId = mod.id,
                            kind = "scope",
                            expected = mod.apps.joinToString(", "),
                            actual = "lsposed_cli_unavailable",
                            reason = "lsposed cli unavailable",
                        )
                    }
                    // LsposedCli exposes no readScope, so a live scope diff is
                    // skipped when the CLI is available; item-level status
                    // coverage handles that case.
                }
            }
        }

        return drift
    }

    private val ITEM_DRIFT_STATUSES = setOf("failed", "manual", "drifted")

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, null) }
}
