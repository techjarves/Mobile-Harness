package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAnswerStateMachineTest {

    @Test
    fun needsNudgeOnlyWhenActivityWithoutTextSinceLastTool() {
        assertTrue(needsFinalAnswerNudge(sawAssistantText = false, sawActivity = true, followUpNudgeSent = false))
        assertFalse(needsFinalAnswerNudge(sawAssistantText = true, sawActivity = true, followUpNudgeSent = false))
        assertFalse(needsFinalAnswerNudge(sawAssistantText = false, sawActivity = false, followUpNudgeSent = false))
        assertFalse(needsFinalAnswerNudge(sawAssistantText = false, sawActivity = true, followUpNudgeSent = true))
    }

    @Test
    fun reasoningThenToolThenReasoningOnlyTurnEndSendsNudge() {
        val sm = FinalAnswerStateMachine()
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.REASONING))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.TOOL_ACTIVITY))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.TOOL_COMPLETED))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.REASONING))
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun textAtEndDoesNotNudge() {
        val sm = FinalAnswerStateMachine()
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.REASONING))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.ASSISTANT_TEXT))
        assertEquals(FinalAnswerAction.FINISH, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun emptyTurnDoesNotNudge() {
        val sm = FinalAnswerStateMachine()
        assertEquals(FinalAnswerAction.FINISH, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun nudgeTextFinishes() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.RUNNING))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.ASSISTANT_TEXT))
        assertEquals(FinalAnswerAction.FINISH, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun nudgeNoTextEmitsFallback() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.RUNNING))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.REASONING))
        assertEquals(FinalAnswerAction.EMIT_FALLBACK, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun originalIdleAfterNudgeIsIgnored() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.IDLE))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.RUNNING))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.ASSISTANT_TEXT))
        assertEquals(FinalAnswerAction.FINISH, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun nudgeRejectionEmitsFallbackOnceAndIgnoresTrailingEnd() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.EMIT_FALLBACK, sm.step(FinalAnswerEvent.NUDGE_REJECTED))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun rejectionAfterDoneIsIgnored() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.EMIT_FALLBACK, sm.step(FinalAnswerEvent.NUDGE_REJECTED))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.NUDGE_REJECTED))
    }

    @Test
    fun markDoneAbsorbsLateTextTurnEndAndRejection() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        sm.markDone()
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.ASSISTANT_TEXT))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.IDLE))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.NUDGE_REJECTED))
    }

    @Test
    fun idleWithoutTurnEndSendsNudge() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        sm.step(FinalAnswerEvent.TOOL_ACTIVITY)
        sm.step(FinalAnswerEvent.TOOL_COMPLETED)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.IDLE))
    }

    @Test
    fun doubleTurnEndWithoutRunningEvaluatesNudge() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.EMIT_FALLBACK, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun assistantTextThenToolThenReasoningOnlyTurnEndStillNudges() {
        val sm = FinalAnswerStateMachine()
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.ASSISTANT_TEXT))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.TOOL_COMPLETED))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.REASONING))
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
    }

    @Test
    fun failedTurnTrailingIdleDoesNotEvaluateNudge() {
        // A failed turn records its failure before the trailing idle; that idle must
        // not trigger a nudge against a dying session.
        assertFalse(
            shouldEvaluateNudgeOnIdle(failure = "Connection error.", sawRunning = true, shutdownSent = false),
        )
        assertTrue(shouldEvaluateNudgeOnIdle(failure = "", sawRunning = true, shutdownSent = false))
        assertFalse(shouldEvaluateNudgeOnIdle(failure = "", sawRunning = false, shutdownSent = false))
        assertFalse(shouldEvaluateNudgeOnIdle(failure = "", sawRunning = true, shutdownSent = true))
    }

    @Test
    fun failureIsTerminalAndNeverCompletedEvenAfterActivity() {
        assertFalse(turnCompleted(sawActivity = true, failure = "Connection error."))
        assertFalse(turnCompleted(sawActivity = false, failure = "Connection error."))
        assertTrue(turnCompleted(sawActivity = true, failure = ""))
        assertFalse(turnCompleted(sawActivity = false, failure = ""))
    }

    @Test
    fun nudgeWatchdogWithTextFinishesWithoutFallback() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.RUNNING))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.ASSISTANT_TEXT))
        assertEquals(FinalAnswerAction.FINISH, sm.onNudgeWatchdogExpired())
        // Idempotent: a second expiry must not re-decide.
        assertEquals(FinalAnswerAction.CONTINUE, sm.onNudgeWatchdogExpired())
    }

    @Test
    fun nudgeWatchdogWithoutTextEmitsFallback() {
        val sm = FinalAnswerStateMachine()
        sm.step(FinalAnswerEvent.REASONING)
        assertEquals(FinalAnswerAction.SEND_NUDGE, sm.step(FinalAnswerEvent.TURN_END))
        assertEquals(FinalAnswerAction.CONTINUE, sm.step(FinalAnswerEvent.RUNNING))
        assertEquals(FinalAnswerAction.EMIT_FALLBACK, sm.onNudgeWatchdogExpired())
        // Idempotent: a second expiry must not re-decide.
        assertEquals(FinalAnswerAction.CONTINUE, sm.onNudgeWatchdogExpired())
    }
}
