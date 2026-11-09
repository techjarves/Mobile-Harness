package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the DSH transport-retry policy and tool-tracking rules. No
 * bridge instance is constructed (its constructor needs an Android Context);
 * every behavior under test lives in a pure, coroutine-free helper.
 */
class DshTransportRecoveryTest {

    @Test
    fun firstTransportFailureSchedulesContinuationInFiveSeconds() {
        val state = DshTransportRetryState()
        val decision = state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = false)

        assertEquals(DshTransportRetryAction.RETRY, decision.action)
        assertEquals(5_000L, decision.delayMs)
        assertEquals(1, decision.attempt)
    }

    @Test
    fun secondTransportFailureSchedulesContinuationInFifteenSeconds() {
        val state = DshTransportRetryState()
        state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = false)
        val decision = state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = false)

        assertEquals(DshTransportRetryAction.RETRY, decision.action)
        assertEquals(15_000L, decision.delayMs)
        assertEquals(2, decision.attempt)
    }

    @Test
    fun thirdTransportFailureExhaustsAndFails() {
        val state = DshTransportRetryState()
        state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = false)
        state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = false)
        val decision = state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = false)

        assertEquals(DshTransportRetryAction.EXHAUSTED, decision.action)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun nonTransportFailureDoesNotRetry() {
        val state = DshTransportRetryState()
        val decision = state.onFailure(code = "AUTH", message = "The provider rejected the saved API key.", userStopRequested = false)

        assertEquals(DshTransportRetryAction.NOT_TRANSPORT, decision.action)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun userStopReturnsNoRetry() {
        val state = DshTransportRetryState()
        val decision = state.onFailure(code = "TRANSPORT", message = "Connection error.", userStopRequested = true)

        assertEquals(DshTransportRetryAction.USER_STOP, decision.action)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun continuationResetsTurnFlagsButKeepsSession() {
        // A transport continuation rebuilds the per-turn final-answer machine from
        // INITIAL (resetting the turn's assistant-text tracking) but keeps the
        // session-scoped tool tracking so the terminal SessionFailed can still warn
        // about side effects. The classification below is the pure rule the bridge
        // applies on every ToolStarted/ToolCompleted.
        listOf("read", "glob", "grep", "web_search", "web_fetch", "ls", "file_info")
            .forEach { assertFalse("$it must be read-only", isMutatingTool(it)) }
        listOf("bash", "write", "edit", "terminal", "git", "unknown_tool")
            .forEach { assertTrue("$it must be mutating", isMutatingTool(it)) }
        // Display names are capitalized by the parser; classification is case-insensitive.
        assertFalse(isMutatingTool("Read"))
        assertTrue(isMutatingTool("Bash"))
        assertTrue(isMutatingTool("Write"))

        // The rebuilt final-answer machine starts in INITIAL, independent of any prior turn.
        val continuationTurn = FinalAnswerStateMachine()
        assertEquals(FinalAnswerPhase.INITIAL, continuationTurn.phase)
    }

    @Test
    fun continuationResetClearsRunningSoTrailingIdleIsIgnored() {
        // Regression (review Important 1): sending a continuation must clear the
        // per-turn `sawRunning` flag, so a trailing idle from the ORIGINAL turn
        // cannot be evaluated for a nudge or a premature completion before the
        // continuation itself signals running.
        val reset = resetContinuationTurn()

        assertFalse(reset.sawRunning)
        assertEquals(FinalAnswerPhase.INITIAL, reset.finalAnswer.phase)
        assertFalse(
            shouldEvaluateNudgeOnIdle(failure = "", sawRunning = reset.sawRunning, shutdownSent = false),
        )
    }

    @Test
    fun transportFailureEntersBackoff() {
        val state = DshContinuationState()
        state.onTransportRetryScheduled()

        assertEquals(DshContinuationPhase.BACKOFF, state.phase)
        assertTrue(state.inBackoff)
    }

    @Test
    fun backoffSuppressesIdleAndTurnEnd() {
        val state = DshContinuationState()
        state.onTransportRetryScheduled()

        assertTrue(state.suppressIdle())
        assertTrue(state.suppressStaleTurnEvents())
    }

    @Test
    fun continuationSendLeavesBackoff() {
        val state = DshContinuationState()
        state.onTransportRetryScheduled()
        state.onContinuationSent()

        assertEquals(DshContinuationPhase.AWAITING_SIGNAL, state.phase)
        assertFalse(state.inBackoff)
    }

    @Test
    fun awaitingSignalSuppressesIdleUntilMeaningfulSignal() {
        val state = DshContinuationState()
        state.onContinuationSent()

        // Idle is suppressed while awaiting the continuation's first signal, but a
        // turn-end is itself a meaningful signal (never suppressed forever).
        assertTrue(state.suppressIdle())
        assertFalse(state.suppressStaleTurnEvents())

        state.onMeaningfulSignal()

        assertEquals(DshContinuationPhase.ORIGINAL_TURN, state.phase)
        assertFalse(state.suppressIdle())
    }

    @Test
    fun terminalExitsAndSuppressesEverything() {
        val state = DshContinuationState()
        state.onTransportRetryScheduled()
        state.onTerminal()

        assertEquals(DshContinuationPhase.TERMINAL, state.phase)
        assertTrue(state.isTerminal)
        assertTrue(state.suppressStaleTurnEvents())
        assertTrue(state.suppressIdle())

        // Terminal is sticky: no later transition can leave it.
        state.onTransportRetryScheduled()
        state.onContinuationSent()
        state.onMeaningfulSignal()
        assertEquals(DshContinuationPhase.TERMINAL, state.phase)
    }
}
