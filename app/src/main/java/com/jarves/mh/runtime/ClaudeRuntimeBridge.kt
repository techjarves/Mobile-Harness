package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.model.AgentAutonomyMode
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val json = runCatching { JSONObject(line) }.getOrNull()
        val combined = buildString {
            append(line)
            json?.let {
                append(' ')
                append(it.optString("error"))
                append(' ')
                append(it.optString("message"))
                append(' ')
                append(it.optString("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "invalid api key" in combined ||
                "http 401" in combined ||
                "http 403" in combined ||
                "http 429" in combined ||
                "expired" in combined ||
                "quota" in combined ||
                "rate limit" in combined ||
                (json?.optString("subtype") == "api_retry" && json.optInt("error_status") in listOf(401, 403, 429)) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    /** Current autonomy mode; read live so a settings change applies to the next session. */
    private val autonomyProvider: () -> AgentAutonomyMode = { AgentAutonomyMode.APPROVE_RISKY },
    /**
     * Reports the Claude Code `session_id` observed in the stream (init/result
     * events) as (projectId, claudeSessionId). The app persists it per chat so
     * an interrupted task can be resumed natively with --resume (ISSUE-007).
     */
    private val saveConversationId: (String, String) -> Unit = { _, _ -> },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    /** Shared checkpoint store — the bridge's private copy was removed (ISSUE-014/039/040). */
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    /** Project of the running session, so the session_id callback can address the right chat. */
    @Volatile private var activeProjectId: String? = null
    /**
     * One-shot native resume (ISSUE-007): when set before a session starts, the
     * CLI is launched with `--resume <id>` and continues the agent's own
     * conversation context instead of rebuilding it from the transcript.
     */
    @Volatile private var resumeSessionId: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L

    /** Arms a native --resume for the next session; consumed exactly once by [startSession]. */
    fun requestResume(claudeSessionId: String) {
        if (claudeSessionId.isNotBlank()) resumeSessionId = claudeSessionId
    }

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        // Stale approvals from an earlier session can never answer this one.
        pending.clear()
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        toolNames.clear()
        seenToolCalls.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
        streamedThinking.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Claude Code…")
        val secret = secretFor(provider).orEmpty()
        if (secret.isBlank()) {
            val message = if (provider.kind == ProviderKind.CLAUDE) {
                "No Claude subscription token is saved. Add one from Agent → AI provider."
            } else {
                "No API key is saved for ${provider.kind.title}."
            }
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            return@withContext sessionId
        }

        var formatGateway: LocalFormatGateway? = null
        var sovereignProxy: SovereignProxy? = null
        runCatching {
            RuntimeTaskController.register(sessionId) {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            // Task-scoped resources (ISSUE-013): the wake lock and thermal
            // monitor live exactly as long as this session.
            TaskWakeLocks.acquire(context, "session:$sessionId")
            ThermalMonitor.start(context) { status ->
                eventBus.tryEmit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Device is thermal throttling",
                        "The device is running hot (level $status of 6). The agent may work slower until it cools down.",
                    ),
                )
            }
            startForegroundRuntime(projectSlug, sessionId)
            // Setup and release checks happen once in the app-start loading flow.
            // Sending a prompt must never perform network update checks or put setup
            // messages into the conversation.
            val installed = installer.installedRuntime()
            installer.ensureSettingsAndHooks(autonomyProvider())
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            // Sovereign proxy (ISSUE-010): the provider credential stays on the
            // host side; the guest only ever sees PROXY_MANAGED_SECRET. OpenAI
            // protocols keep going through the format gateway, which already
            // holds the key on the host side.
            formatGateway = if (provider.kind.protocol in setOf(
                    com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT,
                    com.jarves.mh.model.ProviderProtocol.OPENAI_RESPONSES,
                )) LocalFormatGateway(provider, secret).start() else null
            sovereignProxy = if (formatGateway == null) {
                SovereignProxy.forProvider(provider, secret, projectSlug)?.start()
            } else null
            val launch = RuntimeLaunchConfigBuilder.build(
                profile = provider,
                agent = AgentProfiles.CLAUDE_CODE,
                authToken = secret,
                localGatewayUrl = formatGateway?.url,
                proxyUrl = sovereignProxy?.url,
            )
            // Raw provider config, prompt-bearing command lines and agent output must
            // never reach logcat in release builds (ISSUE-004).
            AppLog.d("ClaudeBridge", "Provider: ${provider.kind}, Model: ${provider.model}, BaseUrl: ${provider.baseUrl}")
            AppLog.d("ClaudeBridge", "Launch environment keys: ${launch.environment.keys}")

            // Build a context-aware prompt that includes conversation history.
            // Native resume (ISSUE-007): a one-shot --resume continues the agent's
            // own conversation context, so the transcript is NOT re-embedded in
            // the prompt on that path.
            val claudeResumeId = resumeSessionId
            resumeSessionId = null
            activeProjectId = projectId
            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(
                prompt,
                if (claudeResumeId.isNullOrBlank()) conversationHistory else emptyList(),
                guestWorkspacePath,
                projectKind,
            )

            val command = claudePrintCommand(
                executable = launch.executable,
                model = launch.environment["ANTHROPIC_MODEL"] ?: provider.model,
                prompt = contextPrompt,
                resumeSessionId = claudeResumeId,
            )
            AppLog.d("ClaudeBridge", "Launching command: $command")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                launch.environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            coroutineScope {
                val permissionWatcher = launch { watchPermissionRequests(sessionId) }
                var lastDiagnostic = ""
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                // The single shared tail loop (ISSUE-013) replaces this bridge's
                // private copy of the follow-the-output-file polling loop.
                OutputFileTailer.tailLines(
                    file = nativeProcess.outputFile,
                    isAlive = process::isAlive,
                    onLine = { line ->
                        AppLog.d("ClaudeBridge", "OUTPUT: $line")
                        ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                            process.destroyForcibly()
                            throw ProviderSessionException(reason)
                        }
                        if (!consumeClaudeEvent(sessionId, line)) {
                            lastDiagnostic = line.takeLast(500)
                            terminalStatus(line)?.let { (title, detail) ->
                                eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, title, detail))
                            }
                        }
                    },
                    onTrailing = { trailing ->
                        trailing.trim().takeIf(String::isNotBlank)?.let { line ->
                            AppLog.d("ClaudeBridge", "TRAILING OUTPUT: $line")
                            if (!consumeClaudeEvent(sessionId, line)) lastDiagnostic = line.takeLast(500)
                        }
                    },
                )
                val exit = process.waitFor()
                AppLog.d("ClaudeBridge", "Process exited with code $exit")
                permissionWatcher.cancelAndJoin()
                pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                    permission.response.writeText("deny")
                    pending.remove(permission.request.approvalId)
                }
                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    AppLog.d("ClaudeBridge", "Changed files: $changed")
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                    acceptLastChanges(projectId)
                }
                if (exit == 0) {
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntime(
                        completed = true,
                        projectName = projectSlug,
                        detail = "Claude Code finished the task in $projectSlug.",
                    )
                } else {
                    if (userStopRequested) throw ProviderSessionException("Stopped by user")
                    error(lastDiagnostic.ifBlank { "Claude Code stopped with exit code $exit" })
                }
            }
        }.onFailure { error ->
            AppLog.e("ClaudeBridge", "Session failed", error)
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        formatGateway?.close()
        sovereignProxy?.close()
        TaskWakeLocks.release("session:$sessionId")
        ThermalMonitor.stop()
        activeProcess = null
        activeSessionId = null
        activeProjectId = null
        RuntimeTaskController.unregister(sessionId)
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val permission = pending.remove(request.approvalId) ?: return@withContext
        permission.response.writeText(if (approved) "allow" else "deny")
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val manifest = File(checkpoint, "changes.json")
        if (!backup.isDirectory || !manifest.isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrElse { return@withContext false }.filterNot(checkpoints::isInternalRuntimePath)

        paths.forEach { path ->
            // A file skipped by the size caps has no baseline; touching it would
            // be destructive (ISSUE-040), so its current version stays.
            if (checkpoints.isUndoUnavailable(projectId, path)) return@forEach
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        if (checkpoints.isUndoUnavailable(projectId, path)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    /**
     * Watches the runtime-bridge directory the guest hook writes to and turns
     * each `.request` file into a policy decision (ISSUE-001).
     *
     * Every request is handled in its own child coroutine so one slow approval
     * never blocks the others. The policy itself lives in [AgentPermissions];
     * this layer only wires files, events and timeouts together. All failure
     * paths fail CLOSED: an unreadable request, a parse failure, or a timeout
     * writes "deny" — never "allow".
     */
    private suspend fun CoroutineScope.watchPermissionRequests(sessionId: String) {
        val bridge = File(context.filesDir, "runtime-bridge")
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        while (coroutineContext.isActive) {
            bridge.listFiles { file -> file.name.endsWith(".request") }.orEmpty().forEach { file ->
                val approvalId = file.name.removeSuffix(".request")
                if (inFlight.add(approvalId)) {
                    launch { runCatching { handlePermissionRequest(sessionId, file) } }
                }
            }
            delay(50)
        }
    }

    private suspend fun handlePermissionRequest(sessionId: String, requestFile: File) {
        val approvalId = requestFile.name.removeSuffix(".request")
        val responseFile = File(requestFile.parentFile, "$approvalId.response")
        val raw = runCatching { requestFile.readText() }.getOrNull()
        // The hook only needs the response; drop the request so a poll loop can
        // never replay it.
        runCatching { requestFile.delete() }
        if (raw == null) {
            // FAIL-CLOSED: an unreadable request is denied, never allowed.
            runCatching { responseFile.writeText("deny") }
            eventBus.emit(
                RuntimeEvent.RuntimeLog(
                    sessionId,
                    "Permission request blocked",
                    "A tool request from the agent could not be read and was blocked.",
                ),
            )
            return
        }
        decidePermission(sessionId, approvalId, raw, responseFile)
    }

    private suspend fun decidePermission(
        sessionId: String,
        approvalId: String,
        raw: String,
        responseFile: File,
    ) {
        val mode = autonomyProvider()
        val parsed = AgentPermissions.parsePermissionRequest(raw)
        if (parsed == null) {
            // FAIL-CLOSED (ISSUE-001): a corrupt or unparseable request is denied.
            runCatching { responseFile.writeText("deny") }
            eventBus.emit(
                RuntimeEvent.RuntimeLog(
                    sessionId,
                    "Permission request blocked",
                    "A tool request from the agent was malformed and was blocked.",
                ),
            )
            return
        }
        val risk = AgentPermissions.classifyRisk(parsed.toolName, parsed.command)
        val request = ToolRequest(
            approvalId = approvalId,
            sessionId = sessionId,
            toolName = parsed.toolName,
            explanation = parsed.explanation,
            affectedPaths = parsed.paths,
            commandPreview = parsed.command,
            risk = risk,
        )
        if (AgentPermissions.shouldAutoApprove(mode, risk)) {
            runCatching { responseFile.writeText("allow") }
            eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, parsed.toolName, request.explanation))
            return
        }
        // Interactive: surface the request to the user and wait, with a
        // fail-closed timeout. respondToApproval answers by removing the entry.
        pending[approvalId] = PendingPermission(request, responseFile)
        eventBus.emit(RuntimeEvent.ToolRequested(sessionId, request))
        val deadline = android.os.SystemClock.elapsedRealtime() + APPROVAL_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline &&
            kotlin.coroutines.coroutineContext.isActive &&
            pending.containsKey(approvalId)
        ) {
            delay(APPROVAL_POLL_MS)
        }
        if (pending.remove(approvalId) != null) {
            // Timed out with no user decision: deny and tell the UI.
            runCatching { responseFile.writeText("deny") }
            eventBus.emit(RuntimeEvent.ToolRejected(sessionId, approvalId))
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeClaudeJsonEvent(sessionId, json)
        return true
    }

    /**
     * Forwards an observed Claude Code `session_id` (init/result events) to the
     * app so it can be persisted per chat and reused for a native --resume
     * after a process death (ISSUE-007).
     */
    private fun reportConversationId(json: JSONObject) {
        val id = json.optString("session_id").trim().takeUnless(String::isEmpty) ?: return
        activeProjectId?.let { projectId -> saveConversationId(projectId, id) }
    }

    private suspend fun consumeClaudeJsonEvent(sessionId: String, json: JSONObject) {
        when (json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeClaudeJsonEvent(sessionId, it) }
            "system" -> when (json.optString("subtype")) {
                "init" -> reportConversationId(json)
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.optString("decision_reason").ifBlank { json.optString("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.optString("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "thinking_delta" -> delta.optString("thinking").takeIf(String::isNotEmpty)?.let {
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
            }
            "content_block_stop" -> {
                if (streamedThinking.isNotBlank()) {
                    emitReasoningSummary(sessionId, streamedThinking.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.optString("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            if (currentThinkingBlockId == 0L || streamedThinking.toString() != it) {
                                currentThinkingBlockId += 1
                                streamedThinking.clear()
                                streamedThinking.append(it)
                                emitReasoningSummary(
                                    sessionId,
                                    it,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> emitToolStarted(sessionId, block)
                    }
                }
                streamedText.clear()
                // Some Anthropic-compatible providers omit Claude Code's final
                // `result` envelope. An assistant end_turn is still authoritative;
                // tool_use means the agent must remain active for another turn.
                if (message.optString("stop_reason") == "end_turn") {
                    emitCompletedOnce(sessionId)
                    terminateActiveProcessGracefully()
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        val toolId = block.optString("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val result = block.optString("content")
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result)))
                    }
                }
            }
            "result" -> {
                reportConversationId(json)
                if (json.optBoolean("is_error")) {
                    val message = json.optString("result").ifBlank { "Claude Code reported an error" }
                    throw IllegalStateException(message)
                }
                // The structured result is Claude Code's authoritative terminal event.
                // Update the UI immediately instead of waiting for a PRoot/Node wrapper
                // that may remain alive after the answer has already completed.
                emitCompletedOnce(sessionId)
                terminateActiveProcessGracefully()
            }
        }
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val summary = sanitizeForDisplay(text).trim().take(2_000)
        if (summary.isBlank() && !startsNewBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || now - lastThinkingUpdateAt >= 150) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = currentThinkingBlockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitReasoningProgress(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (tokens - lastReasoningTokens >= 25 || now - lastReasoningUpdateAt >= 500) {
            lastReasoningTokens = tokens
            lastReasoningUpdateAt = now
            eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitToolStarted(sessionId: String, block: JSONObject) {
        val id = block.optString("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return
        val name = block.optString("name", "Tool")
        if (id.isNotBlank()) toolNames[id] = name
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "Bash" -> input.optString("command").ifBlank { input.optString("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> input.optString("file_path").ifBlank { input.optString("notebook_path") }
            "Glob" -> input.optString("pattern")
            "Grep" -> input.optString("pattern").let { pattern ->
                input.optString("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" } ?: pattern
            }
            else -> input.optString("description").ifBlank { "Running $name" }
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, sanitizeForDisplay(detail.ifBlank { "Running $name" })))
        pushForegroundProgress("Running $name · ${detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { name }}")
    }

    private fun terminalStatus(line: String): Pair<String, String>? = null

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            // Post the completion notification immediately. Waiting for process
            // teardown is unsafe: the PRoot/Node wrapper can hang after the answer
            // is already done, which would freeze the notification on its last step.
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "Claude Code finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "your project",
                    detail = reason,
                )
            }
        }
    }

    private fun sanitizeForDisplay(value: String): String {
        return value
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("(?i)(authorization|api[_-]?key)\\s*[:=]\\s*[^\\s,}]+"), "${'$'}1: ••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
    }

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        // Filter out the current prompt (last user message), system greeting, and any error messages
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1) // Drop the current prompt which was just added

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
        sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
        sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
        sb.appendLine("Mobile Harness globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
        sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is ProviderSessionException -> message
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    /**
     * Mirrors what Claude Code is doing right now into the foreground-service
     * notification, so the notification panel shows the real task progress.
     * Throttled because each update is a service round-trip.
     */
    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /**
     * Destroys the CLI process, escalating to a force kill if the PRoot/Node
     * wrapper ignores the graceful signal. Without this, a hung wrapper would
     * block session cleanup forever after the answer was already delivered.
     */
    private fun terminateActiveProcessGracefully() {
        val running = activeProcess ?: return
        Thread {
            runCatching {
                running.destroy()
                Thread.sleep(3_000)
                if (running.isAlive) running.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startForegroundRuntime(projectName: String, sessionId: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        // The first completion/failure post wins; later cleanup must not duplicate it.
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            AppLog.w("ClaudeBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private data class PendingPermission(val request: ToolRequest, val response: File)
    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L

        /** Interactive approvals auto-deny after this long with no user answer (ISSUE-001). */
        private const val APPROVAL_TIMEOUT_MS = 60_000L
        private const val APPROVAL_POLL_MS = 250L
    }
}

/**
 * Headless Claude Code invocation (ISSUE-007): pure command assembly so the
 * flag set — including the one-shot `--resume <id>` used to continue an
 * interrupted task natively — stays JVM-testable without spawning a process.
 */
internal fun claudePrintCommand(
    executable: String,
    model: String,
    prompt: String,
    resumeSessionId: String? = null,
): List<String> = buildList {
    add(executable)
    add("--bare")
    add("-p")
    add(prompt)
    add("--output-format")
    add("stream-json")
    add("--include-partial-messages")
    add("--verbose")
    add("--model")
    add(model)
    add("--max-turns")
    add("25")
    if (!resumeSessionId.isNullOrBlank()) {
        add("--resume")
        add(resumeSessionId)
    }
}
