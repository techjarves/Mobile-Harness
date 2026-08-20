package dev.pocket.app.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import dev.pocket.app.model.ProviderKind
import dev.pocket.app.model.ProviderProfile
import dev.pocket.app.model.RiskLevel
import dev.pocket.app.model.RuntimeEvent
import dev.pocket.app.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null

    override suspend fun startSession(projectId: String, prompt: String, provider: ProviderProfile): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val secret = secretFor(provider).orEmpty()
        if (provider.kind != ProviderKind.CLAUDE && secret.isBlank()) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."))
            return@withContext sessionId
        }

        runCatching {
            startForegroundRuntime()
            // Setup and release checks happen once in the app-start loading flow.
            // Sending a prompt must never perform network update checks or put setup
            // messages into the conversation.
            val installed = installer.installedRuntime()
            val workspace = ensureWorkspace(projectId)
            val before = snapshot(workspace)
            val launch = RuntimeLaunchConfigBuilder.build(provider, authToken = secret)
            val command = buildList {
                add(launch.executable)
                add("--bare")
                add("-p")
                add(prompt)
                add("--output-format")
                add("stream-json")
                add("--verbose")
                add("--model")
                add(provider.model)
                add("--max-turns")
                add("3")
                add("--permission-mode")
                add("default")
                add("--settings")
                add("/root/.claude/pocket-settings.json")
            }
            val process = installer.process(installed.proot, installed.rootfs, workspace, launch.environment, command)
            activeProcess = process
            coroutineScope {
                val permissionWatcher = launch { watchPermissionRequests(sessionId) }
                var lastDiagnostic = ""
                val pendingOutput = StringBuilder()
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                var outputOffset = 0L
                while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
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
                    if (count > 0) {
                        outputOffset += count
                        pendingOutput.append(bytes.decodeToString(0, count))
                        var newline = pendingOutput.indexOf("\n")
                        while (newline >= 0) {
                            val line = pendingOutput.substring(0, newline).trimEnd('\r')
                            pendingOutput.delete(0, newline + 1)
                            if (line.isNotBlank() && !consumeClaudeEvent(sessionId, line)) {
                                lastDiagnostic = line.takeLast(500)
                            }
                            newline = pendingOutput.indexOf("\n")
                        }
                    }
                }
                pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let { line ->
                    if (!consumeClaudeEvent(sessionId, line)) lastDiagnostic = line.takeLast(500)
                }
                val exit = process.waitFor()
                permissionWatcher.cancelAndJoin()
                pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                    permission.response.writeText("deny")
                    pending.remove(permission.request.approvalId)
                }
                val changed = changedFiles(workspace, before)
                if (changed.isNotEmpty()) eventBus.emit(RuntimeEvent.FilesChanged(sessionId, changed))
                if (exit == 0) {
                    eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
                } else {
                    error(lastDiagnostic.ifBlank { "Claude Code stopped with exit code $exit" })
                }
            }
        }.onFailure { error ->
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, friendlyError(error)))
        }
        activeProcess = null
        activeSessionId = null
        stopForegroundRuntime()
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
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "Stopped by user"))
        }
    }

    private suspend fun watchPermissionRequests(sessionId: String) {
        val bridge = File(context.filesDir, "runtime-bridge")
        while (kotlin.coroutines.coroutineContext.isActive) {
            bridge.listFiles { file -> file.name.endsWith(".request") }.orEmpty().forEach { file ->
                val approvalId = file.name.removeSuffix(".request")
                if (!pending.containsKey(approvalId)) {
                    runCatching {
                        val json = JSONObject(file.readText())
                        val toolName = json.optString("tool_name", "Tool")
                        val input = json.optJSONObject("tool_input") ?: JSONObject()
                        val command = input.optString("command").ifBlank { null }
                        val paths = listOf("file_path", "path", "notebook_path")
                            .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
                        val explanation = input.optString("description")
                            .ifBlank { command.orEmpty() }
                            .ifBlank { "$toolName wants to change or run something in this project" }
                        val request = ToolRequest(
                            approvalId = approvalId,
                            sessionId = sessionId,
                            toolName = toolName,
                            explanation = explanation,
                            affectedPaths = paths,
                            commandPreview = command,
                            risk = classifyRisk(toolName, command),
                        )
                        val response = File(file.parentFile, "$approvalId.response")
                        pending[approvalId] = PendingPermission(request, response)
                        eventBus.emit(RuntimeEvent.ToolRequested(sessionId, request))
                    }.onFailure {
                        File(file.parentFile, "$approvalId.response").writeText("deny")
                    }
                }
            }
            delay(100)
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        when (json.optString("type")) {
            "assistant" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return true
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "text") {
                        block.optString("text").takeIf(String::isNotBlank)?.let {
                            eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                        }
                    }
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return true
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, "Tool", "Claude Code completed an approved action"))
                    }
                }
            }
            "result" -> if (json.optBoolean("is_error")) {
                val message = json.optString("result").ifBlank { "Claude Code reported an error" }
                throw IllegalStateException(message)
            }
        }
        return true
    }

    private fun ensureWorkspace(projectId: String): File {
        val workspace = File(context.filesDir, "workspaces/$projectId").apply { mkdirs() }
        val readme = File(workspace, "README.md")
        if (!readme.exists()) readme.writeText("# Pocket Dev project\n\nThis project is managed locally on Android.\n")
        val index = File(workspace, "index.html")
        if (!index.exists()) index.writeText("<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n")
        return workspace
    }

    private fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(root).path to digest(it) }

    private fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun classifyRisk(tool: String, command: String?): RiskLevel {
        val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
        return when {
            listOf("rm -rf", "git push", "git reset", "sudo", "curl ").any(preview::contains) -> RiskLevel.HIGH
            tool in listOf("Write", "Edit", "NotebookEdit", "Bash") -> RiskLevel.REVIEW
            else -> RiskLevel.SAFE
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    private fun startForegroundRuntime() {
        ContextCompat.startForegroundService(context, android.content.Intent(context, RuntimeExecutionService::class.java))
    }

    private fun stopForegroundRuntime() {
        context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
    }

    private data class PendingPermission(val request: ToolRequest, val response: File)
}
