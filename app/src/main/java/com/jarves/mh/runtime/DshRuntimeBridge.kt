package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RecoveryFailureInfo
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.RuntimeRecoveryContext
import com.jarves.mh.model.RuntimeRecoveryProfile
import com.jarves.mh.model.ToolRequest
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official DeepSeek Harness (`dsh`) in headless
 * one-shot mode: `dsh --profile headless "<task>"`.
 *
 * Verified contract (dsh 0.1.2-rc.1): the final answer goes to stdout, provider
 * reasoning deltas stream under a `dsh: reasoning:` heading, failures print
 * `dsh: <CODE>: <message>`, exit 0 means the turn completed. Because the
 * native launcher merges stdout+stderr into one capture file, this bridge
 * separates the streams by the `dsh:` diagnostic prefix.
 *
 * Auth and model selection are fully non-interactive: keys travel in the
 * process environment (`DEEPSEEK_API_KEY` for the native route, one shared
 * `MH_DSH_API_KEY` for hand-declared routes) and `$DSH_HOME/settings.yaml`
 * carries the default model plus any custom provider route.
 */
class DshRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var liveSession: LiveSession? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L
    @Volatile private var sawToolCall: Boolean = false
    @Volatile private var sawMutatingTool: Boolean = false
    @Volatile private var lastSessionId: String? = null
    @Volatile private var lastFailureRecovery: RecoveryFailureInfo? = null

    override val recoveryProfile: RuntimeRecoveryProfile = DSH_RECOVERY_PROFILE

    override fun recoveryContext(sessionId: String, projectId: String): RuntimeRecoveryContext? {
        // Report the real tracked flags even after the process died and
        // `activeSessionId` was nulled: `lastSessionId` still names the failed
        // session until the next `startSession` begins.
        val isCurrent = activeSessionId == sessionId
        val isLast = lastSessionId == sessionId
        if (!isCurrent && !isLast) return null
        return RuntimeRecoveryContext(
            sessionId = sessionId,
            inPlaceContinuationAvailable = activeProcess?.isAlive == true && isCurrent,
            persistedHandle = null,
            toolsExecuted = sawToolCall,
            mutatingToolsExecuted = sawMutatingTool,
            interruptedToolOutcomeKnown = false,
            checkpointAvailable = false,
            userStopped = userStopRequested,
        )
    }

    override suspend fun recover(action: RecoveryAction, decision: RecoveryDecision): Boolean {
        return when (decision) {
            RecoveryDecision.CONTINUE_IN_PLACE ->
                // Same session, no new process: write the continuation to the live
                // writer. Returns false if the process died or the session changed.
                sendContinuation(action.sessionId, action.instruction)
            RecoveryDecision.RETRY_NEW_SESSION_SAFE -> {
                // Defense in depth on top of the policy: never auto-restart a turn
                // that already ran tools, even if the caller chose the "safe" path.
                val context = recoveryContext(action.sessionId, action.projectId) ?: return false
                if (context.toolsExecuted) return false
                startSession(
                    action.projectId,
                    action.projectSlug,
                    action.projectKind,
                    action.originalPrompt,
                    action.history,
                    action.provider,
                )
                true
            }
            else -> false
        }
    }

    private fun sendContinuation(sessionId: String, instruction: String): Boolean {
        // Read a single atomic snapshot so a concurrent session transition cannot
        // pair a stale id with a fresh process/writer and write the wrong session.
        val live = liveSession ?: return false
        if (live.sessionId != sessionId || !live.process.isAlive) return false
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", SDK_CONTINUATION_PROMPT_ID)
            .put("method", "session/prompt")
            .put(
                "params",
                JSONObject()
                    .put("sessionId", sessionId)
                    .put(
                        "contentBlocks",
                        JSONArray().put(JSONObject().put("type", "text").put("text", instruction)),
                    ),
            )
        return runCatching {
            synchronized(live.writer) {
                live.writer.write(frame.toString())
                live.writer.newLine()
                live.writer.flush()
            }
        }.isSuccess
    }

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        lastSessionId = sessionId
        sawToolCall = false
        sawMutatingTool = false
        lastFailureRecovery = null
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress(R.string.rt_bridge_starting, "DeepSeek Harness")
        val secret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank()) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."))
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings.",
                ),
            )
            return@withContext sessionId
        }

        var openRouterGateway: OpenRouterRoutingGateway? = null
        runCatching {
            RuntimeTaskController.stopAction = {
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
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val baseRoute = DshRouteMapper.forProfile(provider)
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret).start() else null
            val route = if (openRouterGateway != null && baseRoute.custom != null) {
                baseRoute.copy(custom = baseRoute.custom.copy(baseUrl = openRouterGateway!!.url))
            } else {
                baseRoute
            }
            writeDshSettings(installed.rootfs, route, provider)
            val environment = linkedMapOf(
                "DSH_HOME" to DSH_HOME_GUEST_PATH,
                // PocketDev already confines the whole Linux guest with PRoot. Let dsh
                // use every tool inside that boundary without an unavailable approval UI.
                "DSH_PERMISSION_MODE" to "danger-full-access",
                route.keyEnv to secret,
            )
            if (route.keyEnv != FALLBACK_KEY_ENV) environment.remove(FALLBACK_KEY_ENV)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = listOf("/usr/local/bin/dsh", "--profile", "sdk")
            Log.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                // dsh's editor saves through an atomic temp-file rename. PRoot's
                // hard-link emulation turns that rename into a dangling `.l2s`
                // symlink after the temp file is removed, losing the real file.
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            val sdkResult = runSdkSession(
                process = process,
                sessionId = sessionId,
                route = route,
                model = provider.model.ifBlank { route.defaultModel },
                guestWorkspacePath = guestWorkspacePath,
                prompt = contextPrompt,
            )
            val exit = process.waitFor()
            Log.d("DshBridge", "SDK process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("DshBridge", "Changed files: $changed")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            // `turn/end` is the authoritative result. Some Android/PRoot builds keep
            // the SDK wrapper alive after acknowledging shutdown, so cleanup may end
            // it with SIGKILL and produce 137 even though the turn completed normally.
            if (sdkResult.completed && sdkResult.failure.isBlank() && !userStopRequested) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = AppStrings.get(context, R.string.rt_bridge_finished_in, "DeepSeek Harness", projectSlug),
                )
            } else {
                if (userStopRequested) throw DshSessionException("Stopped by user")
                error(sdkResult.failure.ifBlank { "DeepSeek Harness stopped with exit code $exit" })
            }
        }.onFailure { error ->
            Log.e("DshBridge", "Session failed", error)
            val message = friendlyError(error)
            // An ordinary unexpected exit (no outstanding continuation) must still
            // carry side-effect facts so the manual retry card can warn. Only a user
            // stop keeps the metadata null; a continuation failure already recorded
            // `lastFailureRecovery` and wins verbatim.
            val recovery = lastFailureRecovery ?: if (userStopRequested) null else RecoveryFailureInfo(
                retryAvailable = true,
                sideEffectsPossible = sawMutatingTool,
                toolsExecuted = sawToolCall,
            )
            emitFailureOnce(sessionId, message, recovery)
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
        openRouterGateway?.close()
        liveSession = null
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    private suspend fun runSdkSession(
        process: Process,
        sessionId: String,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
        prompt: String,
    ): DshSdkRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val writer = process.outputStream.bufferedWriter()
        liveSession = LiveSession(sessionId, process, writer)
        val parser = DshSdkProtocolParser(sessionId)
        var outputOffset = 0L
        val pendingOutput = StringBuilder()
        var promptSent = false
        var sawRunning = false
        var completed = false
        var sawActivity = false
        var shutdownSent = false
        var shutdownSentAt = 0L
        var forcedShutdown = false
        var inputClosed = false
        var discardingOversizedLine = false
        var failure = ""
        var finalAnswer = FinalAnswerStateMachine()
        var nudgeSentAt = 0L
        val retryState = DshTransportRetryState()
        val continuation = DshContinuationState()
        var continuationDueAt = 0L
        var continuationSentAt = 0L
        var continuationAttempt = 0
        var continuationMessage = ""

        fun send(method: String, id: Int, params: JSONObject? = null) {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            synchronized(writer) {
                writer.write(frame.toString())
                writer.newLine()
                writer.flush()
            }
        }

        fun closeInput() {
            if (inputClosed) return
            inputClosed = true
            runCatching { synchronized(writer) { writer.close() } }
        }

        fun finishTurnAndShutdown() {
            if (shutdownSent) return
            completed = turnCompleted(sawActivity, failure)
            if (!completed && failure.isBlank()) {
                failure = "DeepSeek Harness stopped before processing the prompt"
            }
            shutdownSent = true
            shutdownSentAt = android.os.SystemClock.elapsedRealtime()
            send("shutdown", SDK_SHUTDOWN_ID)
        }

        fun failContinuation(message: String) {
            continuation.onTerminal()
            failure = message
            lastFailureRecovery = RecoveryFailureInfo(
                retryAvailable = true,
                sideEffectsPossible = sawMutatingTool,
                toolsExecuted = sawToolCall,
            )
            finishTurnAndShutdown()
        }

        suspend fun emitNoFinalAnswerThenFinish() {
            if (shutdownSent) return
            if (userStopRequested) {
                finishTurnAndShutdown()
                return
            }
            // Emit the closure before shutdown so the VM still accepts it; after
            // SessionCompleted late AssistantDelta events are dropped.
            eventBus.emit(
                RuntimeEvent.AssistantDelta(
                    sessionId,
                    AppStrings.get(context, R.string.md_reason_dsh_no_final_answer),
                ),
            )
            finishTurnAndShutdown()
        }

        suspend fun sendNudge() {
            if (userStopRequested) {
                finishTurnAndShutdown()
                return
            }
            // Same-session prompt queuing is confirmed for the pinned dsh SDK
            // (0.1.2-rc.1) per its published docs. Reusing the session avoids
            // restarting a fresh one, which would re-run tools and duplicate work.
            pushForegroundProgress(R.string.vm_dsh_final_answer_retry)
            val wrote = runCatching {
                send(
                    method = "session/prompt",
                    id = SDK_NUDGE_PROMPT_ID,
                    params = JSONObject()
                        .put("sessionId", sessionId)
                        .put(
                            "contentBlocks",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "text")
                                    .put("text", "You ended the turn without a user-facing answer. Provide the final status/answer now."),
                            ),
                        ),
                )
            }.isSuccess
            if (wrote) {
                nudgeSentAt = android.os.SystemClock.elapsedRealtime()
            } else {
                // The nudge write failed (e.g. Broken pipe on a dying session).
                // Close with the localized fallback instead of letting the IO
                // error escape runSdkSession and mask the turn's real failure.
                Log.w("DshBridge", "Nudge write failed; closing with localized fallback")
                emitNoFinalAnswerThenFinish()
            }
        }

        send(
            method = "initialize",
            id = SDK_INITIALIZE_ID,
            params = JSONObject()
                .put("cwd", guestWorkspacePath)
                .put("provider", route.name)
                .put("model", model),
        )

        suspend fun handle(protocolEvent: DshSdkProtocolEvent) {
            when (protocolEvent) {
                DshSdkProtocolEvent.Initialized -> if (!promptSent) {
                    send(
                        method = "session/prompt",
                        id = SDK_PROMPT_ID,
                        params = JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "contentBlocks",
                                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                            ),
                    )
                    promptSent = true
                }
                DshSdkProtocolEvent.PromptAccepted -> Unit
                DshSdkProtocolEvent.NudgeAccepted -> Unit
                is DshSdkProtocolEvent.NudgeRejected -> {
                    // The SDK refused the follow-up prompt. Close with a visible
                    // fallback instead of restarting a session (which duplicates
                    // tool work). Deliberately non-fatal, and guarded so a late
                    // rejection after the watchdog already fired cannot re-emit.
                    // During continuation backoff a stale rejection from the
                    // original turn must not end the session either.
                    if (!continuation.suppressStaleTurnEvents() && !shutdownSent) {
                        when (finalAnswer.step(FinalAnswerEvent.NUDGE_REJECTED)) {
                            FinalAnswerAction.EMIT_FALLBACK -> emitNoFinalAnswerThenFinish()
                            else -> Unit
                        }
                    }
                }
                is DshSdkProtocolEvent.ContinuationRejected -> {
                    // The SDK refused the in-place continuation prompt. Non-fatal on
                    // its own: the 60 s continuation watchdog below takes over and
                    // ends the turn in a terminal failure path rather than spinning.
                    if (!shutdownSent) {
                        Log.w("DshBridge", "Continuation rejected: ${protocolEvent.message}")
                    }
                }
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        // Do NOT clear `continuationSentAt` here: `running` alone is
                        // not meaningful progress. The continuation watchdog is an
                        // *inactivity* bound, not a first-event bound — it must fire
                        // when a resumed continuation starts running but then hangs,
                        // so it is only reset on real progress (text/reasoning/tool/
                        // turn-end), never on a bare running signal.
                        pushForegroundProgress(R.string.rt_bridge_working, "DeepSeek Harness")
                        finalAnswer.step(FinalAnswerEvent.RUNNING)
                    } else if (!continuation.suppressIdle() &&
                        shouldEvaluateNudgeOnIdle(failure, sawRunning, shutdownSent)
                    ) {
                        when (finalAnswer.step(FinalAnswerEvent.IDLE)) {
                            FinalAnswerAction.SEND_NUDGE -> sendNudge()
                            FinalAnswerAction.FINISH -> finishTurnAndShutdown()
                            FinalAnswerAction.EMIT_FALLBACK -> emitNoFinalAnswerThenFinish()
                            FinalAnswerAction.CONTINUE -> Unit
                        }
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    continuation.onMeaningfulSignal()
                    sawActivity = true
                    continuationSentAt = 0L
                    finalAnswer.step(FinalAnswerEvent.REASONING)
                    if (!shutdownSent) {
                        emitReasoningSummary(
                            sessionId = sessionId,
                            text = protocolEvent.text,
                            blockId = protocolEvent.blockId,
                            startsNewBlock = protocolEvent.startsNewBlock,
                            isFinal = protocolEvent.isFinal,
                            force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                        )
                    }
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    continuation.onMeaningfulSignal()
                    sawActivity = true
                    continuationSentAt = 0L
                    sawToolCall = true
                    if (isMutatingTool(protocolEvent.name)) sawMutatingTool = true
                    finalAnswer.step(FinalAnswerEvent.TOOL_ACTIVITY)
                    if (!shutdownSent) {
                        eventBus.emit(
                            RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                        )
                    }
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    continuation.onMeaningfulSignal()
                    sawActivity = true
                    continuationSentAt = 0L
                    sawToolCall = true
                    if (isMutatingTool(protocolEvent.name)) sawMutatingTool = true
                    finalAnswer.step(FinalAnswerEvent.TOOL_COMPLETED)
                    if (!shutdownSent) {
                        eventBus.emit(
                            RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary),
                        )
                    }
                }
                is DshSdkProtocolEvent.AssistantText -> if (protocolEvent.text.isNotEmpty()) {
                    continuation.onMeaningfulSignal()
                    sawActivity = true
                    continuationSentAt = 0L
                    finalAnswer.step(FinalAnswerEvent.ASSISTANT_TEXT)
                    if (!shutdownSent) {
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                    }
                }
                is DshSdkProtocolEvent.Failed -> {
                    // A transport error is no longer terminal on arrival: the SDK
                    // session is still alive, so we continue it in place instead of
                    // restarting a fresh session (which would re-run tools).
                    continuationSentAt = 0L
                    val decision = retryState.onFailure(protocolEvent.code, protocolEvent.message, userStopRequested)
                    when (decision.action) {
                        DshTransportRetryAction.RETRY -> {
                            continuation.onTransportRetryScheduled()
                            // Reset the per-turn final-answer state at retry scheduling
                            // time (not only after the continuation write) so a trailing
                            // idle/turn-end from the original turn cannot nudge, complete,
                            // or fail the session during the backoff window. The session
                            // id and the whole-turn tool flags are kept.
                            val turn = resetContinuationTurn()
                            sawRunning = turn.sawRunning
                            finalAnswer = turn.finalAnswer
                            continuationDueAt = android.os.SystemClock.elapsedRealtime() + decision.delayMs
                            continuationAttempt = decision.attempt
                            continuationMessage = protocolEvent.message
                            eventBus.emit(
                                RuntimeEvent.RecoveryStateChanged(
                                    sessionId,
                                    RecoveryDecision.CONTINUE_IN_PLACE,
                                    decision.attempt,
                                    SDK_MAX_TRANSPORT_RETRIES,
                                    protocolEvent.message,
                                ),
                            )
                        }
                        DshTransportRetryAction.EXHAUSTED -> failContinuation(protocolEvent.message)
                        DshTransportRetryAction.USER_STOP,
                        DshTransportRetryAction.NOT_TRANSPORT,
                        -> {
                            continuation.onTerminal()
                            failure = protocolEvent.message
                            finishTurnAndShutdown()
                        }
                    }
                }
                DshSdkProtocolEvent.TurnCompleted -> {
                    // Some dsh builds finish a turn without publishing the expected
                    // follow-up `session.status: idle` notification. Treat turn/end
                    // as authoritative so the UI cannot remain stuck in Running.
                    if (continuation.suppressStaleTurnEvents()) {
                        // A stale turn-end from the original (failed) turn during
                        // backoff must not complete or fail the session before the
                        // continuation runs.
                        Unit
                    } else {
                        // A turn-end is itself a meaningful continuation signal: in
                        // AWAITING_SIGNAL it restores normal processing instead of
                        // being suppressed forever.
                        continuation.onMeaningfulSignal()
                        sawActivity = true
                        continuationSentAt = 0L
                        when (finalAnswer.step(FinalAnswerEvent.TURN_END)) {
                            FinalAnswerAction.SEND_NUDGE -> sendNudge()
                            FinalAnswerAction.FINISH -> finishTurnAndShutdown()
                            FinalAnswerAction.EMIT_FALLBACK -> emitNoFinalAnswerThenFinish()
                            FinalAnswerAction.CONTINUE -> Unit
                        }
                    }
                }
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeInput()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
            // Nudge watchdog: if the follow-up prompt produced no turn end within
            // the bound, close out instead of spinning forever on a withheld
            // shutdown. It must bound both outcomes: without text it emits the
            // localized fallback; with text it is a normal completion. Otherwise
            // a nudge that answered but never sent turn/end would leave the loop
            // spinning or exit as a misleading failure after showing the answer.
            if (
                !continuation.suppressStaleTurnEvents() &&
                !shutdownSent &&
                nudgeSentAt > 0L &&
                finalAnswer.nudgeOutstanding() &&
                android.os.SystemClock.elapsedRealtime() - nudgeSentAt >= SDK_NUDGE_WATCHDOG_MS
            ) {
                when (finalAnswer.onNudgeWatchdogExpired()) {
                    FinalAnswerAction.FINISH -> finishTurnAndShutdown()
                    FinalAnswerAction.EMIT_FALLBACK -> emitNoFinalAnswerThenFinish()
                    else -> Unit
                }
            }
            // Continuation watchdog: if a transport-retry continuation produced no
            // turn/end or text within the bound, fail terminally (no infinite loop).
            if (
                !shutdownSent &&
                continuationSentAt > 0L &&
                android.os.SystemClock.elapsedRealtime() - continuationSentAt >= SDK_CONTINUATION_WATCHDOG_MS
            ) {
                failContinuation(continuationMessage.ifBlank { "DeepSeek Harness did not resume after a transport error" })
            }
            // Send a scheduled continuation once its delay has elapsed.
            if (
                !shutdownSent &&
                continuationDueAt > 0L &&
                android.os.SystemClock.elapsedRealtime() >= continuationDueAt
            ) {
                continuationDueAt = 0L
                val wrote = runCatching {
                    send(
                        method = "session/prompt",
                        id = SDK_CONTINUATION_PROMPT_ID,
                        params = JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "contentBlocks",
                                JSONArray().put(
                                    JSONObject().put("type", "text").put("text", DSH_CONTINUATION_INSTRUCTION),
                                ),
                            ),
                    )
                }.isSuccess
                if (wrote) {
                    // Leave backoff: the continuation is now outstanding. The
                    // per-turn flags were already reset at scheduling time; reset
                    // again here for defense in depth so a stray trailing
                    // idle/answer from the original turn can never complete or
                    // nudge the continuation before it has produced its own signal.
                    continuation.onContinuationSent()
                    continuationSentAt = android.os.SystemClock.elapsedRealtime()
                    val turn = resetContinuationTurn()
                    sawRunning = turn.sawRunning
                    finalAnswer = turn.finalAnswer
                    eventBus.emit(
                        RuntimeEvent.RecoveryStateChanged(
                            sessionId,
                            RecoveryDecision.CONTINUE_IN_PLACE,
                            continuationAttempt,
                            SDK_MAX_TRANSPORT_RETRIES,
                            continuationMessage,
                        ),
                    )
                } else {
                    // The continuation write failed (e.g. broken pipe on a dying
                    // session). Fail terminally with recovery info.
                    Log.w("DshBridge", "Continuation write failed; failing the turn")
                    failContinuation(continuationMessage.ifBlank { "DeepSeek Harness could not be resumed after a transport error" })
                }
            }
            if (
                process.isAlive &&
                !forcedShutdown &&
                shutdownSentAt > 0L &&
                android.os.SystemClock.elapsedRealtime() - shutdownSentAt >= SDK_SHUTDOWN_TIMEOUT_MS
            ) {
                closeInput()
                forcedShutdown = true
                Log.w("DshBridge", "SDK acknowledged completion but did not exit; stopping its runtime wrapper")
                process.destroyForcibly()
            }
            val available = nativeProcess.outputFile.length() - outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                file.seek(outputOffset)
                file.read(bytes)
            }
            if (count <= 0) continue
            outputOffset += count
            var decoded = bytes.decodeToString(0, count)
            if (discardingOversizedLine) {
                val end = decoded.indexOf('\n')
                if (end < 0) continue
                decoded = decoded.substring(end + 1)
                discardingOversizedLine = false
            }
            pendingOutput.append(decoded)
            var newline = pendingOutput.indexOf("\n")
            while (newline >= 0) {
                val line = pendingOutput.substring(0, newline).trimEnd('\r')
                pendingOutput.delete(0, newline + 1)
                if (line.isNotBlank() && !parser.consumeDuplicateAssistantMessage(line)) {
                    handle(parser.parseLine(line))
                }
                newline = pendingOutput.indexOf("\n")
            }
            if (pendingOutput.length > MAX_DSH_PROTOCOL_LINE_CHARS) {
                val duplicate = parser.consumeDuplicateAssistantMessage(pendingOutput)
                pendingOutput.clear()
                discardingOversizedLine = true
                if (!duplicate) {
                    handle(DshSdkProtocolEvent.Failed("DeepSeek Harness sent an oversized response"))
                }
            }
        }
        pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let {
            if (!parser.consumeDuplicateAssistantMessage(it)) handle(parser.parseLine(it))
        }
        // If a continuation was still outstanding (scheduled or sent) when the
        // process died, fail with recovery info rather than reporting a generic
        // exit-code failure.
        if (failure.isBlank() && (continuationSentAt > 0L || continuationDueAt > 0L)) {
            failure = continuationMessage.ifBlank { "DeepSeek Harness did not resume after a transport error" }
            lastFailureRecovery = RecoveryFailureInfo(
                retryAvailable = true,
                sideEffectsPossible = sawMutatingTool,
                toolsExecuted = sawToolCall,
            )
        }
        closeInput()
        return DshSdkRunResult(completed = completed, failure = failure)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Headless one-shot runs expose no approval channel; nothing is ever requested.
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

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) return@withContext false
        paths.forEach { path ->
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

    private fun writeDshSettings(rootfs: File, route: DshRoute, provider: ProviderProfile) {
        val home = File(rootfs, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        val body = buildString {
            appendLine("agent-default-model:")
            appendLine("  provider: ${route.name}")
            appendLine("  model: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            if (route.custom != null) {
                appendLine("llm-pi-ai:")
                appendLine("  providers:")
                appendLine("    ${route.name}:")
                appendLine("      apiKeyEnv: ${route.keyEnv}")
                appendLine("      api: ${route.custom.api}")
                appendLine("      baseURL: ${yamlQuote(route.custom.baseUrl)}")
                appendLine("      models:")
                appendLine("        - id: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            }
        }
        File(home, "settings.yaml").writeText(body)
    }

    private fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Reasoning arrives as a cumulative value. Skip throttled frames before doing
        // any text work so a long-running agent cannot repeatedly scan a multi-MB string.
        if (!force && now - lastThinkingUpdateAt < 400) return
        val summary = compactDshText(text, MAX_DSH_REASONING_BUFFER, MAX_DSH_REASONING_SUMMARY)
        if (summary.isBlank()) return
        lastThinkingUpdateAt = now
        eventBus.emit(
            RuntimeEvent.ReasoningSummary(
                sessionId = sessionId,
                summary = summary,
                blockId = blockId,
                startsNewBlock = startsNewBlock,
                isFinal = isFinal,
            ),
        )
        pushForegroundProgress(R.string.rt_bridge_thinking)
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            // A completed session leaves no recovery facts behind: drop the handle so a
            // later query cannot read stale tool flags for it.
            if (lastSessionId == sessionId) lastSessionId = null
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                detail = AppStrings.get(context, R.string.rt_bridge_finished, "DeepSeek Harness"),
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String, recovery: RecoveryFailureInfo? = null) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason, recovery))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException && message.isMeaningfulDshText() -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached DeepSeek Harness. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            !message.isMeaningfulDshText() -> "DeepSeek Harness could not start."
            else -> message.take(500)
        }
    }

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

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
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        }
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

    /** Localized variant; resolves the string only when the throttle allows an update. */
    private fun pushForegroundProgress(@androidx.annotation.StringRes id: Int, vararg args: Any) {
        if (activeSessionId == null) return
        if (android.os.SystemClock.elapsedRealtime() - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        pushForegroundProgress(AppStrings.get(context, id, *args))
    }

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

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
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
            Log.w("DshBridge", "Could not post task result notification", error)
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

    private class DshSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val DSH_HOME_GUEST_PATH = "/root/.dsh"
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_TIMEOUT_MS = 3_000L
        private const val SDK_NUDGE_WATCHDOG_MS = 30_000L
        private const val SDK_CONTINUATION_WATCHDOG_MS = 60_000L
        private const val SDK_MAX_TRANSPORT_RETRIES = 2
    }
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

/**
 * Consistent snapshot of the live session (id + process + writer) read/written
 * as a single `@Volatile` object so `recover` can never pair a stale id with a
 * fresh process/writer during a session transition.
 */
private data class LiveSession(
    val sessionId: String,
    val process: Process,
    val writer: BufferedWriter,
)

/**
 * JSON-RPC id reserved for the follow-up `session/prompt` nudge sent when a turn
 * completes without a user-facing answer. It must not collide with the primary
 * prompt id (2), so its acceptance/rejection can be classified independently.
 */
private const val SDK_NUDGE_PROMPT_ID = 4

/** JSON-RPC id for the in-place `session/prompt` continuation after a transport error. */
private const val SDK_CONTINUATION_PROMPT_ID = 5

/** True only when the turn ran, produced no assistant text since the last tool,
 * and we have not already sent the follow-up nudge. */
internal fun needsFinalAnswerNudge(
    sawAssistantText: Boolean,
    sawActivity: Boolean,
    followUpNudgeSent: Boolean,
): Boolean = sawActivity && !sawAssistantText && !followUpNudgeSent

/**
 * Computes the `completed` flag recorded when a turn ends. A non-blank failure
 * always yields `false` — the turn is terminal-but-failed — regardless of any
 * activity seen before the failure. This is the pure core of the result
 * computation in `finishTurnAndShutdown`; the shutdown side effect stays there.
 */
internal fun turnCompleted(sawActivity: Boolean, failure: String): Boolean =
    sawActivity && failure.isBlank()

/**
 * True when a trailing `session.status: idle` should still be evaluated for the
 * no-final-answer nudge. A failed turn must not nudge: its `turn/end error(...)`
 * already recorded [failure], and the idle that follows belongs to a dying
 * session, so nudging a dead stream can throw (e.g. Broken pipe) and mask the
 * real failure. The idle is only meaningful after the turn started running and
 * before shutdown has begun.
 */
internal fun shouldEvaluateNudgeOnIdle(failure: String, sawRunning: Boolean, shutdownSent: Boolean): Boolean =
    failure.isBlank() && sawRunning && !shutdownSent

/**
 * Per-turn state an in-place continuation resets: the running flag clears (so a
 * trailing `session.status: idle` from the original turn is ignored until the
 * continuation itself signals running) and the final-answer machine is replaced
 * with a fresh one. The session id and the whole-turn tool flags are deliberately
 * outside this value and are kept across the reset.
 */
internal data class ContinuationTurnReset(
    val sawRunning: Boolean,
    val finalAnswer: FinalAnswerStateMachine,
)

internal fun resetContinuationTurn(): ContinuationTurnReset =
    ContinuationTurnReset(sawRunning = false, finalAnswer = FinalAnswerStateMachine())

/**
 * Which stage of the transport-recovery continuation the session is in. This is
 * the pure core of the "trailing original-turn event" suppression: once a
 * transport retry is scheduled, stale events from the dying original turn
 * (idle, turn/end, nudge rejection, nudge watchdog) must not terminate, nudge,
 * complete, or fail the session before the continuation has run.
 */
internal enum class DshContinuationPhase {
    /** Normal processing: the original (or a resumed) turn is in flight. */
    ORIGINAL_TURN,

    /** A transport retry is scheduled; stale original-turn events are ignored. */
    BACKOFF,

    /** The continuation prompt was written; idle is ignored until the first signal. */
    AWAITING_SIGNAL,

    /** A terminal decision was reached; no further turn events are processed. */
    TERMINAL,
}

/**
 * Pure continuation phase state machine. Free of Android/coroutine types so the
 * whole transition/suppression sequence is unit-testable. The bridge drives it
 * from [runSdkSession]'s event loop and consults its predicates to decide which
 * stale events to ignore.
 */
internal class DshContinuationState {
    var phase: DshContinuationPhase = DshContinuationPhase.ORIGINAL_TURN
        private set

    /** A transport retry was scheduled: enter backoff (unless already terminal). */
    fun onTransportRetryScheduled() {
        if (phase != DshContinuationPhase.TERMINAL) phase = DshContinuationPhase.BACKOFF
    }

    /** The continuation prompt was written successfully. */
    fun onContinuationSent() {
        if (phase != DshContinuationPhase.TERMINAL) phase = DshContinuationPhase.AWAITING_SIGNAL
    }

    /**
     * A meaningful continuation signal (assistant text, reasoning, tool, or a
     * turn-end) arrived: back to normal processing.
     */
    fun onMeaningfulSignal() {
        if (phase == DshContinuationPhase.AWAITING_SIGNAL) phase = DshContinuationPhase.ORIGINAL_TURN
    }

    /** A terminal decision was reached (exhaustion, stop, non-transport, write failure). */
    fun onTerminal() {
        phase = DshContinuationPhase.TERMINAL
    }

    /**
     * True while stale original-turn events (idle, turn/end, nudge rejection,
     * nudge watchdog) must be ignored: during backoff or after a terminal decision.
     * In [DshContinuationPhase.AWAITING_SIGNAL] a turn/end is itself a meaningful
     * signal, so it is not treated as stale here.
     */
    fun suppressStaleTurnEvents(): Boolean =
        phase == DshContinuationPhase.BACKOFF || phase == DshContinuationPhase.TERMINAL

    /**
     * True while an `idle` must be ignored. Idle is ignored in every phase except
     * normal processing, so a stale running+idle pair from the original turn can
     * never reach the nudge/completion path before the continuation signals.
     */
    fun suppressIdle(): Boolean = phase != DshContinuationPhase.ORIGINAL_TURN

    val inBackoff: Boolean get() = phase == DshContinuationPhase.BACKOFF
    val isTerminal: Boolean get() = phase == DshContinuationPhase.TERMINAL
}

internal enum class FinalAnswerPhase { INITIAL, NUDGE_SENT, AFTER_NUDGE, DONE }

internal enum class FinalAnswerEvent {
    ASSISTANT_TEXT,
    REASONING,
    TOOL_ACTIVITY,
    TOOL_COMPLETED,
    TURN_END,
    IDLE,
    RUNNING,
    NUDGE_REJECTED,
}

internal enum class FinalAnswerAction {
    CONTINUE,
    SEND_NUDGE,
    FINISH,
    EMIT_FALLBACK,
}

/**
 * Pure per-session state machine driving the "turn ended without a final answer"
 * recovery. It is intentionally free of coroutine/Android types so the full
 * sequence can be unit-tested.
 *
 * Phases:
 *  - INITIAL: first (only) prompt turn is in flight.
 *  - NUDGE_SENT: follow-up prompt written; the original turn's trailing idle is
 *    still on its way and must be ignored.
 *  - AFTER_NUDGE: the nudge turn has started running (or ended); evaluate its result.
 *  - DONE: a terminal decision was reached; ignore any trailing events.
 */
internal class FinalAnswerStateMachine {
    var phase: FinalAnswerPhase = FinalAnswerPhase.INITIAL
        private set

    private var sawAssistantText = false
    private var sawActivity = false
    private var followUpNudgeSent = false
    private var producedTextAfterNudge = false

    fun step(event: FinalAnswerEvent): FinalAnswerAction = when (event) {
        FinalAnswerEvent.ASSISTANT_TEXT -> {
            sawAssistantText = true
            sawActivity = true
            // Any text after the nudge is the nudge turn's answer, even if the
            // running signal was skipped.
            if (phase == FinalAnswerPhase.NUDGE_SENT || phase == FinalAnswerPhase.AFTER_NUDGE) {
                producedTextAfterNudge = true
            }
            FinalAnswerAction.CONTINUE
        }
        FinalAnswerEvent.REASONING -> {
            sawActivity = true
            FinalAnswerAction.CONTINUE
        }
        FinalAnswerEvent.TOOL_ACTIVITY -> {
            sawActivity = true
            FinalAnswerAction.CONTINUE
        }
        FinalAnswerEvent.TOOL_COMPLETED -> {
            sawActivity = true
            sawAssistantText = false
            FinalAnswerAction.CONTINUE
        }
        FinalAnswerEvent.RUNNING -> {
            if (phase == FinalAnswerPhase.NUDGE_SENT) phase = FinalAnswerPhase.AFTER_NUDGE
            FinalAnswerAction.CONTINUE
        }
        FinalAnswerEvent.NUDGE_REJECTED -> {
            // A rejection arriving after we already decided (watchdog, prior
            // rejection, or a finished turn) must not re-emit the fallback.
            if (phase == FinalAnswerPhase.DONE) FinalAnswerAction.CONTINUE
            else {
                phase = FinalAnswerPhase.DONE
                FinalAnswerAction.EMIT_FALLBACK
            }
        }
        FinalAnswerEvent.TURN_END -> when (phase) {
            FinalAnswerPhase.INITIAL -> {
                if (needsFinalAnswerNudge(sawAssistantText, sawActivity, followUpNudgeSent)) {
                    followUpNudgeSent = true
                    sawAssistantText = false
                    phase = FinalAnswerPhase.NUDGE_SENT
                    FinalAnswerAction.SEND_NUDGE
                } else {
                    phase = FinalAnswerPhase.DONE
                    FinalAnswerAction.FINISH
                }
            }
            FinalAnswerPhase.NUDGE_SENT -> {
                phase = FinalAnswerPhase.AFTER_NUDGE
                evaluateNudge()
            }
            FinalAnswerPhase.AFTER_NUDGE -> evaluateNudge()
            FinalAnswerPhase.DONE -> FinalAnswerAction.CONTINUE
        }
        FinalAnswerEvent.IDLE -> when (phase) {
            FinalAnswerPhase.INITIAL -> {
                if (needsFinalAnswerNudge(sawAssistantText, sawActivity, followUpNudgeSent)) {
                    followUpNudgeSent = true
                    sawAssistantText = false
                    phase = FinalAnswerPhase.NUDGE_SENT
                    FinalAnswerAction.SEND_NUDGE
                } else {
                    phase = FinalAnswerPhase.DONE
                    FinalAnswerAction.FINISH
                }
            }
            // The idle that belongs to the original turn arrives right after its
            // turn/end; ignore it so it cannot finish the turn before the nudge.
            FinalAnswerPhase.NUDGE_SENT -> FinalAnswerAction.CONTINUE
            FinalAnswerPhase.AFTER_NUDGE -> evaluateNudge()
            FinalAnswerPhase.DONE -> FinalAnswerAction.CONTINUE
        }
    }

    /** True while a follow-up nudge is outstanding (sent but not yet decided). */
    fun nudgeOutstanding(): Boolean =
        phase == FinalAnswerPhase.NUDGE_SENT || phase == FinalAnswerPhase.AFTER_NUDGE

    /** Forces the terminal phase so late events from an already-shut-down turn
     * cannot advance the machine or emit a second fallback/answer. */
    fun markDone() {
        phase = FinalAnswerPhase.DONE
    }

    /**
     * Terminal decision when the nudge watchdog expires. With text the nudge is
     * a normal completion (FINISH); without, the localized fallback
     * (EMIT_FALLBACK). Idempotent: once decided, a late nudge event cannot
     * re-decide or emit a second answer.
     */
    fun onNudgeWatchdogExpired(): FinalAnswerAction {
        if (!nudgeOutstanding()) return FinalAnswerAction.CONTINUE
        phase = FinalAnswerPhase.DONE
        return if (producedTextAfterNudge) FinalAnswerAction.FINISH else FinalAnswerAction.EMIT_FALLBACK
    }

    private fun evaluateNudge(): FinalAnswerAction {
        val action = if (producedTextAfterNudge) FinalAnswerAction.FINISH else FinalAnswerAction.EMIT_FALLBACK
        phase = FinalAnswerPhase.DONE
        return action
    }
}


/** dsh provider route resolved from our saved provider profile. */
internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(val api: String, val baseUrl: String)

internal object DshRouteMapper {
    fun forProfile(profile: ProviderProfile): DshRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute(
                name = "deepseek-official",
                keyEnv = "DEEPSEEK_API_KEY",
                defaultModel = model,
            )
            ProviderKind.ANTHROPIC -> DshRoute(
                name = "mh-anthropic",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                name = "mh-openrouter",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.KIMI -> DshRoute(
                name = "mh-kimi",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.OPENCODE_ZEN -> DshRoute(
                name = "opencode-zen",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-responses", profile.resolvedBaseUrl),
            )
            ProviderKind.NVIDIA_NIM -> DshRoute(
                name = "nvidia-nim",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-completions", profile.resolvedBaseUrl),
            )
            ProviderKind.CUSTOM -> DshRoute(
                name = "mh-custom",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.CLAUDE -> throw IllegalArgumentException("Claude subscription login is not supported by DeepSeek Harness")
        }
    }
}

/** One classified line of merged headless output (stdout+stderr share a capture file). */
internal sealed interface DshLine {
    data object ReasoningHeading : DshLine
    data class Reasoning(val text: String) : DshLine
    data class Diagnostic(val text: String) : DshLine
    data class Answer(val text: String) : DshLine
}

internal object DshHeadlessParser {
    fun parseLine(rawLine: String): DshLine {
        val line = rawLine.trim()
        if (line.startsWith("dsh:")) {
            val body = line.removePrefix("dsh:").trim()
            if (body.startsWith("reasoning:")) {
                val rest = body.removePrefix("reasoning:").trim()
                return if (rest.isBlank()) DshLine.ReasoningHeading else DshLine.Reasoning(rest)
            }
            return DshLine.Diagnostic(body.ifBlank { line })
        }
        return DshLine.Answer(line)
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data object NudgeAccepted : DshSdkProtocolEvent
    data class NudgeRejected(val message: String) : DshSdkProtocolEvent
    data class ContinuationRejected(val message: String) : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class Failed(val message: String, val code: String? = null) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

private fun String.isMeaningfulDshText(): Boolean =
    isNotBlank() && !trim().equals("null", ignoreCase = true)

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private var streamedTextSinceMessage = false
    private val toolNames = mutableMapOf<String, String>()

    /** The SDK repeats the entire final assistant message after streaming its deltas.
     * Recognize that frame before JSONObject allocates another full object graph. */
    fun consumeDuplicateAssistantMessage(line: CharSequence): Boolean {
        if (!streamedTextSinceMessage) return false
        if (!line.contains("\"method\":\"session.event\"") ||
            !line.contains("\"type\":\"assistant/message\"")) return false
        streamedTextSinceMessage = false
        return true
    }

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                DshSdkProtocolEvent.Failed(
                    line.removePrefix("dsh:").trim().meaningfulDshText(
                        "DeepSeek Harness reported an unspecified error",
                    ),
                )
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                val message = error.optString("message").meaningfulDshText("DeepSeek Harness SDK request $id failed")
                return when (id) {
                    SDK_NUDGE_PROMPT_ID ->
                        // A rejected nudge must stay non-fatal: turning it into `Failed`
                        // would mark an already-completed turn as SessionFailed.
                        DshSdkProtocolEvent.NudgeRejected(message)
                    SDK_CONTINUATION_PROMPT_ID ->
                        // A rejected continuation is non-fatal; the continuation
                        // watchdog takes over and ends the turn terminally.
                        DshSdkProtocolEvent.ContinuationRejected(message)
                    else -> DshSdkProtocolEvent.Failed(message)
                }
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                SDK_NUDGE_PROMPT_ID -> DshSdkProtocolEvent.NudgeAccepted
                SDK_CONTINUATION_PROMPT_ID -> DshSdkProtocolEvent.PromptAccepted
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val params = frame.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (frame.optString("method")) {
            "session.status" -> {
                if (params.optString("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.optString("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.optString("type")) {
            "assistant/chunk" -> parseAssistantChunk(data)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content")
                val text = contentText(content)
                if (text.isBlank()) {
                    DshSdkProtocolEvent.Ignored
                } else if (streamedTextSinceMessage) {
                    // `assistant/message` repeats the completed content after the SDK has
                    // already delivered its text deltas. The UI has appended those deltas.
                    streamedTextSinceMessage = false
                    DshSdkProtocolEvent.Ignored
                } else {
                    DshSdkProtocolEvent.AssistantText(text)
                }
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val rawName = data.optString("name").ifBlank { "Tool" }
                val arguments = data.optString("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                val callId = resultBlock?.optString("toolCallId").orEmpty()
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.optString("message").orEmpty()
                    .meaningfulDshText(text)
                    .let { compactDshText(it, MAX_DSH_TOOL_RAW_TEXT, 180) }
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> {
                        val error = reason.optJSONObject("error")
                        DshSdkProtocolEvent.Failed(
                            error?.optString("message").orEmpty()
                                .meaningfulDshText("DeepSeek Harness turn failed"),
                            error?.optString("code")?.takeIf { it.isNotBlank() },
                        )
                    }
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun String.meaningfulDshText(fallback: String): String =
        if (isMeaningfulDshText()) trim() else fallback

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return when (chunk.optString("type")) {
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }
                    .appendBounded(delta, MAX_DSH_TEXT_BLOCK_BUFFER)
                streamedTextSinceMessage = true
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.appendBounded(chunk.optString("text"), MAX_DSH_REASONING_BUFFER)
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    // Text deltas are already delivered directly. The completed block normally
                    // repeats them, so avoid retaining or comparing the full response.
                    val missingSuffix = if (streamed.isEmpty()) complete.takeLast(MAX_DSH_TEXT_BLOCK_BUFFER) else ""
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage = true
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.optString("text")
                    .ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
                    .takeLast(MAX_DSH_REASONING_BUFFER)
                val starts = blockId !in reasoningByBlock
                reasoningByBlock.remove(blockId)
                if (text.isBlank()) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Reasoning(blockId, text, starts, isFinal = true)
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun displayToolName(rawName: String, arguments: String): String {
        val operation = runCatching { JSONObject(arguments).optString("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (operation.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun toolDetail(arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val detail = parsed?.let { json ->
            listOf("path", "file_path", "command", "pattern", "query")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return compactDshText(detail.ifBlank { arguments }, MAX_DSH_TOOL_RAW_TEXT, 240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val result = StringBuilder()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            val text = when (block.optString("type")) {
                "text" -> block.optString("text")
                "tool-result" -> contentText(block.optJSONArray("content"))
                else -> ""
            }
            if (text.isBlank()) continue
            if (result.isNotEmpty()) result.append('\n')
            result.appendBounded(text, MAX_DSH_CONTENT_TEXT)
        }
        return result.toString()
    }
}

private const val MAX_DSH_REASONING_BUFFER = 8_000
private const val MAX_DSH_REASONING_SUMMARY = 2_000
private const val MAX_DSH_TOOL_RAW_TEXT = 4_000
private const val MAX_DSH_TEXT_BLOCK_BUFFER = 8_000
private const val MAX_DSH_CONTENT_TEXT = 16_000
private const val MAX_DSH_PROTOCOL_LINE_CHARS = 256 * 1024

private fun StringBuilder.appendBounded(value: String, maxChars: Int) {
    if (value.length >= maxChars) {
        clear()
        append(value.takeLast(maxChars))
        return
    }
    append(value)
    val overflow = length - maxChars
    if (overflow > 0) delete(0, overflow)
}

/** Collapses whitespace while inspecting only a bounded tail of untrusted agent output. */
internal fun compactDshText(value: String, rawLimit: Int, outputLimit: Int): String {
    if (value.isEmpty() || rawLimit <= 0 || outputLimit <= 0) return ""
    val start = (value.length - rawLimit).coerceAtLeast(0)
    val result = StringBuilder(value.length - start)
    var pendingSpace = false
    for (index in start until value.length) {
        val char = value[index]
        if (char.isWhitespace()) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            pendingSpace = false
            result.append(char)
        }
    }
    return result.toString().takeLast(outputLimit)
}

/** Action the pure transport-retry policy can decide for a failed turn. */
internal enum class DshTransportRetryAction {
    RETRY,
    EXHAUSTED,
    NOT_TRANSPORT,
    USER_STOP,
}

internal data class DshTransportRetryDecision(
    val action: DshTransportRetryAction,
    val attempt: Int = 0,
    val delayMs: Long = 0L,
)

/**
 * Pure transport-retry policy: up to two in-place continuations with 5 s then
 * 15 s backoff, then exhaustion. Non-transport failures and user stops never
 * retry. Free of Android/coroutine types so the whole sequence is unit-testable.
 */
internal class DshTransportRetryState(private val maxAttempts: Int = 2) {
    private var transportFailures = 0

    fun onFailure(code: String?, message: String, userStopRequested: Boolean): DshTransportRetryDecision {
        if (userStopRequested) return DshTransportRetryDecision(DshTransportRetryAction.USER_STOP)
        if (!isTransportFailure(code, message)) return DshTransportRetryDecision(DshTransportRetryAction.NOT_TRANSPORT)
        transportFailures += 1
        if (transportFailures > maxAttempts) return DshTransportRetryDecision(DshTransportRetryAction.EXHAUSTED)
        val delayMs = when (transportFailures) {
            1 -> 5_000L
            2 -> 15_000L
            else -> return DshTransportRetryDecision(DshTransportRetryAction.EXHAUSTED)
        }
        return DshTransportRetryDecision(DshTransportRetryAction.RETRY, transportFailures, delayMs)
    }
}

/** True when a failure is a transport drop-out that can be retried in place. */
internal fun isTransportFailure(code: String?, message: String): Boolean =
    code == "TRANSPORT" || message.contains("Connection error", ignoreCase = true)

private val READ_ONLY_TOOLS = setOf("read", "glob", "grep", "web_search", "web_fetch", "ls", "file_info")

/** True for tools that can mutate the workspace; unknown names default to mutating. */
internal fun isMutatingTool(name: String): Boolean =
    name.lowercase() !in READ_ONLY_TOOLS
