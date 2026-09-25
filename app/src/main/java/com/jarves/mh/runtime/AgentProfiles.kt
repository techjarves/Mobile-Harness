package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind

/**
 * Per-agent launch profile (ISSUE-039): the executable, base arguments and
 * translation-model id of each coding agent live here, next to the agent they
 * belong to — not inside the shared bridge contract.
 *
 * Adding a fourth agent means adding one [AgentProfile] entry plus its bridge;
 * `RuntimeBridge.kt` and the other agents are not touched. The shared
 * `RuntimeLaunchConfigBuilder` consumes whatever profile it is given.
 */
data class AgentProfile(
    val kind: AgentKind,
    /** Absolute guest path of the agent executable inside the PRoot rootfs. */
    val executable: String,
    /**
     * Model id advertised to a translation gateway when a provider speaks an
     * OpenAI wire protocol that has no Anthropic-native model name. `null`
     * when the agent never runs behind a translation gateway.
     */
    val translationModel: String? = null,
)

object AgentProfiles {

    val CLAUDE_CODE = AgentProfile(
        kind = AgentKind.CLAUDE_CODE,
        executable = "/usr/local/bin/claude",
        // The model id Claude Code may report when routed through the local
        // Anthropic-to-OpenAI format gateway.
        translationModel = "claude-sonnet-4-6",
    )

    val DEEPSEEK_HARNESS = AgentProfile(
        kind = AgentKind.DEEPSEEK_HARNESS,
        executable = "/usr/local/bin/dsh",
        translationModel = null,
    )

    val ANTIGRAVITY = AgentProfile(
        kind = AgentKind.ANTIGRAVITY,
        executable = RuntimeInstaller.AGY_GUEST_PATH,
        translationModel = null,
    )

    private val byKind = listOf(CLAUDE_CODE, DEEPSEEK_HARNESS, ANTIGRAVITY).associateBy { it.kind }

    fun forKind(kind: AgentKind): AgentProfile =
        byKind[kind] ?: error("No launch profile registered for agent '${kind.stableId}'")
}
