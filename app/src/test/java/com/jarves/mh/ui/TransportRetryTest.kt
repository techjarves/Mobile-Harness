package com.jarves.mh.ui

import com.jarves.mh.model.RecoveryFailureInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRetryTest {
    @Test
    fun matchesConnectionErrorCaseInsensitively() {
        assertTrue(isTransportFailure("Connection error."))
        assertTrue(isTransportFailure("connection error"))
        assertTrue(isTransportFailure("CONNECTION ERROR"))
    }

    @Test
    fun matchesTransportCodeCaseInsensitively() {
        assertTrue(isTransportFailure("TRANSPORT"))
        assertTrue(isTransportFailure("transport"))
        assertTrue(isTransportFailure("Transport"))
    }

    @Test
    fun doesNotMatchAuthQuotaOrRateLimitFailures() {
        assertFalse(isTransportFailure("Invalid API key"))
        assertFalse(isTransportFailure("authentication failed"))
        assertFalse(isTransportFailure("HTTP 401"))
        assertFalse(isTransportFailure("HTTP 403"))
        assertFalse(isTransportFailure("HTTP 429"))
        assertFalse(isTransportFailure("quota exceeded"))
        assertFalse(isTransportFailure("rate limit reached"))
        assertFalse(isTransportFailure("token expired"))
    }

    @Test
    fun transportIsNotMatchedWhenAuthTermsPresent() {
        assertFalse(isTransportFailure("Connection error: quota exceeded"))
        assertFalse(isTransportFailure("TRANSPORT 429 rate limit"))
    }

    @Test
    fun retryBudgetBacksOffFiveThenFifteenSecondsThenExhausts() {
        assertEquals(5000L, transportRetryDelayMillis(0)!!)
        assertEquals(15000L, transportRetryDelayMillis(1)!!)
        assertNull(transportRetryDelayMillis(2))
        assertNull(transportRetryDelayMillis(3))
    }

    @Test
    fun stopFinalizesLocallyOnlyDuringRetryGap() {
        assertFalse(shouldFinalizeStopLocally(false))
        assertTrue(shouldFinalizeStopLocally(true))
    }

    @Test
    fun transportFailureHandlingRequiresRecoveryEvidenceNotHarnessType() {
        val evidence = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = false, toolsExecuted = false)
        assertTrue(shouldHandleTransportFailure("Connection error.", evidence))
        assertFalse(shouldHandleTransportFailure("Connection error.", null))
        assertFalse(shouldHandleTransportFailure("Invalid API key", evidence))
    }
}
