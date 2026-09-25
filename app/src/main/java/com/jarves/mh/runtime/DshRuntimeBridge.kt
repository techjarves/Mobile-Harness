package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.model.AgentAutonomyMode
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    /** Current autonomy mode; read live so a settings change applies to the next session. */
    private val autonomyProvider: () -> AgentAutonomyMode = { AgentAutonomyMode.APPROVE_RISKY },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    /** Sovereign proxy of the running session; closed on every exit path (ISSUE-010). */
    @Volatile private var sovereignProxyForSession: SovereignProxy? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting DeepSeek Harness…")
        val secret = secretFor(provider).orEmpty()
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
            // Task-scoped resources (ISSUE-013).
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
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val route = DshRouteMapper.forProfile(provider)
            // Sovereign proxy (ISSUE-010): custom dsh routes point at the local
            // proxy, which holds the real key on the host side. The native
            // "deepseek-official" route has no configurable base URL inside dsh,
            // so its key still travels via env — the one documented exception
            // (see SECURITY.md, roadmap 3a residual).
            val sovereignProxy = if (route.custom != null) {
                // dsh talks to custom routes directly (no app-side format
                // gateway), so the proxy is built explicitly for the route's
                // upstream — including the OpenAI-wire providers.
                SovereignProxy(
                    upstreamBaseUrl = route.custom.baseUrl,
                    credentialStyle = ProxyCredentialStyle.KEY_AND_BEARER,
                    apiKey = secret,
                    projectKey = projectSlug,
                ).start()
            } else null
            sovereignProxyForSession = sovereignProxy
            writeDshSettings(
                installed.rootfs,
                route,
                provider,
                proxyBaseUrl = sovereignProxy?.url,
            )
            val environment = linkedMapOf(
                "DSH_HOME" to DSH_HOME_GUEST_PATH,
                // Autonomy mode (ISSUE-001): only the explicit FULLY_AUTONOMOUS
                // opt-in keeps danger-full-access. In the careful modes dsh runs
                // in its default mode and denies tool calls that have no
                // approval channel instead of approving them.
                "DSH_PERMISSION_MODE" to AgentPermissions.dshPermissionMode(autonomyProvider()),
                route.keyEnv to (sovereignProxy?.let { PROXY_MANAGED_SECRET } ?: secret),
            )
            if (route.keyEnv != FALLBACK_KEY_ENV) environment.remove(FALLBACK_KEY_ENV)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = listOf(AgentProfiles.DEEPSEEK_HARNESS.executable, "--profile", "sdk")
            // Route/model diagnostics must not reach logcat in release builds (ISSUE-004).
            AppLog.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
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
            AppLog.d("DshBridge", "SDK process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                AppLog.d("DshBridge", "Changed files: $changed")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            if (exit == 0 && sdkResult.completed && !userStopRequested) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "DeepSeek Harness finished the task in $projectSlug.",
                )
            } else {
                if (userStopRequested) throw DshSessionException("Stopped by user")
                error(sdkResult.failure.ifBlank { "DeepSeek Harness stopped with exit code $exit" })
            }
        }.onFailure { error ->
            AppLog.e("DshBridge", "Session failed", error)
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
        activeProcess = null
        activeSessionId = null
        sovereignProxyForSession?.close()
        sovereignProxyForSession = null
        TaskWakeLocks.release("session:$sessionId")
        ThermalMonitor.stop()
        RuntimeTaskController.unregister(sessionId)
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
        val parser = DshSdkProtocolParser(sessionId)
        var promptSent = false
        var sawRunning = false
        var completed = false
        var sawActivity = false
        val shutdownSentAt = AtomicLong(0L)
        var inputClosed = false
        var failure = ""

        fun send(method: String, id: Int, params: JSONObject? = null) {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            writer.write(frame.toString())
            writer.newLine()
            writer.flush()
        }

        fun closeInput() {
            if (inputClosed) return
            inputClosed = true
            runCatching { writer.close() }
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
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        pushForegroundProgress("DeepSeek Harness is working…")
                    } else if (sawRunning && shutdownSentAt.get() == 0L) {
                        completed = sawActivity && failure.isBlank()
                        if (!completed && failure.isBlank()) {
                            failure = "DeepSeek Harness stopped before processing the prompt"
                        }
                        shutdownSentAt.set(android.os.SystemClock.elapsedRealtime())
                        send("shutdown", SDK_SHUTDOWN_ID)
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    sawActivity = true
                    emitReasoningSummary(
                        sessionId = sessionId,
                        text = protocolEvent.text,
                        blockId = protocolEvent.blockId,
                        startsNewBlock = protocolEvent.startsNewBlock,
                        isFinal = protocolEvent.isFinal,
                        force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                    )
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary),
                    )
                }
                is DshSdkProtocolEvent.AssistantText -> if (protocolEvent.text.isNotEmpty()) {
                    sawActivity = true
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                }
                is DshSdkProtocolEvent.Failed -> failure = protocolEvent.message
                DshSdkProtocolEvent.TurnCompleted -> sawActivity = true
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeInput()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        coroutineScope {
            // Shutdown watchdog: if dsh never acknowledges the shutdown request,
            // close stdin and destroy the process instead of waiting forever.
            val watchdog = launch {
                while (kotlin.coroutines.coroutineContext.isActive) {
                    val sentAt = shutdownSentAt.get()
                    if (sentAt > 0L && android.os.SystemClock.elapsedRealtime() - sentAt >= SDK_SHUTDOWN_TIMEOUT_MS) {
                        closeInput()
                        process.destroy()
                        break
                    }
                    delay(250)
                }
            }
            // The single shared tail loop (ISSUE-013) replaces this bridge's
            // private copy of the follow-the-output-file polling loop.
            OutputFileTailer.tailLines(
                file = nativeProcess.outputFile,
                isAlive = process::isAlive,
                onLine = { line -> handle(parser.parseLine(line)) },
                onTrailing = { trailing ->
                    trailing.trim().takeIf(String::isNotBlank)?.let { handle(parser.parseLine(it)) }
                },
            )
            watchdog.cancel()
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
            // A file skipped by the size caps has no baseline; touching it
            // would be destructive (ISSUE-040).
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

    private fun writeDshSettings(rootfs: File, route: DshRoute, provider: ProviderProfile, proxyBaseUrl: String? = null) {
        val home = File(rootfs, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        // ISSUE-010: custom routes point at the sovereign proxy when present;
        // the proxy holds the real key on the host side and forwards to the
        // route's true upstream.
        val routeBaseUrl = proxyBaseUrl ?: route.custom?.baseUrl
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
                appendLine("      baseURL: ${yamlQuote(routeBaseUrl.orEmpty())}")
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
        val summary = text.replace(Regex("\\s+"), " ").trim().take(2_000)
        if (summary.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastThinkingUpdateAt >= 400) {
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
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "DeepSeek Harness finished the task.",
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

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException -> message
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
            message.isBlank() -> "DeepSeek Harness could not start."
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
            sb.appendLine("Mobile Harness globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this Mobile Harness runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in Mobile Harness Settings before building.")
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
            AppLog.w("DshBridge", "Could not post task result notification", error)
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
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_TIMEOUT_MS = 3_000L
    }
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

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
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private val streamedTextSinceMessage = StringBuilder()
    private val toolNames = mutableMapOf<String, String>()

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                DshSdkProtocolEvent.Failed(line.removePrefix("dsh:").trim())
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                return DshSdkProtocolEvent.Failed(
                    error.optString("message").ifBlank { "DeepSeek Harness SDK request $id failed" },
                )
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
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
                } else if (streamedTextSinceMessage.isNotEmpty()) {
                    // `assistant/message` repeats the completed content after the SDK has
                    // already delivered its text deltas. The UI has appended those deltas.
                    streamedTextSinceMessage.clear()
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
                    .ifBlank { text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(180)
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.optString("message").orEmpty()
                            .ifBlank { "DeepSeek Harness turn failed" },
                    )
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return when (chunk.optString("type")) {
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }.append(delta)
                streamedTextSinceMessage.append(delta)
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.append(chunk.optString("text"))
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    val missingSuffix = complete.takeIf { it.startsWith(streamed) }?.removePrefix(streamed).orEmpty()
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage.append(missingSuffix)
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.optString("text").ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
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
        return detail.ifBlank { arguments }.replace(Regex("\\s+"), " ").trim().take(240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        return buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    "tool-result" -> contentText(block.optJSONArray("content")).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.joinToString("\n")
    }
}
