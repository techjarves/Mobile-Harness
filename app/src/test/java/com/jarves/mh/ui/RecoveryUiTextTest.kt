package com.jarves.mh.ui

import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RecoveryFailureInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryUiTextTest {

    private fun recovery(
        decision: RecoveryDecision? = null,
        attempt: Int = 1,
        maxAttempts: Int = 2,
        retryAvailable: Boolean = true,
        sideEffectsPossible: Boolean = false,
        toolsExecuted: Boolean = false,
    ) = RecoveryUiState(
        decision = decision,
        attempt = attempt,
        maxAttempts = maxAttempts,
        reason = "transport failure",
        retryAvailable = retryAvailable,
        sideEffectsPossible = sideEffectsPossible,
        toolsExecuted = toolsExecuted,
    )

    @Test
    fun retryingLabelShowsAttemptAndMax() {
        val state = recovery(
            decision = RecoveryDecision.CONTINUE_IN_PLACE,
            retryAvailable = false,
        )

        assertEquals(
            "Conexión perdida. Reconectando… (Intento 1 de 2)",
            recoveryBannerLabel(
                recovery = state,
                reconnectingText = "Conexión perdida. Reconectando…",
                attemptTemplate = "Intento %1\$d de %2\$d",
                exhaustedText = "No se pudo reconectar",
            ),
        )
    }

    @Test
    fun exhaustedLabelOffersRetry() {
        assertEquals(
            "No se pudo reconectar",
            recoveryBannerLabel(
                recovery = recovery(),
                reconnectingText = "Conexión perdida. Reconectando…",
                attemptTemplate = "Intento %1\$d de %2\$d",
                exhaustedText = "No se pudo reconectar",
            ),
        )
    }

    @Test
    fun sideEffectWarningMentionsActions() {
        val warning = "El intento anterior pudo ejecutar acciones. Reintentar puede repetirlas."

        assertEquals(
            warning,
            recoveryWarningText(
                recovery = recovery(toolsExecuted = true),
                warningText = warning,
            ),
        )
    }

    @Test
    fun automaticRecoveryDecisionsShowReconnectingBanner() {
        val decisions = listOf(
            RecoveryDecision.CONTINUE_IN_PLACE,
            RecoveryDecision.RESUME_PERSISTED,
            RecoveryDecision.RETRY_NEW_SESSION_SAFE,
        )

        decisions.forEach { decision ->
            assertEquals(
                RecoveryBannerMode.RECONNECTING,
                recoveryBannerMode(recovery(decision = decision, retryAvailable = false)),
            )
        }
    }

    @Test
    fun unavailableRetryWithoutAutomaticDecisionHidesBanner() {
        val decisions = listOf(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RecoveryDecision.NO_RETRY,
            null,
        )

        decisions.forEach { decision ->
            assertNull(recoveryBannerMode(recovery(decision = decision, retryAvailable = false)))
        }
        assertNull(recoveryBannerMode(null))
    }

    @Test
    fun retryAvailableRecoveryShowsExhaustedBanner() {
        assertEquals(
            RecoveryBannerMode.EXHAUSTED,
            recoveryBannerMode(
                recovery(
                    decision = RecoveryDecision.CONTINUE_IN_PLACE,
                    retryAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun retryIsOfferedOnlyWhenAvailableAndIdle() {
        val available = recovery(retryAvailable = true)
        val unavailable = recovery(retryAvailable = false)

        assertTrue(shouldOfferRetry(available, isRunning = false))
        assertFalse(shouldOfferRetry(available, isRunning = true))
        assertFalse(shouldOfferRetry(unavailable, isRunning = false))
        assertFalse(shouldOfferRetry(null as RecoveryUiState?, isRunning = false))
    }

    @Test
    fun warningUsesToolsExecutedInsteadOfSideEffectsPossible() {
        assertTrue(
            shouldWarnSideEffects(
                recovery(sideEffectsPossible = false, toolsExecuted = true),
            ),
        )
        assertFalse(
            shouldWarnSideEffects(
                recovery(sideEffectsPossible = true, toolsExecuted = false),
            ),
        )
        assertFalse(shouldWarnSideEffects(null as RecoveryUiState?))
    }

    @Test
    fun warningTextIsHiddenWithoutToolExecution() {
        assertNull(
            recoveryWarningText(
                recovery = recovery(sideEffectsPossible = true, toolsExecuted = false),
                warningText = "El intento anterior pudo ejecutar acciones. Reintentar puede repetirlas.",
            ),
        )
    }

    @Test
    fun fallbackRetryShowsReconnectingBanner() {
        val recoveryInfo = RecoveryFailureInfo(
            retryAvailable = true,
            sideEffectsPossible = false,
            toolsExecuted = false,
        )
        val decisions = listOf(
            RecoveryDecision.RETRY_NEW_SESSION_SAFE,
            RecoveryDecision.RESUME_PERSISTED,
        )

        decisions.forEach { decision ->
            val state = recoveryUiStateForFallbackAttempt(
                decision = decision,
                attempt = 1,
                reason = "transport failure",
                recovery = recoveryInfo,
            )

            assertEquals(RecoveryBannerMode.RECONNECTING, recoveryBannerMode(state))
            assertEquals(1, state.attempt)
            assertEquals(2, state.maxAttempts)
            assertFalse(state.retryAvailable)
            assertFalse(shouldOfferRetry(state, isRunning = true))
        }
    }

    @Test
    fun fallbackRetryCarriesToolFacts() {
        val warning = "El intento anterior pudo ejecutar acciones. Reintentar puede repetirlas."
        val state = recoveryUiStateForFallbackAttempt(
            decision = RecoveryDecision.RETRY_NEW_SESSION_SAFE,
            attempt = 1,
            reason = "transport failure",
            recovery = RecoveryFailureInfo(
                retryAvailable = true,
                sideEffectsPossible = true,
                toolsExecuted = true,
            ),
        )

        assertTrue(state.toolsExecuted)
        assertTrue(state.sideEffectsPossible)
        assertEquals(warning, recoveryWarningText(state, warning))
    }
}
