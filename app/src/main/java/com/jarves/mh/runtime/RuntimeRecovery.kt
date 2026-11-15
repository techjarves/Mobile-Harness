package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RuntimeRecoveryContext
import com.jarves.mh.model.RuntimeRecoveryProfile

/** Everything needed to restart or steer a session after a transport failure. */
data class RecoveryAction(
    val sessionId: String,
    val projectId: String,
    val projectSlug: String,
    val projectKind: ProjectKind,
    val originalPrompt: String,
    val instruction: String,
    val history: List<ChatMessage>,
    val provider: ProviderProfile,
)

/**
 * Harness-agnostic recovery policy. The order of the rules matters: a user stop
 * always wins, a live session continues in place, and automatic retries only
 * happen when no tool side effects can have been lost.
 */
object RuntimeRecoveryPolicy {
    fun decide(profile: RuntimeRecoveryProfile, context: RuntimeRecoveryContext): RecoveryDecision {
        if (context.userStopped) return RecoveryDecision.NO_RETRY
        if (context.inPlaceContinuationAvailable) return RecoveryDecision.CONTINUE_IN_PLACE
        // A harness that cannot report tool activity cannot prove no tools ran,
        // so an automatic retry would risk repeating side effects.
        if (!profile.reportsToolActivity) return RecoveryDecision.MANUAL_RETRY_REQUIRED
        if (profile.canResumePersisted && context.persistedHandle != null) {
            if (!context.toolsExecuted) return RecoveryDecision.RESUME_PERSISTED
            if (profile.reportsInterruptedToolOutcome && context.interruptedToolOutcomeKnown) {
                return RecoveryDecision.RESUME_PERSISTED
            }
        }
        if (!context.toolsExecuted) return RecoveryDecision.RETRY_NEW_SESSION_SAFE
        return RecoveryDecision.MANUAL_RETRY_REQUIRED
    }
}

/** Fail-closed: auto-restart is allowed only with a context that proves no tool ran. */
internal fun canAutoRestartWithoutTools(context: RuntimeRecoveryContext?): Boolean =
    context != null && !context.toolsExecuted

val DSH_RECOVERY_PROFILE = RuntimeRecoveryProfile(
    canResumePersisted = false,
    reportsToolActivity = true,
    reportsInterruptedToolOutcome = false,
    supportsTurnCheckpoint = false,
)

val CLAUDE_RECOVERY_PROFILE = RuntimeRecoveryProfile(
    canResumePersisted = false,
    reportsToolActivity = true,
    reportsInterruptedToolOutcome = false,
    supportsTurnCheckpoint = false,
)

val ANTIGRAVITY_RECOVERY_PROFILE = RuntimeRecoveryProfile(
    canResumePersisted = true,
    reportsToolActivity = true,
    reportsInterruptedToolOutcome = false,
    supportsTurnCheckpoint = false,
)

const val DSH_CONTINUATION_INSTRUCTION =
    "The previous attempt was interrupted by a network error. Continue the same task from where it stopped. Do not repeat tool calls that already have results. Before retrying a side-effecting action whose result is missing, inspect the workspace and verify whether it already happened."
