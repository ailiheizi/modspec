package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure parsing/classification logic of the agent's
 * inventory surfaces (no Android device, no org.json). Run via
 * `:app:testDebugUnitTest`.
 */
class InspectorParsingTest {

    // --- ProcessInspector ---

    @Test
    fun ps_line_parses_columns() {
        val row = ProcessInspector.parsePsLine("u0_a123 1234 S com.example.target")!!
        assertEquals("u0_a123", row.user)
        assertEquals(1234, row.pid)
        assertEquals("S", row.state)
        assertEquals("com.example.target", row.name)
        assertEquals("com.example.target", row.packageName)
        assertEquals(10123, row.uid)
    }

    @Test
    fun uid_mapping_covers_well_known_users() {
        assertEquals(0, ProcessInspector.uidOfUser("root"))
        assertEquals(1000, ProcessInspector.uidOfUser("system"))
        assertEquals(2000, ProcessInspector.uidOfUser("shell"))
        assertEquals(10123, ProcessInspector.uidOfUser("u0_a123"))
        assertEquals(1010123, ProcessInspector.uidOfUser("u10_a123"))
        assertEquals(12345, ProcessInspector.uidOfUser("12345"))
        assertNull(ProcessInspector.uidOfUser("some_unknown_user"))
    }

    @Test
    fun ps_line_with_process_part_maps_to_package() {
        val row = ProcessInspector.parsePsLine("u0_a123  5678 S com.example.target:push")!!
        assertEquals("com.example.target", row.packageName)
        assertEquals("com.example.target:push", row.name)
    }

    @Test
    fun ps_header_and_garbage_lines_are_rejected() {
        assertNull(ProcessInspector.parsePsLine("USER PID STAT NAME"))
        assertNull(ProcessInspector.parsePsLine(""))
        assertNull(ProcessInspector.parsePsLine("  "))
        assertNull(ProcessInspector.parsePsLine("u0_a123 notap pid S x"))
    }

    @Test
    fun kernel_process_names_are_not_mistaken_for_packages() {
        assertNull(ProcessInspector.packageOfProcessName("system_server"))
        assertNull(ProcessInspector.packageOfProcessName("kworker/0:1"))
        assertEquals("com.android.systemui", ProcessInspector.packageOfProcessName("com.android.systemui"))
    }

    // --- LogQuery ---

    @Test
    fun threadtime_line_parses_fields() {
        val parsed = LogQuery.parseThreadTimeLine(
            "08-07 12:34:56.789  1234  4321 E AndroidRuntime: boom",
        )!!
        assertEquals("AndroidRuntime", parsed.tag)
        assertEquals('E', parsed.level)
        assertEquals(1234, parsed.pid)
        assertEquals(4321, parsed.tid)
        assertEquals("boom", parsed.message)
        assertTrue(parsed.timestampMs > 0)
    }

    @Test
    fun threadtime_line_with_colon_in_tag_or_message() {
        val parsed = LogQuery.parseThreadTimeLine(
            "08-07 12:34:56.789  1234  4321 I ModspecAgent: message: with colons: ok",
        )!!
        assertEquals("ModspecAgent", parsed.tag)
        assertEquals("message: with colons: ok", parsed.message)
    }

    @Test
    fun garbage_lines_are_rejected() {
        assertNull(LogQuery.parseThreadTimeLine("not a log line"))
        assertNull(LogQuery.parseThreadTimeLine(""))
    }

    @Test
    fun filters_apply_pid_tag_and_since() {
        val pidLine = "08-07 12:34:56.789  1234  4321 E AndroidRuntime: crash"
        val otherLine = "08-07 12:34:56.790  9999  9999 I System: fine"
        // Pid set: the line matches when its pid (1234) OR tid (4321) is in it.
        assertTrue(LogQuery.matchesFilters(pidLine, listOf(1234), null, null))
        assertTrue(LogQuery.matchesFilters(pidLine, listOf(4321), null, null))
        assertFalse(LogQuery.matchesFilters(otherLine, listOf(1234), null, null))
        // Tag filter.
        assertTrue(LogQuery.matchesFilters(pidLine, emptyList(), "androidruntime", null))
        assertFalse(LogQuery.matchesFilters(pidLine, emptyList(), "System", null))
        // since_ms cutoff: the parsed timestamp is recent, so a far-past cutoff passes.
        assertTrue(LogQuery.matchesFilters(pidLine, emptyList(), null, 0L))
        // A future cutoff drops everything.
        val farFuture = System.currentTimeMillis() + 24L * 3600 * 1000
        assertFalse(LogQuery.matchesFilters(pidLine, emptyList(), null, farFuture))
    }

    // --- AppInspector ---

    @Test
    fun installer_line_is_parsed() {
        assertEquals(
            "com.android.vending",
            AppInspector.parseInstallerLine("package:com.example.app installer=com.android.vending"),
        )
        assertNull(AppInspector.parseInstallerLine("package:com.example.app"))
        assertNull(AppInspector.parseInstallerLine("unrelated line"))
    }

    // --- Shared package-name validation mirrors RpcHandler.requireSafePackage ---

    @Test
    fun package_name_validation_matches_agent_regex() {
        val valid = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        assertTrue(valid.matches("com.example.app"))
        assertTrue(valid.matches("com.example"))
        assertTrue(valid.matches("io.github.lsposed.mod"))
        assertFalse(valid.matches("com.example app"))
        assertFalse(valid.matches(""))
        assertFalse(valid.matches("single"))
    }
}
