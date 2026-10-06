package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
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
                    if (step.optString("state") in setOf("DONE", "ERROR")) {
                        val output = step.optJSONObject("tool_info")
                            ?.optString("output")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::antigravityToolOutput)
                        val error = step.optJSONObject("tool_info")
                            ?.optJSONObject("error")
                            ?.optString("message")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::redactToolDetail)
                        AntigravityParsedEvent.ToolCompleted(name, output ?: error ?: detail)
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

private fun antigravityToolOutput(value: String): String {
    val redacted = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
        .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    return if (redacted.length <= 6_000) redacted else "…\n" + redacted.takeLast(6_000)
}

/** Official Antigravity CLI bridge. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 90_000L

/**
 * Stream-json's result event is terminal, but agy may keep its input loop alive
 * after emitting it. Stop the entire spawned process group so task completion is
 * driven by the protocol rather than an implementation-specific process exit.
 */
private suspend fun stopAfterTerminalResult(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
    if (process.isAlive) process.destroyForcibly()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
}

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
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            throw IllegalStateException("Antigravity CLI is not installed.")
        }
        try {
            withTimeout(timeoutMillis) {
                val installed = installer.installedRuntime()
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
                    check(done) { friendlyError(pending.toString().takeLast(500).ifBlank { "Antigravity exited without answering" }) }
                    stopAfterTerminalResult(process)
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
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                activeProcess?.destroy()
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
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
            var activeToolName: String? = null
            var activeToolDetail = ""
            var activeToolStartedAtMillis = 0L
            var lastToolProgressAtMillis = 0L
            suspend fun handleLine(line: String) {
                when (val event = AntigravityEventParser.parse(line)) {
                    is AntigravityParsedEvent.Initialized -> saveConversationId(projectId, event.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        assistantTextSeen = true
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        activeToolName = event.name
                        activeToolDetail = event.detail
                        activeToolStartedAtMillis = System.currentTimeMillis()
                        lastToolProgressAtMillis = activeToolStartedAtMillis
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.ToolCompleted -> {
                        if (activeToolName == event.name) activeToolName = null
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    }
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
            outputLoop@ while (process.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    val now = System.currentTimeMillis()
                    if (activeToolName == "Bash" && activeToolDetail.contains("gradle", ignoreCase = true) &&
                        now - activeToolStartedAtMillis >= 10_000L && now - lastToolProgressAtMillis >= 10_000L
                    ) {
                        lastToolProgressAtMillis = now
                        eventBus.emit(
                            RuntimeEvent.ToolProgress(
                                sessionId,
                                "Bash",
                                antigravityGradleProgress(activeToolDetail, now - activeToolStartedAtMillis),
                            ),
                        )
                    }
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
                    if (resultSeen) break@outputLoop
                    newline = pending.indexOf("\n")
                }
            }
            if (resultSeen) {
                stopAfterTerminalResult(process)
            } else {
                pending.toString().trim().takeIf(String::isNotEmpty)?.let { handleLine(it) }
                val exit = process.waitFor()
                check(exit == 0 && resultSeen) {
                    friendlyError(pending.toString().takeLast(1_000).ifBlank { "Antigravity exited with code $exit" })
                }
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

    For Android projects, never use plain `gradle build` or start a Gradle daemon. Build only the debug APK. First check whether `app/build/outputs/apk/debug/` already contains an APK.
    - For a first build with no existing debug APK, allow dependency resolution and use:
      `timeout 15m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
    - For a later build with an existing debug APK, prefer the cached fast path:
      `timeout 5m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace`
    PocketDev bundles the standard Android dependencies locally. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.
    Antigravity does not stream output from a foreground shell command until that command exits. Start the optimized Android build with `run_command` normally; do not use `nohup`, shell backgrounding, or a detached process because those processes do not survive PocketDev's proot command boundary. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.
    </pocketdev_workspace>

    $prompt
""".trimIndent()

internal fun antigravityGradleProgress(command: String, elapsedMillis: Long): String {
    val elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
    return "Gradle is running · ${elapsedSeconds}s\n$command\nFull output will appear when the command completes."
}
