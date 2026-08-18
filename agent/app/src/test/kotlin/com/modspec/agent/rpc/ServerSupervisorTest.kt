package com.modspec.agent.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for the server supervisor's lifecycle + watchdog logic using fake
 * servers. These run with `:app:testDebugUnitTest` and do NOT require an
 * Android device (or real sockets).
 */
class ServerSupervisorTest {

    /** Scripted [ManagedServer] whose liveness can be killed from the test. */
    private class FakeServer(
        override val name: String,
        val startCalls: AtomicInteger = AtomicInteger(),
        val stopCalls: AtomicInteger = AtomicInteger(),
    ) : ManagedServer {
        @Volatile
        var alive = false

        override fun start() {
            startCalls.incrementAndGet()
            alive = true
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }

    private class ThrowingServer(override val name: String) : ManagedServer {
        override fun start() {
            throw RuntimeException("bind failed")
        }

        override fun stop() = Unit

        override fun isAlive(): Boolean = false
    }

    private fun supervisor(
        http: FakeServer,
        ws: FakeServer,
        intervalMs: Long = 10,
        log: (String) -> Unit = {},
    ) = ServerSupervisor(
        serverFactory = { name -> if (name == "http") http else ws },
        log = log,
        watchdogIntervalMs = intervalMs,
    )

    @Test
    fun start_is_idempotent_and_creates_each_server_once() {
        val http = FakeServer("http")
        val ws = FakeServer("ws")
        val s = supervisor(http, ws)
        s.start()
        s.start() // second start must be a no-op
        assertTrue(http.alive)
        assertTrue(ws.alive)
        assertEquals(1, http.startCalls.get())
        assertEquals(1, ws.startCalls.get())
        assertTrue(s.isAlive("http"))
        assertTrue(s.isAlive("ws"))
        s.stop()
        assertFalse(http.alive)
        assertFalse(ws.alive)
        assertEquals(1, http.stopCalls.get())
        assertEquals(1, ws.stopCalls.get())
    }

    @Test
    fun watchdog_restarts_a_dead_server() {
        val http = FakeServer("http")
        val ws = FakeServer("ws")
        val s = supervisor(http, ws)
        s.start()
        assertTrue(http.alive)

        http.alive = false // simulate accept-loop death
        Thread.sleep(150) // > watchdog interval
        assertTrue("watchdog must re-create the dead http server", http.alive)
        assertEquals(2, http.startCalls.get())
        assertEquals(1, ws.startCalls.get())
        s.stop()
    }

    @Test
    fun poke_restarts_a_dead_server_immediately() {
        val http = FakeServer("http")
        val ws = FakeServer("ws")
        val s = supervisor(http, ws, intervalMs = 10_000) // watchdog must not race poke
        s.start()
        http.alive = false
        s.poke()
        assertTrue(http.alive)
        assertEquals(2, http.startCalls.get())
        s.stop()
    }

    @Test
    fun snapshot_reports_health_restarts_and_errors() {
        val http = FakeServer("http")
        val ws = FakeServer("ws")
        val logs = CopyOnWriteArrayList<String>()
        val s = ServerSupervisor(
            serverFactory = { name -> if (name == "http") http else ws },
            log = { logs += it },
            watchdogIntervalMs = 10_000, // watchdog must not race poke
        )
        s.start()
        http.alive = false
        s.poke()

        val snapshot = s.snapshot()
        assertEquals(listOf("http", "ws"), snapshot.map { it.name })
        assertTrue(snapshot[0].alive)
        assertEquals(1, snapshot[0].restarts)
        // The death was observed and repaired; the restart reason is cleared
        // after the server is healthy again (restarts counter keeps history).
        assertNull(snapshot[0].lastError)
        assertTrue(logs.any { it.contains("http server died") })
        assertTrue(snapshot[1].alive)
        assertEquals(0, snapshot[1].restarts)
        assertNull(snapshot[1].lastError)
        s.stop()
    }

    @Test
    fun start_failure_is_recorded_and_retried_on_next_poke() {
        var firstHttp = true
        val logs = CopyOnWriteArrayList<String>()
        val s = ServerSupervisor(
            serverFactory = { name ->
                if (name == "http" && firstHttp) {
                    firstHttp = false
                    ThrowingServer(name)
                } else {
                    FakeServer(name)
                }
            },
            log = { logs += it },
            watchdogIntervalMs = 10,
        )
        s.start()
        assertFalse("failed start must not report alive", s.isAlive("http"))
        assertNotNull(s.lastError("http"))
        // The failed initial start is not a restart; the retry counts later.
        assertEquals(0, s.restartCount("http"))
        assertTrue(s.isAlive("ws"))

        s.poke() // retry path
        assertTrue("next poke must bring the server up", s.isAlive("http"))
        assertNull(s.lastError("http"))
        assertTrue(logs.any { it.contains("failed to start") })
        s.stop()
    }

    @Test
    fun stop_is_idempotent_and_halts_the_watchdog() {
        val http = FakeServer("http")
        val ws = FakeServer("ws")
        val s = supervisor(http, ws)
        s.start()
        s.stop()
        s.stop() // second stop is safe

        http.alive = false
        Thread.sleep(100) // watchdog must no longer poke
        assertFalse("watchdog must not restart after stop", http.alive)
        assertEquals(1, http.startCalls.get())
        assertNull(s.lastError("http"))
    }

    @Test
    fun dead_ws_server_is_restarted_like_http() {
        val http = FakeServer("http")
        val ws = FakeServer("ws")
        val s = supervisor(http, ws)
        s.start()
        ws.alive = false
        Thread.sleep(150)
        assertTrue("watchdog must re-create the dead ws server", ws.alive)
        assertEquals(2, ws.startCalls.get())
        assertEquals(1, http.startCalls.get())
        s.stop()
    }
}
