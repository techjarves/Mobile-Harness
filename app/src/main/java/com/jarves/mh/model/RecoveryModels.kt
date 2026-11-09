package com.jarves.mh.model

enum class RecoveryDecision {
    CONTINUE_IN_PLACE,
    RESUME_PERSISTED,
    RETRY_NEW_SESSION_SAFE,
    MANUAL_RETRY_REQUIRED,
    NO_RETRY,
}

/**
 * What a runtime can honestly report about a session after a transport failure.
 * The policy trusts these flags to decide whether an automatic retry is safe.
 */
data class RuntimeRecoveryProfile(
    val canResumePersisted: Boolean,
    val reportsToolActivity: Boolean,
    val reportsInterruptedToolOutcome: Boolean,
    val supportsTurnCheckpoint: Boolean,
)

/** Session-specific facts gathered at the moment a failure is classified. */
data class RuntimeRecoveryContext(
    val sessionId: String,
    val inPlaceContinuationAvailable: Boolean,
    val persistedHandle: String?,
    val toolsExecuted: Boolean,
    val mutatingToolsExecuted: Boolean,
    val interruptedToolOutcomeKnown: Boolean,
    val checkpointAvailable: Boolean,
    val userStopped: Boolean,
)

/** User-facing recovery facts attached to a failed session. */
data class RecoveryFailureInfo(
    val retryAvailable: Boolean,
    val sideEffectsPossible: Boolean,
    val toolsExecuted: Boolean,
)
