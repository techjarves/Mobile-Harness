package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.ui.AppStrings
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
import org.json.JSONArray
import org.json.JSONObject

internal const val CURSOR_VERSION = "2026.10.19-6bbf2c4"
internal const val CURSOR_VAULT_ID = "cursor-cli"
internal const val CURSOR_NOT_INSTALLED =
    "Cursor CLI is not installed. Open Settings → Coding agent to install it."
internal const val CURSOR_NEEDS_KEY =
    "Cursor CLI needs an API key. Open Settings → Coding agent."
internal const val CURSOR_QUOTA =
    "The Cursor account has hit its usage limit. Check the plan, or wait and try again."
internal const val CURSOR_NO_SPACE =
    "Not enough free storage for Cursor CLI. Free at least 1.2 GB and install again."
internal const val CURSOR_INCOMPATIBLE =
    "Cursor CLI is incompatible with this runtime build. Reinstall the pinned version from Settings."
internal const val CURSOR_TIMEOUT =
    "Cursor CLI timed out. Files already written were kept."
internal const val CURSOR_NETWORK =
    "Cursor CLI could not reach Cursor. Check the internet connection and try again."
internal const val CURSOR_FAILED = "Cursor CLI could not complete the task."

internal data class CursorTokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    fun hasAny(): Boolean =
        inputTokens > 0 || outputTokens > 0 || cacheReadTokens > 0 || cacheWriteTokens > 0
}

internal sealed class CursorParsedEvent {
    data class Session(val sessionId: String, val model: String? = null) : CursorParsedEvent()
    data class AssistantDelta(val text: String) : CursorParsedEvent()
    data class ToolStarted(val name: String, val detail: String) : CursorParsedEvent()
    data class ToolCompleted(val name: String, val detail: String) : CursorParsedEvent()
    data class Success(
        val sessionId: String?,
        val resultText: String?,
        val usage: CursorTokenUsage? = null,
    ) : CursorParsedEvent()
    data class Failure(val message: String, val usage: CursorTokenUsage? = null) : CursorParsedEvent()
}

/** Newline-delimited Cursor stream-json. Unknown types and fields are ignored. */
internal object CursorEventParser {
    fun parse(line: String): CursorParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("type")) {
            "system" -> {
                if (root.optString("subtype") != "init") return null
                val sessionId = root.optString("session_id").takeIf(String::isNotBlank) ?: return null
                CursorParsedEvent.Session(sessionId, root.optString("model").takeIf(String::isNotBlank))
            }
            "assistant" -> parseAssistant(root)
            "tool_call" -> parseTool(root)
            "result" -> parseResult(root)
            "user" -> null
            else -> null
        }
    }

    private fun parseAssistant(root: JSONObject): CursorParsedEvent? {
        val hasTimestamp = root.has("timestamp_ms") && !root.isNull("timestamp_ms")
        val hasModelCall = root.has("model_call_id") && !root.isNull("model_call_id") &&
            root.optString("model_call_id").isNotBlank()
        if (!hasTimestamp || hasModelCall) return null
        val text = assistantText(root)
        return text.takeIf(String::isNotEmpty)?.let(CursorParsedEvent::AssistantDelta)
    }

    private fun parseTool(root: JSONObject): CursorParsedEvent? {
        val (name, detail) = cursorToolDisplay(root)
        return when (root.optString("subtype")) {
            "started" -> CursorParsedEvent.ToolStarted(name, detail)
            "completed" -> CursorParsedEvent.ToolCompleted(name, cursorCompletedDetail(root, detail))
            else -> null
        }
    }

    private fun parseResult(root: JSONObject): CursorParsedEvent? {
        val sessionId = root.optString("session_id").takeIf(String::isNotBlank)
        val isError = root.optBoolean("is_error", false)
        val subtype = root.optString("subtype")
        val usage = parseCursorUsage(root)
        if (!isError && subtype == "success") {
            return CursorParsedEvent.Success(sessionId, root.optString("result").takeIf(String::isNotBlank), usage)
        }
        if (isError || subtype.isNotBlank()) {
            val message = root.optString("error").ifBlank { root.optString("result") }
            return CursorParsedEvent.Failure(message.ifBlank { CURSOR_FAILED }, usage)
        }
        return null
    }
}

/** Model id to show. A blank or account-default report is omitted unless the user selected one. */
internal fun cursorShownModel(requested: String?, reported: String?): String? {
    val fromCli = reported?.trim()?.takeIf { it.isNotEmpty() && !it.equals("default", true) && !it.equals("auto", true) }
    if (fromCli != null) return fromCli
    return requested?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun parseCursorUsage(root: JSONObject): CursorTokenUsage? {
    val usage = root.optJSONObject("usage") ?: return null
    fun long(vararg names: String): Long {
        names.forEach { name ->
            if (!usage.has(name) || usage.isNull(name)) return@forEach
            val number = usage.optLong(name, Long.MIN_VALUE)
            if (number != Long.MIN_VALUE && number >= 0L) return number
        }
        return 0L
    }
    return CursorTokenUsage(
        inputTokens = long("inputTokens", "input_tokens"),
        outputTokens = long("outputTokens", "output_tokens"),
        cacheReadTokens = long("cacheReadTokens", "cache_read_tokens"),
        cacheWriteTokens = long("cacheWriteTokens", "cache_write_tokens"),
    ).takeIf { it.hasAny() }
}

internal fun formatTokenCount(tokens: Long): String {
    val digits = kotlin.math.abs(tokens).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (tokens < 0) "-$grouped" else grouped
}

/** One short note. Missing model or missing usage is left out instead of showing a placeholder. */
internal fun cursorUsageNote(
    model: String?,
    usage: CursorTokenUsage?,
    modelLabel: (String) -> String = { "Model: $it" },
    inputLabel: (String) -> String = { "Input: $it" },
    outputLabel: (String) -> String = { "Output: $it" },
    cacheLabel: (String) -> String = { "Cache: $it" },
): String? {
    val counted = usage?.takeIf { it.hasAny() }
    if (model.isNullOrBlank() && counted == null) return null
    return buildString {
        if (!model.isNullOrBlank()) appendLine(modelLabel(model))
        if (counted != null) {
            appendLine(inputLabel(formatTokenCount(counted.inputTokens)))
            appendLine(outputLabel(formatTokenCount(counted.outputTokens)))
            val cache = counted.cacheReadTokens + counted.cacheWriteTokens
            if (cache > 0L) appendLine(cacheLabel(formatTokenCount(cache)))
        }
    }.trim()
}

private fun assistantText(root: JSONObject): String {
    val message = root.optJSONObject("message") ?: return ""
    return when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index)
                if (item == null) {
                    append(content.optString(index))
                } else if (item.optString("type").let { it.isBlank() || it == "text" }) {
                    append(item.optString("text"))
                }
            }
        }
        else -> ""
    }
}

private fun cursorToolDisplay(root: JSONObject): Pair<String, String> {
    val call = root.optJSONObject("tool_call") ?: return "Tool" to ""
    val key = call.keys().asSequence().firstOrNull() ?: return "Tool" to ""
    val payload = call.optJSONObject(key)
    if (key == "function") {
        val name = payload?.optString("name").orEmpty().ifBlank { "Tool" }.replace('_', ' ')
        val args = payload?.optString("arguments").orEmpty()
            .ifBlank { payload?.opt("args")?.toString().orEmpty() }
        return name to cursorRedact(args).take(500)
    }
    val bare = key.removeSuffix("ToolCall")
    val args = payload?.optJSONObject("args")
    val display = when {
        key == "readToolCall" -> "Read"
        key == "writeToolCall" -> "Write"
        bare.equals("shell", true) || bare.equals("bash", true) ||
            bare.equals("runCommand", true) || bare.equals("terminal", true) -> "Bash"
        else -> key.replace('_', ' ')
    }
    val detail = when (display) {
        "Read", "Write" -> args?.optString("path").orEmpty()
        "Bash" -> listOf("command", "cmd", "script")
            .firstNotNullOfOrNull { candidate -> args?.optString(candidate)?.takeIf(String::isNotBlank) }
            ?: key
        else -> args?.toString()?.takeUnless { it == "{}" }.orEmpty()
    }
    return display to cursorRedact(detail).take(500)
}

private fun cursorCompletedDetail(root: JSONObject, fallback: String): String {
    val call = root.optJSONObject("tool_call") ?: return cursorRedact(fallback).take(500)
    val key = call.keys().asSequence().firstOrNull() ?: return cursorRedact(fallback).take(500)
    if (key == "readToolCall" || key == "writeToolCall") return cursorRedact(fallback).take(500)
    val payload = call.optJSONObject(key)
    val result = payload?.optJSONObject("result")
    val error = result?.optString("error")?.takeIf(String::isNotBlank)
        ?: payload?.optString("error")?.takeIf(String::isNotBlank)
    val summary = error
        ?: result?.optString("success")?.takeIf(String::isNotBlank)
        ?: result?.optString("output")?.takeIf(String::isNotBlank)
        ?: fallback
    return cursorToolOutput(summary)
}

internal fun scrubCursorSecret(value: String, secret: String?): String {
    val redacted = cursorRedact(value)
    val key = secret?.trim().orEmpty()
    if (key.length < 8) return redacted
    return redacted.replace(key, "••••")
}

internal fun cursorRedact(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)(\\S+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)\\S+"), "$1••••")

private fun cursorToolOutput(value: String): String {
    val redacted = cursorRedact(value).replace("\r\n", "\n").replace('\r', '\n').trim()
    return if (redacted.length <= 6_000) redacted else "…\n" + redacted.takeLast(6_000)
}

internal fun cursorFriendlyError(raw: String): String {
    val value = cursorRedact(raw).replace(Regex("\\s+"), " ").trim()
    if (value in CURSOR_KNOWN_ERRORS) return value
    return when {
        value.contains("not installed", true) -> CURSOR_NOT_INSTALLED
        value.contains("not authenticated", true) || value.contains("invalid api key", true) ||
            Regex("(?i)\\blogin\\b").containsMatchIn(value) ||
            Regex("(?i)(^|\\D)401(\\D|$)").containsMatchIn(value) -> CURSOR_NEEDS_KEY
        Regex("(?i)(^|\\D)429(\\D|$)").containsMatchIn(value) || value.contains("rate limit", true) ||
            value.contains("usage limit", true) || value.contains("quota", true) -> CURSOR_QUOTA
        value.contains("ENOSPC", true) || value.contains("no space", true) -> CURSOR_NO_SPACE
        cursorRuntimeIncompatible(value) -> CURSOR_INCOMPATIBLE
        value.contains("failed to fetch", true) || value.contains("ENOTFOUND") ||
            value.contains("EAI_AGAIN") || value.contains("network is unreachable", true) ||
            value.contains("i/o timeout", true) -> CURSOR_NETWORK
        cursorProcessTimedOut(value) -> CURSOR_TIMEOUT
        value.isBlank() -> CURSOR_FAILED
        else -> value.takeLast(500)
    }
}

private fun cursorProcessTimedOut(value: String): Boolean {
    val withoutBuildHints = value
        .replace(Regex("(?i)\\btimeout\\s+\\d+\\s*m\\b"), "")
        .replace(Regex("(?i)timed-out build"), "")
    return withoutBuildHints.contains("timed out", true) || withoutBuildHints.contains("timeout", true)
}

private val CURSOR_KNOWN_ERRORS = setOf(
    CURSOR_NOT_INSTALLED,
    CURSOR_NEEDS_KEY,
    CURSOR_QUOTA,
    CURSOR_NO_SPACE,
    CURSOR_INCOMPATIBLE,
    CURSOR_TIMEOUT,
    CURSOR_NETWORK,
    CURSOR_FAILED,
)

private fun cursorRuntimeIncompatible(value: String): Boolean {
    if (value.contains("GLIBC_", true) || value.contains("pty.node", true)) return true
    return Regex("(?i)(cursor-agent|\\bnode\\b).{0,80}not found").containsMatchIn(value)
}

internal fun cursorCommand(
    projectSlug: String,
    model: String,
    sessionId: String?,
    prompt: String,
): List<String> = buildList {
    add(RuntimeInstaller.CURSOR_GUEST_PATH)
    add("-p")
    add("--force")
    add("--trust")
    add("--sandbox")
    add("disabled")
    add("--disable-auto-update")
    add("--output-format")
    add("stream-json")
    add("--stream-partial-output")
    add("--workspace")
    add("/workspace/$projectSlug")
    val chosenModel = model.trim()
    if (chosenModel.isNotEmpty()) {
        add("--model")
        add(chosenModel)
    }
    sessionId?.trim()?.takeIf(String::isNotEmpty)?.let {
        add("--resume")
        add(it)
    }
    add(prompt)
}

internal fun cursorWorkspacePrompt(
    projectSlug: String,
    prompt: String,
    history: List<ChatMessage> = emptyList(),
): String {
    val preamble = """
        The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build files only inside this directory.

        For Android projects, never run plain `gradle build` and never start a Gradle daemon. Build only the debug APK.
        - First build, when app/build/outputs/apk/debug/ has no APK:
          timeout 15m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace
        - Later build, when a debug APK already exists:
          timeout 5m gradle -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 --offline --init-script /root/.gradle/init.d/pocketdev-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 assembleDebug --console=plain --stacktrace
        If and only if the offline build fails because a dependency is missing from the local Maven cache, retry once online with timeout 15m and without --offline. Do not retry a timed-out build unchanged.
        Do not use nohup, shell backgrounding, or a detached process. Those processes do not survive this environment.
    """.trimIndent()
    val prior = if (history.lastOrNull()?.fromUser == true) history.dropLast(1) else history
    val recent = prior.recentWithinCharacterBudget(CURSOR_HISTORY_CHARACTERS)
    val historyBlock = if (recent.isEmpty()) {
        ""
    } else {
        recent.joinToString(prefix = "\n\n", separator = "\n") { message ->
            val who = if (message.fromUser) "User" else "Assistant"
            "$who: ${message.text}"
        }
    }
    return preamble + historyBlock + "\n\n" + prompt
}

data class CursorModel(
    val id: String,
    val name: String = id,
    val contextTokens: Long? = null,
    val maxContextTokens: Long? = null,
    val isDefault: Boolean = false,
) {
    /** Context window in front of the display name, for example "1M · Claude Opus 4.5". */
    fun title(): String {
        val window = contextWindowLabel()
        val shown = name.ifBlank { id }
        return if (window.isNullOrBlank()) shown else "$window · $shown"
    }

    fun contextWindowLabel(): String? {
        val standard = contextTokens?.let(::formatContextTokens)
        val extended = maxContextTokens?.takeIf { it != contextTokens }?.let(::formatContextTokens)
        return when {
            standard != null && extended != null -> "$standard–$extended"
            standard != null -> standard
            else -> extended
        }
    }
}

data class CursorModelCatalog(
    val models: List<CursorModel>,
    val defaultId: String?,
) {
    val ids: List<String> get() = models.map { it.id }
}

internal fun parseCursorModelList(output: String): List<String> = parseCursorModelCatalog(output).ids

internal fun parseCursorModelCatalog(output: String): CursorModelCatalog {
    val trimmed = output.replace(CURSOR_ANSI, "").trim()
    if (trimmed.startsWith("{")) {
        val models = runCatching { JSONObject(trimmed).optJSONArray("models") }.getOrNull()
        if (models != null) return catalogFrom(jsonModels(models))
    }
    if (trimmed.startsWith("[")) {
        val array = runCatching { JSONArray(trimmed) }.getOrNull()
        if (array != null) return catalogFrom(jsonModels(array))
    }
    val models = mutableListOf<CursorModel>()
    trimmed.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("Tip:", true) ||
            line.equals("Available models", true) || line.startsWith("No models", true)
        ) {
            return@forEach
        }
        val described = Regex("""^(\S+)\s+-\s+(.+)$""").matchEntire(line)
        val id = described?.groupValues?.get(1) ?: line.takeIf { ' ' !in it }
        if (id == null || id.length !in 2..160) return@forEach
        models += cursorModelFromText(id, described?.groupValues?.get(2).orEmpty())
    }
    return catalogFrom(models)
}

/** Every query word must match the name, id, vendor, version, or context window. */
internal fun filterCursorModels(models: List<CursorModel>, query: String): List<CursorModel> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return models
    return models.filter { model -> tokens.all { token -> cursorModelMatches(model, token) } }
}

internal fun cursorModelMatches(model: CursorModel, token: String): Boolean {
    val wanted = parseContextQuery(token)
    if (wanted != null) {
        val limits = listOfNotNull(model.contextTokens, model.maxContextTokens)
        if (limits.any { sameContextLabel(it, wanted) }) return true
    }
    val needle = looseSearchText(token)
    if (needle.isEmpty()) return true
    return looseSearchText(model.searchBlob()).contains(needle)
}

internal fun formatContextTokens(tokens: Long): String {
    if (tokens >= 1_000_000L) {
        val whole = tokens / 1_000_000L
        val fraction = ((tokens % 1_000_000L) / 100_000L).toInt()
        return if (fraction == 0) "${whole}M" else "$whole.${fraction}M"
    }
    if (tokens >= 1_000L) {
        val whole = tokens / 1_000L
        val fraction = ((tokens % 1_000L) / 100L).toInt()
        return if (fraction == 0) "${whole}K" else "$whole.${fraction}K"
    }
    return tokens.toString()
}

private fun catalogFrom(models: List<CursorModel>): CursorModelCatalog {
    val distinct = models.distinctBy { it.id }
    return CursorModelCatalog(distinct, distinct.firstOrNull { it.isDefault }?.id)
}

private fun CursorModel.searchBlob(): String = buildString {
    append(id).append(' ').append(name).append(' ')
    contextWindowLabel()?.let { append(it).append(' ') }
    append(vendorSearchTerms(id, name))
}

private fun vendorSearchTerms(id: String, name: String): String {
    val blob = "$id $name".lowercase()
    return buildString {
        if ("gemini" in blob || "gemma" in blob) append("google gemini ")
        if ("claude" in blob) append("anthropic claude ")
        if ("gpt" in blob || "openai" in blob) append("openai gpt ")
        if ("grok" in blob) append("xai grok ")
        if ("composer" in blob) append("cursor composer ")
    }
}

private fun looseSearchText(value: String): String =
    value.lowercase()
        .replace(Regex("(?<=\\d)-(?=\\d)"), ".")
        .replace(Regex("[^a-z0-9.]+"), "")

private fun sameContextLabel(left: Long, right: Long): Boolean =
    formatContextTokens(left).equals(formatContextTokens(right), ignoreCase = true)

/** "1m", "1 million", and "1000000" are context sizes. "4.5" is a version, not a size. */
private fun parseContextQuery(token: String): Long? {
    val compact = token.lowercase().replace(Regex("[\\s,_]"), "")
    CONTEXT_AMOUNT.find(compact)?.let { return it.toContextTokens() }
    val digits = compact.replace(",", "")
    val plain = digits.toLongOrNull() ?: return null
    return plain.takeIf { it >= 1_000L }
}

private fun cursorModelFromText(id: String, detail: String): CursorModel {
    val isDefault = detail.contains("default", ignoreCase = true)
    val cleaned = detail.replace(STATUS_PARENS, " ").replace(Regex("\\s+"), " ").trim()
    val amounts = CONTEXT_AMOUNT.findAll("$id $cleaned").map { it.toContextTokens() }.distinct().sorted().toList()
    val name = stripContextPhrases(cleaned).ifBlank {
        stripContextPhrases(id.substringBefore('[')).ifBlank { id.substringBefore('[') }
    }
    return CursorModel(
        id = id,
        name = name,
        contextTokens = amounts.firstOrNull(),
        maxContextTokens = amounts.lastOrNull()?.takeIf { amounts.size > 1 },
        isDefault = isDefault,
    )
}

private fun stripContextPhrases(value: String): String =
    value.replace(CONTEXT_PHRASE, " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '·', '|', ',', '/')

private fun jsonModels(array: JSONArray): List<CursorModel> =
    (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index)
        when {
            obj != null -> jsonModel(obj)
            else -> array.optString(index)
                .takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
                ?.let { cursorModelFromText(it, "") }
        }
    }

private fun jsonModel(obj: JSONObject): CursorModel? {
    val id = obj.optString("id").ifBlank { obj.optString("modelId") }.ifBlank { obj.optString("name") }
        .takeIf { it.isNotBlank() } ?: return null
    val display = obj.optString("displayName")
        .ifBlank { obj.optString("clientDisplayName") }
        .ifBlank { obj.optString("display_name") }
        .ifBlank { obj.optString("client_display_name") }
    val parsed = cursorModelFromText(id, display)
    val context = obj.longField("contextTokenLimit", "context_token_limit", "contextWindow", "contextTokens")
    val maxContext = obj.longField("contextTokenLimitForMaxMode", "context_token_limit_for_max_mode")
    return parsed.copy(
        name = stripContextPhrases(display).ifBlank { parsed.name },
        contextTokens = context ?: parsed.contextTokens,
        maxContextTokens = maxContext ?: parsed.maxContextTokens,
        isDefault = parsed.isDefault || obj.optBoolean("default") || obj.optBoolean("isDefault"),
    )
}

private fun JSONObject.longField(vararg names: String): Long? {
    names.forEach { name ->
        if (!has(name) || isNull(name)) return@forEach
        val number = optLong(name, Long.MIN_VALUE)
        if (number != Long.MIN_VALUE && number > 0L) return number
    }
    return null
}

private fun MatchResult.toContextTokens(): Long {
    val number = groupValues[1].toDouble()
    val unit = groupValues[2].lowercase()
    val tokens = if (unit == "m") number * 1_000_000.0 else number * 1_000.0
    return tokens.toLong()
}

private val CURSOR_ANSI = Regex("\\u001B\\[[0-9;]*[A-Za-z]")
private val STATUS_PARENS = Regex("""(?i)\((?:current|default)(?:\s*,\s*(?:current|default))*\)""")
private val CONTEXT_AMOUNT = Regex("""(?i)(?:context\s*=\s*)?(\d+(?:\.\d+)?)[-_\s]*([km])(?:illion)?(?=$|[^a-z])""")
private val CONTEXT_PHRASE = Regex("""(?i)(?:\s*[·|,/-]\s*)?(?:context\s*=\s*)?\d+(?:\.\d+)?[-_\s]*[km](?:illion)?\s*(?:tokens?|context|ctx)?""")

private const val HELLO_TIMEOUT_MILLIS = 90_000L
private const val UTILITY_TIMEOUT_MILLIS = 45_000L
private const val CURSOR_HISTORY_CHARACTERS = 160_000
private const val PROMPT_ARGV_LIMIT = 100_000
private const val MAX_UTILITY_OUTPUT_CHARS = 24_000

private suspend fun stopCursorAfterTerminalResult(process: Process) {
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

class CursorRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val apiKey: () -> String?,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false
    @Volatile private var activeProjectSlug = ""
    @Volatile private var taskStartedAtElapsedRealtime = 0L
    @Volatile private var lastForegroundProgressAt = 0L

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    suspend fun hello(timeoutMillis: Long = HELLO_TIMEOUT_MILLIS): String = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.CURSOR_CLI)) {
            throw CursorSessionException(CURSOR_NOT_INSTALLED)
        }
        val key = apiKey()?.takeIf(String::isNotBlank) ?: throw CursorSessionException(CURSOR_NEEDS_KEY)
        try {
            withTimeout(timeoutMillis) {
                runCursor(
                    slug = "cursor-hello",
                    workspace = File(context.cacheDir, "cursor-hello").apply { mkdirs() },
                    prompt = "Reply with exactly: ok",
                    sessionId = null,
                    apiKey = key,
                    timeoutLabel = "hello",
                ).text.ifBlank { "ok" }
            }
        } catch (error: TimeoutCancellationException) {
            throw CursorSessionException(CURSOR_TIMEOUT)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: CursorSessionException) {
            throw error
        } catch (error: Throwable) {
            throw CursorSessionException(cursorFriendlyError(scrubCursorSecret(error.message.orEmpty(), key)))
        }
    }

    suspend fun listModels(): CursorModelCatalog = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.CURSOR_CLI)) {
            throw CursorSessionException(CURSOR_NOT_INSTALLED)
        }
        val key = apiKey()?.takeIf(String::isNotBlank) ?: throw CursorSessionException(CURSOR_NEEDS_KEY)
        val primary = runCatching {
            runUtility(listOf(RuntimeInstaller.CURSOR_GUEST_PATH, "models", "--disable-auto-update"), key)
        }
        val parsed = primary.getOrNull()?.let(::parseCursorModelCatalog)
        if (parsed != null && parsed.ids.isNotEmpty()) return@withContext parsed
        val failure = primary.exceptionOrNull()
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        val mapped = failure?.message?.let(::cursorFriendlyError)
        if (failure != null && mapped in setOf(
                CURSOR_NEEDS_KEY,
                CURSOR_QUOTA,
                CURSOR_NETWORK,
                CURSOR_NOT_INSTALLED,
                CURSOR_NO_SPACE,
                CURSOR_TIMEOUT,
                CURSOR_INCOMPATIBLE,
            )
        ) {
            throw failure
        }
        val fallback = parseCursorModelCatalog(
            runUtility(listOf(RuntimeInstaller.CURSOR_GUEST_PATH, "--list-models", "--disable-auto-update"), key),
        )
        if (fallback.ids.isNotEmpty()) return@withContext fallback
        if (failure != null) throw failure
        parsed ?: CursorModelCatalog(emptyList(), null)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.CURSOR_CLI)) return@withContext
        try {
            runUtility(listOf(RuntimeInstaller.CURSOR_GUEST_PATH, "logout", "--disable-auto-update"), apiKey = null)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            // The local key is already removed. A failed logout must not block that.
        }
        Unit
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
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.CURSOR_CLI)) {
            emitFailure(sessionId, CURSOR_NOT_INSTALLED)
            releaseCursorSession()
            return@withContext sessionId
        }
        val key = apiKey()?.takeIf(String::isNotBlank)
        if (key == null) {
            emitFailure(sessionId, CURSOR_NEEDS_KEY)
            releaseCursorSession()
            return@withContext sessionId
        }
        var promptFile: File? = null
        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                activeProcess?.destroy()
            }
            startForegroundRuntime(projectSlug)
            pushForegroundProgress(R.string.rt_bridge_starting, "Cursor CLI")
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val savedSession = conversationId(projectId)?.takeIf(String::isNotBlank)
            val fullPrompt = cursorWorkspacePrompt(
                projectSlug,
                prompt,
                if (savedSession == null) conversationHistory else emptyList(),
            )
            val promptArgument = if (fullPrompt.length > PROMPT_ARGV_LIMIT) {
                val file = File(workspace, ".pocket/cursor-prompt.txt")
                promptFile = file
                file.parentFile?.mkdirs()
                file.writeText(fullPrompt)
                "Read /workspace/$projectSlug/.pocket/cursor-prompt.txt and follow it exactly."
            } else {
                fullPrompt
            }
            val command = cursorCommand(projectSlug, model(), savedSession, promptArgument)
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                cursorEnvironment(key),
                command,
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
                pseudoTerminal = false,
            )
            activeProcess = process
            runCatching { process.outputStream.close() }
            if (userStopRequested) process.destroy()
            val native = process as? NativeSpawnProcess ?: error("Unsupported Cursor process")
            var offset = 0L
            val pending = StringBuilder()
            var resultSeen = false
            var assistantTextSeen = false
            var reportedModel: String? = null
            suspend fun emitUsage(usage: CursorTokenUsage?) {
                val summary = cursorUsageNote(
                    model = cursorShownModel(model(), reportedModel),
                    usage = usage,
                    modelLabel = { context.getString(R.string.cursor_usage_model, it) },
                    inputLabel = { context.getString(R.string.cursor_usage_input, it) },
                    outputLabel = { context.getString(R.string.cursor_usage_output, it) },
                    cacheLabel = { context.getString(R.string.cursor_usage_cache, it) },
                ) ?: return
                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, "\n\n$summary"))
            }
            suspend fun handleLine(line: String) {
                when (val event = CursorEventParser.parse(line)) {
                    is CursorParsedEvent.Session -> {
                        saveConversationId(projectId, event.sessionId)
                        event.model?.takeIf(String::isNotBlank)?.let { reportedModel = it }
                    }
                    is CursorParsedEvent.AssistantDelta -> {
                        assistantTextSeen = true
                        pushForegroundProgress(R.string.rt_bridge_working, "Cursor CLI")
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.text))
                    }
                    is CursorParsedEvent.ToolStarted -> {
                        pushForegroundProgress(
                            AppStrings.get(context, R.string.rt_bridge_running_tool, event.name, event.detail),
                        )
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    }
                    is CursorParsedEvent.ToolCompleted -> {
                        pushForegroundProgress(R.string.rt_bridge_working, "Cursor CLI")
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    }
                    is CursorParsedEvent.Success -> {
                        event.sessionId?.let { saveConversationId(projectId, it) }
                        if (!assistantTextSeen && !event.resultText.isNullOrBlank()) {
                            assistantTextSeen = true
                            eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.resultText))
                        }
                        emitUsage(event.usage)
                        resultSeen = true
                    }
                    is CursorParsedEvent.Failure -> {
                        emitUsage(event.usage)
                        throw CursorSessionException(cursorFriendlyError(scrubCursorSecret(event.message, key)))
                    }
                    null -> Unit
                }
            }
            outputLoop@ while (process.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    pushForegroundProgress(R.string.rt_bridge_working, "Cursor CLI")
                    delay(50)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) {
                    delay(50)
                    continue
                }
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
                stopCursorAfterTerminalResult(process)
            } else {
                pending.toString().trim().takeIf(String::isNotEmpty)?.let { handleLine(it) }
                val exit = process.waitFor()
                check(exit == 0 && resultSeen) {
                    cursorFriendlyError(
                        scrubCursorSecret(
                            pending.toString().takeLast(1_000).ifBlank { "Cursor CLI exited with code $exit" },
                            key,
                        ),
                    )
                }
            }
            val paths = checkpoints.changedFiles(workspace, before)
            checkpoints.saveChangedPaths(projectId, paths)
            if (paths.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
            }
            emitCompleted(sessionId)
            finishForegroundRuntime(true, projectSlug, AppStrings.get(context, R.string.rt_bridge_finished_in, "Cursor CLI", projectSlug))
        }.onFailure { error ->
            val message = if (userStopRequested) {
                "Stopped by user"
            } else {
                cursorFriendlyError(scrubCursorSecret(error.message.orEmpty(), key))
            }
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        runCatching { promptFile?.delete() }
        releaseCursorSession()
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // --force is intentional. Cursor v1 runs tools without a PocketDev approval dialog.
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

    private data class CursorRun(val text: String)

    private suspend fun runCursor(
        slug: String,
        workspace: File,
        prompt: String,
        sessionId: String?,
        apiKey: String,
        timeoutLabel: String,
    ): CursorRun {
        val installed = installer.installedRuntime()
        val outputFile = File(context.cacheDir, "cursor-$timeoutLabel-${System.nanoTime()}.log")
        val process = installer.process(
            installed.proot,
            installed.rootfs,
            workspace,
            cursorEnvironment(apiKey),
            cursorCommand(slug, model(), sessionId, prompt),
            guestWorkspacePath = "/workspace/$slug",
            emulateHardLinks = false,
            pseudoTerminal = false,
            outputFile = outputFile,
        )
        return try {
            runCatching { process.outputStream.close() }
            readCursorOutput(process, outputFile, apiKey)
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
        }
    }

    private suspend fun readCursorOutput(process: Process, outputFile: File, apiKey: String): CursorRun {
        var offset = 0L
        val pending = StringBuilder()
        var reply: String? = null
        var done = false
        fun handleLine(line: String): Boolean {
            when (val event = CursorEventParser.parse(line)) {
                is CursorParsedEvent.AssistantDelta -> if (reply == null && event.text.isNotBlank()) reply = event.text
                is CursorParsedEvent.Success -> {
                    if (reply == null && !event.resultText.isNullOrBlank()) reply = event.resultText
                    return true
                }
                is CursorParsedEvent.Failure -> {
                    throw CursorSessionException(cursorFriendlyError(scrubCursorSecret(event.message, apiKey)))
                }
                else -> Unit
            }
            return false
        }
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
            if (count <= 0) {
                delay(50)
                continue
            }
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
        if (!done) {
            val exit = if (process.isAlive) null else process.exitValue()
            val tail = pending.toString().takeLast(1_000)
            throw CursorSessionException(
                cursorFriendlyError(scrubCursorSecret(tail.ifBlank { "Cursor CLI exited with code $exit" }, apiKey)),
            )
        }
        stopCursorAfterTerminalResult(process)
        return CursorRun(reply?.trim().orEmpty())
    }

    private suspend fun runUtility(command: List<String>, apiKey: String?): String {
        val installed = installer.installedRuntime()
        val workspace = File(context.cacheDir, "cursor-util").apply { mkdirs() }
        val outputFile = File(context.cacheDir, "cursor-util-${System.nanoTime()}.log")
        val process = installer.process(
            installed.proot,
            installed.rootfs,
            workspace,
            buildMap {
                put("NO_UPDATE_NOTIFIER", "1")
                apiKey?.takeIf(String::isNotBlank)?.let { put("CURSOR_API_KEY", it) }
            },
            command,
            guestWorkspacePath = "/workspace/cursor-hello",
            emulateHardLinks = false,
            pseudoTerminal = false,
            outputFile = outputFile,
        )
        return try {
            withTimeout(UTILITY_TIMEOUT_MILLIS) {
                runCatching { process.outputStream.close() }
                while (process.isAlive) delay(100)
                val exit = process.waitFor()
                val output = readUtilityOutput(outputFile)
                if (exit != 0) {
                    throw CursorSessionException(
                        cursorFriendlyError(
                            scrubCursorSecret(
                                output.takeLast(1_000).ifBlank { "Cursor CLI exited with code $exit" },
                                apiKey,
                            ),
                        ),
                    )
                }
                output
            }
        } catch (error: TimeoutCancellationException) {
            throw CursorSessionException(CURSOR_TIMEOUT)
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
        }
    }

    private fun readUtilityOutput(file: File): String {
        if (!file.isFile) return ""
        return file.inputStream().use { input ->
            val buffer = ByteArray(MAX_UTILITY_OUTPUT_CHARS)
            val count = input.read(buffer)
            if (count <= 0) "" else buffer.decodeToString(0, count)
        }
    }

    private fun releaseCursorSession() {
        activeProcess = null
        activeSessionId = null
        activeProjectSlug = ""
        taskStartedAtElapsedRealtime = 0L
        RuntimeTaskController.stopAction = null
    }

    private fun cursorEnvironment(secret: String): Map<String, String> = mapOf(
        "CURSOR_API_KEY" to secret,
        "NO_UPDATE_NOTIFIER" to "1",
    )

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

    private fun pushForegroundProgress(@androidx.annotation.StringRes id: Int, vararg args: Any) {
        pushForegroundProgress(AppStrings.get(context, id, *args))
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsed = (now - taskStartedAtElapsedRealtime).coerceAtLeast(0L) / 1_000L
        val minutes = elapsed / 60L
        val seconds = elapsed % 60L
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, "$detail · $minutes:${seconds.toString().padStart(2, '0')}"),
            )
        }
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

    private companion object {
        const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 10_000L
    }
}

private class CursorSessionException(message: String) : IllegalStateException(message)
