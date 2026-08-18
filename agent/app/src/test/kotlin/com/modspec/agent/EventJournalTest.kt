package com.modspec.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM tests for the Agent-owned bounded event ring + durable journal.
 * These run with `:app:testDebugUnitTest` and do NOT require an Android device.
 */
class EventJournalTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("modspec-events").toFile()
        EventJournal.init(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun event(event: String, generation: Long? = null, ruleId: String? = null) =
        HookEvent(
            eventId = 0L,
            timestampMs = 1_000L,
            level = "I",
            tag = "T",
            event = event,
            generation = generation,
            ruleId = ruleId,
            scriptId = null,
            packageName = "com.example.target",
            message = "m",
            raw = null,
        )

    @Test
    fun event_ids_are_monotonic() {
        val a = EventJournal.append(event("hook_loaded"))
        val b = EventJournal.append(event("hook_hit"))
        val c = EventJournal.append(event("hook_loaded"))
        assertTrue(a.eventId < b.eventId)
        assertTrue(b.eventId < c.eventId)
    }

    @Test
    fun collect_without_cursor_returns_all_and_advances_cursor() {
        EventJournal.append(event("hook_loaded", generation = 42, ruleId = "r"))
        EventJournal.append(event("hook_loaded", generation = 42, ruleId = "r"))
        val first = EventJournal.collect(null, 100, null, null, null, null)
        assertEquals(2, first.entries.size)
        val next = first.nextEventId
        // Incremental poll strictly after the cursor: nothing new.
        val second = EventJournal.collect(next, 100, null, null, null, null)
        assertTrue(second.entries.isEmpty())
        assertEquals(next, second.nextEventId)
    }

    @Test
    fun batch_boundary_has_no_duplicates_or_losses() {
        val ids = mutableListOf<Long>()
        for (i in 0 until 25) {
            ids += EventJournal.append(event("hook_loaded", generation = i.toLong())).eventId
        }
        var cursor: Long? = null
        val seen = mutableListOf<Long>()
        while (seen.size < 25) {
            val batch = EventJournal.collect(cursor, 10, null, null, null, null)
            assertTrue(batch.entries.isNotEmpty())
            assertTrue(batch.entries.none { it.eventId <= (cursor ?: Long.MIN_VALUE) })
            for (e in batch.entries) seen += e.eventId
            cursor = batch.nextEventId
        }
        assertEquals(ids.sorted(), seen)
        assertEquals(25, seen.toSet().size)
    }

    @Test
    fun filters_apply_to_event_metadata() {
        EventJournal.append(event("hook_loaded", generation = 1, ruleId = "a/b"))
        EventJournal.append(event("hook_hit", generation = 2, ruleId = "a/b"))
        EventJournal.append(event("hook_loaded", generation = 2, ruleId = "x/y"))

        val exact = EventJournal.collect(null, 100, null, null, null, 2L)
        assertEquals(2, exact.entries.size)
        assertTrue(exact.entries.all { it.generation == 2L })

        val rule = EventJournal.collect(null, 100, "a/b", null, null, null)
        assertEquals(2, rule.entries.size)

        val both = EventJournal.collect(null, 100, "a/b", null, null, 2L)
        assertEquals(1, both.entries.size)
        assertEquals("hook_hit", both.entries.single().event)
    }

    @Test
    fun ring_is_bounded_and_reports_truncation() {
        for (i in 0 until EventJournal.MAX_EVENTS + 50) {
            EventJournal.append(event("hook_hit", generation = i.toLong()))
        }
        // Collect in batches (each poll is capped) and count everything.
        var cursor: Long? = null
        var total = 0
        var firstId: Long? = null
        loop@ while (true) {
            val batch = EventJournal.collect(cursor, 500, null, null, null, null)
            if (batch.entries.isEmpty()) break
            if (firstId == null) firstId = batch.entries.first().eventId
            total += batch.entries.size
            val next = batch.nextEventId
            if (next == cursor) break@loop
            cursor = next
        }
        assertEquals(EventJournal.MAX_EVENTS, total)
        // A cursor older than the oldest retained event is flagged as truncated.
        val tooOld = EventJournal.collect(0L, 100, null, null, null, null)
        assertTrue(tooOld.truncated)
        assertTrue((firstId ?: 0L) > 0L)
    }

    @Test
    fun append_if_new_deduplicates_logcat_overlaps() {
        val first = EventJournal.appendIfNew(event("hook_loaded", generation = 7))
        val duplicate = EventJournal.appendIfNew(event("hook_loaded", generation = 7))
        assertTrue(first != null)
        assertNull(duplicate)
        // Distinct generation is a distinct event.
        assertTrue(EventJournal.appendIfNew(event("hook_loaded", generation = 8)) != null)
    }

    @Test
    fun journal_persists_and_seeds_event_ids_across_restarts() {
        val a = EventJournal.append(event("hook_loaded", generation = 42, ruleId = "r"))
        val b = EventJournal.append(event("hook_hit", generation = 42, ruleId = "r"))
        assertTrue(b.eventId > a.eventId)

        // Re-init against the same directory: ring is restored and ids continue.
        EventJournal.init(dir)
        val all = EventJournal.collect(null, 100, null, null, null, null)
        assertEquals(2, all.entries.size)
        assertEquals(a.eventId, all.entries[0].eventId)
        val c = EventJournal.append(event("hook_loaded", generation = 43, ruleId = "r"))
        assertTrue(c.eventId > b.eventId)
    }

    @Test
    fun repeated_init_on_same_directory_does_not_clear_live_events() {
        val stored = EventJournal.append(event("rule_uploaded", generation = 42, ruleId = "r"))
        File(dir, "events.ndjson").delete()

        EventJournal.init(dir)

        val collected = EventJournal.collect(null, 100, null, null, null, null)
        assertEquals(listOf(stored.eventId), collected.entries.map { it.eventId })
    }

    @Test
    fun source_label_reflects_durability() {
        assertEquals("journal", EventJournal.sourceLabel())
    }
}
