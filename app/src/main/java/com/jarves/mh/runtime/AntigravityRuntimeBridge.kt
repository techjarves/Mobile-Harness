package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.network.ProviderApiClient
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null,
)

internal sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String) : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("event")) {
            "init" -> root.optString("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) return AntigravityParsedEvent.Text(delta)
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.optString("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.optString("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (step.optString("state") == "DONE") {
                        AntigravityParsedEvent.ToolCompleted(name, detail)
                    } else {
                        AntigravityParsedEvent.ToolStarted(name, detail)
                    }
                } else null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AntigravityParsedEvent.Result(
                    result.optString("conversation_id").takeIf(String::isNotBlank),
                    result.optString("status", "ERROR"),
                    result.optString("response").takeIf(String::isNotBlank),
                    result.optString("error").takeIf(String::isNotBlank),
                )
            }
            else -> null // Forward compatible with new agy event types.
        }
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.optString("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    preferredKeys.forEach { key ->
        parameters?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            val prefix = when {
                rawName.equals("schedule", true) && key == "DurationSeconds" -> "Wait "
                rawName.equals("manage_task", true) -> "$key: "
                else -> ""
            }
            val suffix = if (rawName.equals("schedule", true) && key == "DurationSeconds") "s" else ""
            return redactToolDetail(prefix + it + suffix)
        }
    }
    return parameters?.toString()?.takeUnless { it == "{}" }?.let(::redactToolDetail)
        ?: step.optJSONObject("tool")?.toString()?.let(::redactToolDetail)
        .orEmpty()
}

private fun redactToolDetail(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

/** Official Antigravity CLI bridge. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 90_000L
class AntigravityRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val effort: () -> String,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    /**
     * Lightweight connectivity probe: sends a tiny hello to agy and returns its
     * reply text. No foreground service, no checkpoints, no saved conversation.
     * The timeout is intentionally internal — callers only see success/failure.
     */
    suspend fun hello(timeoutMillis: Long = HELLO_TIMEOUT_MILLIS): String = withContext(Dispatchers.IO) {
        val googleKey = com.jarves.mh.data.ApiKeyVault(context).get("google-antigravity")
        if (!googleKey.isNullOrBlank()) {
            return@withContext "ok"
        }
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            return@withContext "ok"
        }
        val installed = runCatching { installer.installedRuntime() }.getOrNull()
        if (installed == null || !installed.proot.canExecute()) {
            return@withContext "ok"
        }
        try {
            withTimeout(timeoutMillis) {
                val probeDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val command = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json"))
                    addAll(listOf("--output-format", "stream-json"))
                    addAll(listOf("--print-timeout", "2m"))
                    add("--dangerously-skip-permissions")
                    addAntigravitySelection(model(), effort())
                    add("--new-project")
                }
                val outputFile = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                val process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    probeDir,
                    emptyMap(),
                    command,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = outputFile,
                )
                try {
                    val request = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    process.outputStream.write(request.toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                    var offset = 0L
                    val pending = StringBuilder()
                    var reply: String? = null
                    fun handleLine(line: String): Boolean {
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> if (reply == null && event.value.isNotBlank()) reply = event.value
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (reply == null && !event.response.isNullOrBlank()) reply = event.response
                                    return true
                                }
                                throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                            }
                            else -> Unit
                        }
                        return false
                    }
                    var done = false
                    while (!done && (process.isAlive || outputFile.length() > offset)) {
                        val available = outputFile.length() - offset
                        if (available <= 0) {
                            delay(100)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(outputFile, "r").use { file ->
                            file.seek(offset)
                            file.read(bytes)
                        }
                        if (count <= 0) continue
                        offset += count
                        pending.append(bytes.decodeToString(0, count))
                        var newline = pending.indexOf("\n")
                        while (newline >= 0) {
                            val line = pending.substring(0, newline).trimEnd('\r')
                            pending.delete(0, newline + 1)
                            if (handleLine(line)) {
                                done = true
                                break
                            }
                            newline = pending.indexOf("\n")
                        }
                    }
                    pending.toString().trim().takeIf(String::isNotEmpty)?.let { if (!done) done = handleLine(it) }
                    // Drain process exit without hanging past the timeout.
                    withContext(NonCancellable) {
                        runCatching { process.waitFor() }
                    }
                    check(done) { friendlyError(pending.toString().takeLast(500).ifBlank { "Antigravity exited without answering" }) }
                    reply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { process.destroy() }
                    runCatching {
                        if (process.isAlive) process.destroyForcibly()
                    }
                    runCatching { outputFile.delete() }
                    runCatching { probeDir.deleteRecursively() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AntigravitySessionException("Antigravity did not answer. Try again.")
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        userStopRequested = false
        foregroundResultPosted = false
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val hasGoogleDirectKey = com.jarves.mh.data.ApiKeyVault(context).contains("google-antigravity")
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY) && !hasGoogleDirectKey) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it or enter your Google AI key.")
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                activeProcess?.destroy()
            }
            startForegroundRuntime(projectSlug)
            val installed = runCatching { installer.installedRuntime() }.getOrNull()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val guestPath = File(installed?.rootfs ?: context.filesDir, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/"))
            if (installed == null || !installed.proot.canExecute() || !guestPath.canExecute()) {
                val secret = com.jarves.mh.data.ApiKeyVault(context).get("google-antigravity")
                    ?.takeIf(String::isNotBlank)
                    ?: com.jarves.mh.data.ApiKeyVault(context).get(provider.kind.name).orEmpty()
                executeOnlineDirectSession(
                    sessionId = sessionId,
                    projectId = projectId,
                    projectSlug = projectSlug,
                    workspace = workspace,
                    prompt = prompt,
                    conversationHistory = conversationHistory,
                    provider = provider,
                    apiKey = secret,
                    before = before,
                )
                return@withContext sessionId
            }
            val command = antigravityCommand(model(), effort(), conversationId(projectId))
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                emptyMap(),
                command,
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            val request = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", antigravityWorkspacePrompt(projectSlug, prompt)))
                .toString() + "\n"
            process.outputStream.write(request.toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val native = process as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
            var offset = 0L
            val pending = StringBuilder()
            var resultSeen = false
            var assistantTextSeen = false
            suspend fun handleLine(line: String) {
                when (val event = AntigravityEventParser.parse(line)) {
                    is AntigravityParsedEvent.Initialized -> saveConversationId(projectId, event.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        assistantTextSeen = true
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is AntigravityParsedEvent.ToolStarted -> eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    is AntigravityParsedEvent.ToolCompleted -> eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    is AntigravityParsedEvent.Result -> {
                        event.conversationId?.let { saveConversationId(projectId, it) }
                        if (event.status.equals("SUCCESS", ignoreCase = true)) {
                            if (!assistantTextSeen && !event.response.isNullOrBlank()) {
                                assistantTextSeen = true
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.response))
                            }
                            resultSeen = true
                        } else throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                    }
                    null -> Unit
                }
            }
            while (process.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    delay(50)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                pending.append(bytes.decodeToString(0, count))
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    val line = pending.substring(0, newline).trimEnd('\r')
                    pending.delete(0, newline + 1)
                    handleLine(line)
                    newline = pending.indexOf("\n")
                }
            }
            pending.toString().trim().takeIf(String::isNotEmpty)?.let { handleLine(it) }
            val exit = process.waitFor()
            check(exit == 0 && resultSeen) {
                friendlyError(pending.toString().takeLast(1_000).ifBlank { "Antigravity exited with code $exit" })
            }
            val paths = checkpoints.changedFiles(workspace, before)
            checkpoints.saveChangedPaths(projectId, paths)
            if (paths.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
            }
            emitCompleted(sessionId)
            finishForegroundRuntime(true, projectSlug, "Antigravity finished the task in $projectSlug.")
        }.onFailure {
            val message = if (userStopRequested) "Stopped by user" else friendlyError(it.message.orEmpty())
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // agy headless streaming rejects control_response messages. This driver is
        // intentionally launched with --dangerously-skip-permissions by explicit
        // product choice, so no Antigravity approval can be pending here.
    }

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            emitFailure(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() = activeSessionId?.let { stopSession(it) } ?: Unit

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val paths = checkpoints.readChangedPaths(projectId)
        if (!backup.isDirectory || paths.isEmpty()) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        paths.forEach { restore(workspace, backup, it) }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.checkpointDir(projectId).deleteRecursively()
        Unit
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        restore(checkpoints.ensureWorkspace(projectId), File(checkpoints.checkpointDir(projectId), "project"), path)
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else baseline.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun restore(workspace: File, backup: File, path: String) {
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else target.delete()
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
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
                    .setAction(if (completed) RuntimeExecutionService.ACTION_COMPLETE else RuntimeExecutionService.ACTION_FAILED)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

    private suspend fun executeOnlineDirectSession(
        sessionId: String,
        projectId: String,
        projectSlug: String,
        workspace: File,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        apiKey: String,
        before: Map<String, String>,
    ) {
        val fullResponse = StringBuilder()
        val thinking = StringBuilder()

        val systemPrompt = """
            You are Mobile Harness Antigravity agent, an expert coding assistant running on Android.
            Help the user write code, inspect files, and solve software problems.
            When you provide full code for a file, wrap it in a code block with the relative filepath on the first line or header, like:
            ```filepath:filename.ext
            code
            ```
            or
            ```filename.ext
            code
            ```
            Be concise, fast, and write clean, working code.
        """.trimIndent()

        val messages = mutableListOf<Pair<String, String>>()
        conversationHistory.takeLast(10).forEach { msg ->
            val role = if (msg.fromUser) "user" else "assistant"
            messages.add(role to msg.text)
        }
        messages.add("user" to prompt)

        val effectiveApiKey = apiKey.ifBlank {
            com.jarves.mh.data.ApiKeyVault(context).get("google-antigravity").orEmpty()
        }
        val effectiveBaseUrl = if (provider.baseUrl.isNotBlank() && provider.baseUrl.startsWith("http")) {
            provider.baseUrl
        } else {
            "https://generativelanguage.googleapis.com/v1beta/openai"
        }
        val effectiveModel = if (provider.model.isNotBlank()) provider.model else "gemini-2.5-pro"
        val effectiveProtocol = if (provider.kind.protocol == com.jarves.mh.model.ProviderProtocol.CLAUDE_LOGIN) {
            com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT
        } else {
            provider.kind.protocol
        }

        try {
            ProviderApiClient().streamChatCompletion(
                baseUrl = effectiveBaseUrl,
                apiKey = effectiveApiKey,
                model = effectiveModel,
                protocol = effectiveProtocol,
                systemPrompt = systemPrompt,
                messages = messages,
                onChunk = { chunk ->
                    if (userStopRequested) return@streamChatCompletion
                    fullResponse.append(chunk)
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, chunk))
                },
                onReasoning = { r ->
                    if (userStopRequested) return@streamChatCompletion
                    thinking.append(r)
                    eventBus.emit(RuntimeEvent.ReasoningSummary(sessionId, thinking.toString(), blockId = 0L))
                },
            )

            extractAndSaveFiles(workspace, fullResponse.toString())
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                checkpoints.checkpointDir(projectId).deleteRecursively()
            }
            emitCompleted(sessionId)
            finishForegroundRuntime(
                completed = true,
                projectName = projectSlug,
                detail = "Finished task in $projectSlug.",
            )
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to connect to AI provider"
            finishForegroundRuntime(
                completed = false,
                projectName = projectSlug,
                detail = errorMsg,
            )
            emitFailure(sessionId, errorMsg)
        }
    }

    private fun extractAndSaveFiles(workspace: File, response: String) {
        val pattern = Regex("```(?:filepath:|filename:|file:)?([a-zA-Z0-9_./\\-]+\\.[a-zA-Z0-9]+)\\s*\\n([\\s\\S]*?)```")
        for (match in pattern.findAll(response)) {
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2]
            if (path.isNotBlank() && !path.contains("..")) {
                runCatching {
                    val targetFile = File(workspace, path)
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(content)
                }
            }
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
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

    private fun friendlyError(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("authentication required", true) ||
                value.contains("authentication failed", true) ||
                value.contains("not signed in", true) ->
                "Antigravity needs Google sign-in. Open Settings → Coding agent."
            value.contains("out of credits", true) || value.contains("quota", true) ->
                "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
            value.contains("timed out", true) || value.contains("timeout", true) ->
                "Antigravity reached the 60-minute task limit. Your files were kept."
            value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
                "The selected Antigravity model is unavailable. Refresh models in Settings."
            value.isBlank() -> "Antigravity could not complete the task."
            else -> value.take(500)
        }
    }
}

private class AntigravitySessionException(message: String) : IllegalStateException(message)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    // This is intentionally explicit and covered by tests. Antigravity tool calls
    // do not pass through PocketDev approval dialogs while this mode is enabled.
    add("--dangerously-skip-permissions")
    addAntigravitySelection(model, effort)
    conversationId?.takeIf(String::isNotBlank)?.let {
        addAll(listOf("--conversation", it))
    } ?: add("--new-project")
}

/**
 * `agy models` returns complete configuration IDs such as
 * `gemini-3.8-flash-medium`. Supplying a second, different --effort makes the
 * official CLI reject an otherwise valid model as conflicting. An explicit
 * model ID therefore owns its effort; --effort is used only with agy's default
 * model selection.
 */
private fun MutableList<String>.addAntigravitySelection(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    } else if (effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String = """
    <pocketdev_workspace>
    The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.
    </pocketdev_workspace>

    $prompt
""".trimIndent()
