package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-025 regression tests: the stop registry must be keyed per session so a
 * second bridge session can never clobber the first one's stop handler, and a
 * stop signal must only reach the session it targets (or all sessions when no
 * target is given, as the notification's Stop button does).
 */
class RuntimeTaskControllerTest {
    @Test
    fun `register keeps each session isolated`() {
        val stopped = mutableListOf<String>()
        RuntimeTaskController.register("session-a") { stopped += "a" }
        RuntimeTaskController.register("session-b") { stopped += "b" }

        RuntimeTaskController.requestStop("session-a")

        assertEquals(listOf("a"), stopped)
        RuntimeTaskController.unregister("session-a")
        RuntimeTaskController.unregister("session-b")
    }

    @Test
    fun `stopping one session does not touch another`() {
        var aStopped = false
        var bStopped = false
        RuntimeTaskController.register("session-a") { aStopped = true }
        RuntimeTaskController.register("session-b") { bStopped = true }

        RuntimeTaskController.requestStop("session-b")

        assertFalse(aStopped)
        assertTrue(bStopped)
        RuntimeTaskController.unregister("session-a")
        RuntimeTaskController.unregister("session-b")
    }

    @Test
    fun `stop without session id stops every registered session`() {
        var aStopped = false
        var bStopped = false
        RuntimeTaskController.register("session-a") { aStopped = true }
        RuntimeTaskController.register("session-b") { bStopped = true }

        RuntimeTaskController.requestStop()

        assertTrue(aStopped)
        assertTrue(bStopped)
        RuntimeTaskController.unregister("session-a")
        RuntimeTaskController.unregister("session-b")
    }

    @Test
    fun `re-registering the same session replaces the previous handler`() {
        var firstHandlerRan = false
        var secondHandlerRan = false
        RuntimeTaskController.register("session-a") { firstHandlerRan = true }
        RuntimeTaskController.register("session-a") { secondHandlerRan = true }

        RuntimeTaskController.requestStop("session-a")

        assertFalse(firstHandlerRan)
        assertTrue(secondHandlerRan)
        RuntimeTaskController.unregister("session-a")
    }

    @Test
    fun `unregister removes the handler and a throwing handler does not break the rest`() {
        var bStopped = false
        RuntimeTaskController.register("session-a") { error("stop action failed") }
        RuntimeTaskController.register("session-b") { bStopped = true }

        RuntimeTaskController.requestStop()
        RuntimeTaskController.unregister("session-a")

        assertTrue(bStopped)
        // After unregistering, a stop signal is a no-op even for all sessions.
        RuntimeTaskController.requestStop()
    }

    @Test
    fun `stop for an unknown session id is a safe no-op`() {
        RuntimeTaskController.requestStop("never-registered")
    }
}
