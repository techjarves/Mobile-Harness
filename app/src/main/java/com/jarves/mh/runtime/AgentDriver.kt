package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind

/** Features exposed by an agent without teaching shared UI about a concrete CLI. */
enum class AgentCapability {
    API_KEY,
    ACCOUNT_LOGIN,
    PROVIDER_PICKER,
    MODEL_PICKER,
    REASONING_EFFORT,
    RESUME,
    INTERACTIVE_APPROVALS,
}

interface AgentDriver {
    val kind: AgentKind
    val runtime: RuntimeBridge
    val capabilities: Set<AgentCapability>
    fun isInstalled(installer: RuntimeInstaller): Boolean = installer.isAgentInstalled(kind)
    suspend fun install(installer: RuntimeInstaller, onProgress: suspend (RuntimeInstallProgress) -> Unit) =
        installer.ensureAgentInstalled(kind, onProgress)
}

private class BuiltInAgentDriver(
    override val kind: AgentKind,
    override val runtime: RuntimeBridge,
    override val capabilities: Set<AgentCapability>,
) : AgentDriver

/** Single selection point for agent runtimes. Adding an agent does not change existing bridges. */
class AgentRegistry(drivers: List<AgentDriver>) {
    private val byId = drivers.associateBy { it.kind.stableId }

    init {
        require(byId.size == drivers.size) { "Duplicate agent id" }
        require(AgentKind.entries.all { it.stableId in byId }) { "Every built-in agent must be registered" }
    }

    fun require(kind: AgentKind): AgentDriver =
        byId[kind.stableId] ?: error("Agent '${kind.stableId}' is not registered")

    companion object {
        fun builtIns(
            claude: RuntimeBridge,
            deepSeek: RuntimeBridge,
            antigravity: RuntimeBridge,
            hermes: RuntimeBridge,
        ) = AgentRegistry(
            listOf(
                BuiltInAgentDriver(
                    AgentKind.HERMES_AGENT,
                    hermes,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.REASONING_EFFORT,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.CLAUDE_CODE,
                    claude,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.ACCOUNT_LOGIN,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.DEEPSEEK_HARNESS,
                    deepSeek,
                    setOf(
                        AgentCapability.API_KEY,
                        AgentCapability.PROVIDER_PICKER,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.RESUME,
                        AgentCapability.INTERACTIVE_APPROVALS,
                    ),
                ),
                BuiltInAgentDriver(
                    AgentKind.ANTIGRAVITY,
                    antigravity,
                    setOf(
                        AgentCapability.ACCOUNT_LOGIN,
                        AgentCapability.MODEL_PICKER,
                        AgentCapability.REASONING_EFFORT,
                        AgentCapability.RESUME,
                    ),
                ),
            ),
        )
    }
}
