package com.jarves.mh.runtime

import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RuntimeRecoveryContext
import com.jarves.mh.model.RuntimeRecoveryProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeRecoveryPolicyTest {

    private val noResumeProfile = RuntimeRecoveryProfile(
        canResumePersisted = false,
        reportsToolActivity = true,
        reportsInterruptedToolOutcome = false,
        supportsTurnCheckpoint = false,
    )

    private fun context(
        inPlaceContinuationAvailable: Boolean = false,
        persistedHandle: String? = null,
        toolsExecuted: Boolean = false,
        userStopped: Boolean = false,
    ) = RuntimeRecoveryContext(
        sessionId = "session-1",
        inPlaceContinuationAvailable = inPlaceContinuationAvailable,
        persistedHandle = persistedHandle,
        toolsExecuted = toolsExecuted,
        mutatingToolsExecuted = toolsExecuted,
        interruptedToolOutcomeKnown = false,
        checkpointAvailable = false,
        userStopped = userStopped,
    )

    @Test
    fun stoppedUserNeverRecovers() {
        assertEquals(
            RecoveryDecision.NO_RETRY,
            RuntimeRecoveryPolicy.decide(
                noResumeProfile,
                context(inPlaceContinuationAvailable = true, persistedHandle = "handle", toolsExecuted = true, userStopped = true),
            ),
        )
    }

    @Test
    fun liveSessionContinuesInPlaceEvenAfterTools() {
        assertEquals(
            RecoveryDecision.CONTINUE_IN_PLACE,
            RuntimeRecoveryPolicy.decide(noResumeProfile, context(inPlaceContinuationAvailable = true, toolsExecuted = true)),
        )
    }

    @Test
    fun deadSessionBeforeToolsRetriesNewSession() {
        assertEquals(
            RecoveryDecision.RETRY_NEW_SESSION_SAFE,
            RuntimeRecoveryPolicy.decide(noResumeProfile, context(toolsExecuted = false)),
        )
    }

    @Test
    fun deadSessionAfterToolsRequiresManualWithoutResume() {
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(noResumeProfile, context(toolsExecuted = true)),
        )
    }

    @Test
    fun persistedResumeAllowedWhenNoToolsRan() {
        val profile = noResumeProfile.copy(canResumePersisted = true)
        assertEquals(
            RecoveryDecision.RESUME_PERSISTED,
            RuntimeRecoveryPolicy.decide(profile, context(persistedHandle = "handle", toolsExecuted = false)),
        )
    }

    @Test
    fun persistedResumeAfterToolsRequiresKnownOutcome() {
        val profile = noResumeProfile.copy(canResumePersisted = true, reportsInterruptedToolOutcome = false)
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(profile, context(persistedHandle = "handle", toolsExecuted = true)),
        )
    }

    @Test
    fun persistedResumeAfterToolsAllowedWhenOutcomeKnown() {
        val profile = noResumeProfile.copy(canResumePersisted = true, reportsInterruptedToolOutcome = true)
        val known = context(persistedHandle = "handle", toolsExecuted = true).copy(interruptedToolOutcomeKnown = true)
        assertEquals(
            RecoveryDecision.RESUME_PERSISTED,
            RuntimeRecoveryPolicy.decide(profile, known),
        )
    }

    @Test
    fun profileWithoutToolActivityReportingFailsClosed() {
        val profile = noResumeProfile.copy(reportsToolActivity = false)
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(profile, context(toolsExecuted = false)),
        )
    }

    @Test
    fun inPlaceContinuationWinsEvenWithoutToolActivityReporting() {
        val profile = noResumeProfile.copy(reportsToolActivity = false)
        assertEquals(
            RecoveryDecision.CONTINUE_IN_PLACE,
            RuntimeRecoveryPolicy.decide(profile, context(inPlaceContinuationAvailable = true, toolsExecuted = false)),
        )
    }
}
