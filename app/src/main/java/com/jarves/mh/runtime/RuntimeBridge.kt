package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import kotlinx.coroutines.flow.Flow

/**
 * Contract every agent bridge implements. Agent-specific launch details
 * (executable, arguments, translation model) live in [AgentProfiles]; this
 * file must not change when an agent is added or removed (ISSUE-039).
 */
interface RuntimeBridge {
    val events: Flow<RuntimeEvent>

    suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String

    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}
