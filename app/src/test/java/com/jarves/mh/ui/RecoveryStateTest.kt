package com.jarves.mh.ui

import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RecoveryFailureInfo
import com.jarves.mh.model.RuntimeRecoveryContext
import com.jarves.mh.runtime.DSH_RECOVERY_PROFILE
import com.jarves.mh.runtime.RuntimeRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryStateTest {

    private fun context(
        toolsExecuted: Boolean = false,
        inPlaceContinuationAvailable: Boolean = false,
        persistedHandle: String? = null,
        userStopped: Boolean = false,
        mutatingToolsExecuted: Boolean = toolsExecuted,
    ) = RuntimeRecoveryContext(
        sessionId = "s1",
        inPlaceContinuationAvailable = inPlaceContinuationAvailable,
        persistedHandle = persistedHandle,
        toolsExecuted = toolsExecuted,
        mutatingToolsExecuted = mutatingToolsExecuted,
        interruptedToolOutcomeKnown = false,
        checkpointAvailable = false,
        userStopped = userStopped,
    )

    @Test
    fun transportFailureBeforeToolsIsSafeToRetry() {
        val decision = RuntimeRecoveryPolicy.decide(
            DSH_RECOVERY_PROFILE,
            context(toolsExecuted = false),
        )
        assertEquals(RecoveryDecision.RETRY_NEW_SESSION_SAFE, decision)
    }

    @Test
    fun transportFailureAfterToolsRequiresManualWhenNoResume() {
        val decision = RuntimeRecoveryPolicy.decide(
            DSH_RECOVERY_PROFILE,
            context(toolsExecuted = true),
        )
        assertEquals(RecoveryDecision.MANUAL_RETRY_REQUIRED, decision)
    }

    @Test
    fun retryIsOfferedOnlyWhenAvailable() {
        val available = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = false, toolsExecuted = false)
        val unavailable = RecoveryFailureInfo(retryAvailable = false, sideEffectsPossible = false, toolsExecuted = false)
        assertTrue(shouldOfferRetry(available, isRunning = false))
        assertFalse(shouldOfferRetry(available, isRunning = true))
        assertFalse(shouldOfferRetry(unavailable, isRunning = false))
        assertFalse(shouldOfferRetry(null as RecoveryFailureInfo?, isRunning = false))
    }

    @Test
    fun sideEffectWarningOnlyWhenMutatingToolsRan() {
        val ran = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = true, toolsExecuted = true)
        val clean = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = false, toolsExecuted = false)
        assertTrue(shouldWarnSideEffects(ran))
        assertFalse(shouldWarnSideEffects(clean))
        assertFalse(shouldWarnSideEffects(null as RecoveryFailureInfo?))
    }

    @Test
    fun terminalFailureUserStopReturnsNull() {
        // A user-initiated stop must not recreate recovery state or a retry request;
        // even a bridge-supplied recovery is dropped when the stop is resolved.
        assertNull(terminalFailureRecoveryInfo(null, context(userStopped = true), userStopped = true))
        val evidence = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = true, toolsExecuted = true)
        assertNull(terminalFailureRecoveryInfo(evidence, context(userStopped = true), userStopped = true))
    }

    @Test
    fun terminalFailurePrefersEventRecoveryVerbatim() {
        val event = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = true, toolsExecuted = true)
        val result = terminalFailureRecoveryInfo(event, context(toolsExecuted = false), userStopped = false)!!
        assertTrue(result.retryAvailable)
        assertTrue(result.sideEffectsPossible)
        assertTrue(result.toolsExecuted)
        // A conflicting context must not override the bridge's own facts.
        val conflicting = terminalFailureRecoveryInfo(event, context(toolsExecuted = false), userStopped = false)!!
        assertTrue(conflicting.sideEffectsPossible)
        assertTrue(conflicting.toolsExecuted)
    }

    @Test
    fun terminalFailureDerivesFactsFromContextWhenEventNull() {
        val mutating = context(toolsExecuted = true, mutatingToolsExecuted = true)
        val readOnly = context(toolsExecuted = true, mutatingToolsExecuted = false)
        val mutatingResult = terminalFailureRecoveryInfo(null, mutating, userStopped = false)!!
        assertTrue(mutatingResult.retryAvailable)
        assertTrue(mutatingResult.sideEffectsPossible)
        assertTrue(mutatingResult.toolsExecuted)
        val readOnlyResult = terminalFailureRecoveryInfo(null, readOnly, userStopped = false)!!
        assertFalse(readOnlyResult.sideEffectsPossible)
        assertTrue(readOnlyResult.toolsExecuted)
    }

    @Test
    fun terminalFailureNullContextDefaultsSafe() {
        val result = terminalFailureRecoveryInfo(null, null, userStopped = false)!!
        assertTrue(result.retryAvailable)
        assertFalse(result.sideEffectsPossible)
        assertFalse(result.toolsExecuted)
    }

    @Test
    fun effectiveRecoveryInfoPrefersEventRecoveryVerbatim() {
        val event = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = true, toolsExecuted = true)
        val result = effectiveRecoveryInfo(event, context(toolsExecuted = true))
        assertTrue(result.retryAvailable)
        assertTrue(result.sideEffectsPossible)
        assertTrue(result.toolsExecuted)
        // A conflicting context must not override the bridge's own facts.
        val conflicting = effectiveRecoveryInfo(event, context(toolsExecuted = false))
        assertTrue(conflicting.sideEffectsPossible)
        assertTrue(conflicting.toolsExecuted)
    }

    @Test
    fun effectiveRecoveryInfoDerivesSideEffectsFromMutatingTools() {
        val mutating = context(toolsExecuted = true, mutatingToolsExecuted = true)
        val readOnly = context(toolsExecuted = true, mutatingToolsExecuted = false)
        assertTrue(effectiveRecoveryInfo(null, mutating).sideEffectsPossible)
        assertTrue(effectiveRecoveryInfo(null, mutating).toolsExecuted)
        assertFalse(effectiveRecoveryInfo(null, readOnly).sideEffectsPossible)
        assertTrue(effectiveRecoveryInfo(null, readOnly).toolsExecuted)
    }

    @Test
    fun effectiveRecoveryInfoNullContextDefaultsSafe() {
        val result = effectiveRecoveryInfo(null, null)
        assertTrue(result.retryAvailable)
        assertFalse(result.sideEffectsPossible)
        assertFalse(result.toolsExecuted)
        // A user stop in the context withholds the retry offer.
        assertFalse(effectiveRecoveryInfo(null, context(userStopped = true)).retryAvailable)
    }
}
