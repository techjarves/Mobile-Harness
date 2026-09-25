package com.jarves.mh.model

import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

enum class ProviderProtocol { CLAUDE_LOGIN, ANTHROPIC, ANTHROPIC_GATEWAY, OPENROUTER, OPENAI_RESPONSES, OPENAI_CHAT }

enum class ProviderKind(
    val title: String,
    val subtitle: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val experimental: Boolean = false,
    val fixedBaseUrl: Boolean = false,
    val fixedProtocol: Boolean = false,
) {
    CLAUDE("Claude subscription", "Pro, Max, Team or Enterprise", ProviderProtocol.CLAUDE_LOGIN, "", "default"),
    ANTHROPIC("Anthropic API", "Usage billed through Console", ProviderProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-6"),
    LLM_ROUTER("OpenRouter", "Use your OpenRouter API key", ProviderProtocol.OPENROUTER, "https://openrouter.ai/api", "~anthropic/claude-sonnet-latest"),
    DEEPSEEK("DeepSeek", "Use your DeepSeek API key", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.deepseek.com/anthropic", "deepseek-v4-flash"),
    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),
    OPENCODE_ZEN(
        "OpenCode Zen",
        "Models through the OpenCode Zen gateway",
        ProviderProtocol.OPENAI_RESPONSES,
        "https://opencode.ai/zen/v1",
        "deepseek-v4-flash",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM",
        "OpenAI-compatible models hosted by NVIDIA",
        ProviderProtocol.OPENAI_CHAT,
        "https://integrate.api.nvidia.com/v1",
        "qwen/qwen2.5-coder-32b-instruct",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    CUSTOM("Custom API", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "", "", true),
}

/**
 * Coding agent engine installed in the private Linux runtime.
 * Each coding agent is installed independently on demand over the shared Core runtime.
 */
enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    CLAUDE_CODE(
        "claude-code",
        "Claude Code",
        "Anthropic's coding agent · broad provider support",
        "71.8 MB",
    ),
    DEEPSEEK_HARNESS(
        "deepseek-harness",
        "DeepSeek Harness",
        "Official DeepSeek coding agent · API-key providers",
        "26.5 MB",
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity CLI",
        "Google's official coding agent · Google account",
        "39.9 MB",
    ),
    ;

    companion object {
        fun fromStored(value: String?): AgentKind = entries.firstOrNull {
            it.stableId == value || it.name == value
        } ?: CLAUDE_CODE
    }
}

/** Provider kinds usable with [AgentKind.DEEPSEEK_HARNESS]. Claude OAuth login has no dsh equivalent. */
val DEEPSEEK_HARNESS_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.DEEPSEEK,
    ProviderKind.ANTHROPIC,
    ProviderKind.LLM_ROUTER,
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

val DSH_PROTOCOL_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

fun defaultDshApiForProvider(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENCODE_ZEN -> "openai-responses"
    ProviderKind.NVIDIA_NIM -> "openai-completions"
    else -> "anthropic-messages"
}

/**
 * Best-effort protocol choice for a user-entered custom gateway URL.
 * The picker remains editable because a URL alone cannot prove a gateway's wire format.
 */
fun inferredDshApiForUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
    return when {
        normalized.endsWith("/responses") -> "openai-responses"
        "/anthropic" in normalized || "api.anthropic.com" in normalized -> "anthropic-messages"
        normalized.endsWith("/v1") -> "openai-completions"
        else -> "anthropic-messages"
    }
}

/** Resolves the protocol DeepSeek Harness will actually use for this saved profile. */
fun providerProtocolForAgent(profile: ProviderProfile, agent: AgentKind): ProviderProtocol {
    if (agent != AgentKind.DEEPSEEK_HARNESS || profile.kind !in DSH_PROTOCOL_PROVIDERS) {
        return profile.kind.protocol
    }
    val api = if (profile.kind.fixedProtocol) defaultDshApiForProvider(profile.kind) else profile.dshApi
    return when (api) {
        "openai-completions" -> ProviderProtocol.OPENAI_CHAT
        "openai-responses" -> ProviderProtocol.OPENAI_RESPONSES
        else -> ProviderProtocol.ANTHROPIC_GATEWAY
    }
}

/** Provider choices shown for the selected coding agent. */
fun providersForAgent(agent: AgentKind): List<ProviderKind> = when (agent) {
    AgentKind.DEEPSEEK_HARNESS -> ProviderKind.entries.filter { it in DEEPSEEK_HARNESS_PROVIDERS }
    AgentKind.CLAUDE_CODE -> ProviderKind.entries.filterNot { it == ProviderKind.OPENCODE_ZEN }
    AgentKind.ANTIGRAVITY -> emptyList()
}

data class ProviderProfile(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val hasSecret: Boolean = false,
    /** dsh custom-route wire protocol for CUSTOM: anthropic-messages | openai-completions | openai-responses. */
    val dshApi: String = defaultDshApiForProvider(kind),
) {
    /** Effective base URL: fixed kinds always resolve to their constant, ignoring stored drift. */
    val resolvedBaseUrl: String get() = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl
}

enum class ProjectKind { PROJECT, QUICK_PROJECT }

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val language: String,
    val slug: String = projectSlug(name),
    val rootPath: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val kind: ProjectKind = ProjectKind.PROJECT,
) {
    val formattedUpdatedAt: String
        get() {
            val diff = System.currentTimeMillis() - updatedAtMillis
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                diff < 0 || seconds < 60 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday"
                days < 7 -> "${days}d ago"
                else -> {
                    val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(updatedAtMillis))
                }
            }
        }
}

fun projectSlug(name: String): String {
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return ascii.ifBlank { "project" }
}

data class QuickChatIdentity(val displayName: String, val slug: String)

fun generateQuickChatIdentity(usedSlugs: Set<String>, random: Random = Random.Default): QuickChatIdentity {
    val adjectives = listOf("bright", "calm", "clever", "curious", "gentle", "nimble", "quiet", "swift", "wise", "bold")
    val pioneers = listOf("turing", "lovelace", "hopper", "tesla", "curie", "ramanujan", "bose", "kalam", "faraday", "darwin")
    repeat(20) {
        val base = "${adjectives.random(random)}-${pioneers.random(random)}"
        if (base !in usedSlugs) return QuickChatIdentity(base.toDisplayName(), base)
    }
    val base = "${adjectives.random(random)}-${pioneers.random(random)}"
    val slug = generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in usedSlugs }
    return QuickChatIdentity(slug.toDisplayName(), slug)
}

private fun String.toDisplayName(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val sizeBytes: Long = 0,
)

enum class RiskLevel { SAFE, REVIEW, HIGH }

/**
 * How much freedom the coding agent gets over tool calls (ISSUE-001).
 *
 * The default is [APPROVE_RISKY]: everyday edits run automatically while
 * destructive or network commands wait for an explicit user approval.
 * [FULLY_AUTONOMOUS] restores the pre-v1.1.0 always-approve behavior and must
 * stay an explicit, informed choice.
 */
enum class AgentAutonomyMode(val stableId: String, val title: String, val summary: String) {
    FULLY_AUTONOMOUS(
        "fully_autonomous",
        "Fully autonomous",
        "Every tool call is approved automatically. Fastest and most dangerous: a prompt injection can run any command inside the workspace.",
    ),
    APPROVE_RISKY(
        "approve_risky",
        "Approve risky actions (recommended)",
        "Destructive and network commands wait for your approval. Everyday edits run automatically.",
    ),
    APPROVE_ALL(
        "approve_all",
        "Approve everything",
        "Every tool call waits for your approval. Safest, and it needs you nearby.",
    ),
    ;

    companion object {
        /** Unknown or missing stored values fall back to the safe default, never to autonomy. */
        fun fromStored(value: String?): AgentAutonomyMode =
            entries.firstOrNull { it.stableId == value } ?: APPROVE_RISKY
    }
}

/**
 * Optional development toolchains the user can pick during onboarding.
 * Node.js, npm, Git, and Claude Code itself are always installed because the
 * agent runtime depends on them; these stacks add heavier extras on demand.
 */
enum class DevStack(
    val label: String,
    val description: String,
    val installsSummary: String,
) {
    WEB(
        "Web (JavaScript / TypeScript)",
        "Websites and web apps with HTML, CSS, and JS frameworks.",
        "Node.js and npm (already included)",
    ),
    PYTHON(
        "Python",
        "Scripts, automation, data work, and Python backends.",
        "python3, pip, venv, and build tools",
    ),
    ANDROID(
        "Android (Java / Kotlin)",
        "Build Android app projects and install them directly on this phone.",
        "JDK 17, ARM64 Android SDK 36, Build Tools 35, Gradle 8.14.3, and an offline Maven cache",
    ),
    CPP(
        "C / C++",
        "Fast compiled programs, algorithms, and systems code.",
        "gcc, g++, make, cmake, gdb",
    ),
    PHP(
        "PHP",
        "Websites and apps with PHP — classic sites and Laravel projects.",
        "php-cli, common extensions, and Composer",
    ),
}

data class ToolRequest(
    val approvalId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel,
)

/**
 * A task that was in flight when the OS killed the app process (ISSUE-007,
 * roadmap 3h). Detected at startup from the session journal and surfaced as a
 * "Task interrupted — Resume / Dismiss" banner. `canResumeNatively` is true
 * when the agent's own conversation id was captured before the kill, letting
 * the resume continue the exact server-side context (--resume / --conversation)
 * instead of rebuilding it from the persisted transcript.
 */
data class InterruptedSession(
    val agentKind: AgentKind,
    val projectId: String,
    val projectSlug: String,
    val request: String,
    val startedAtMillis: Long,
    val canResumeNatively: Boolean,
)

sealed interface RuntimeEvent {
    val sessionId: String

    data class SessionStarted(override val sessionId: String) : RuntimeEvent
    data class AssistantDelta(override val sessionId: String, val text: String) : RuntimeEvent
    data class ReasoningProgress(override val sessionId: String, val estimatedTokens: Int) : RuntimeEvent
    data class ReasoningSummary(
        override val sessionId: String,
        val summary: String,
        val blockId: Long,
        val startsNewBlock: Boolean = false,
        val isFinal: Boolean = false,
    ) : RuntimeEvent
    data class ToolStarted(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class RuntimeLog(
        override val sessionId: String,
        val title: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolRequested(override val sessionId: String, val request: ToolRequest) : RuntimeEvent
    data class ToolApproved(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolRejected(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolCompleted(override val sessionId: String, val toolName: String, val summary: String) : RuntimeEvent
    data class FilesChanged(override val sessionId: String, val changes: List<ChangeItem>) : RuntimeEvent {
        val paths: List<String> get() = changes.map { it.path }
    }
    data class PreviewStarted(override val sessionId: String, val url: String) : RuntimeEvent
    data class SessionCompleted(override val sessionId: String) : RuntimeEvent
    data class SessionFailed(override val sessionId: String, val reason: String) : RuntimeEvent
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val attachments: List<ChatAttachment> = emptyList(),
    val workItems: List<ActivityItem> = emptyList(),
    val workedMillis: Long = 0L,
)

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ProjectChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

enum class DiffLineType { CONTEXT, ADDITION, DELETION, INFO }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class ChangeItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffLines: List<DiffLine> = emptyList(),
    val binary: Boolean = false,
    val accepted: Boolean? = null,
)

data class ActivityItem(
    val title: String,
    val detail: String,
    val isComplete: Boolean = true,
    val isCommand: Boolean = false,
)

/** A repository entry as reported by the official GitHub CLI (moved out of the retired GitHubClient). */
data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)
