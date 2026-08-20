package dev.pocket.app.runtime

import dev.pocket.app.model.ProviderProfile
import dev.pocket.app.model.RuntimeEvent
import dev.pocket.app.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(projectId: String, prompt: String, provider: ProviderProfile): String
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
}

object RuntimeLaunchConfigBuilder {
    fun build(profile: ProviderProfile, authToken: String? = null, localGatewayUrl: String? = null): RuntimeLaunchConfig {
        val environment = linkedMapOf("DISABLE_AUTOUPDATER" to "1")
        when (profile.kind.protocol) {
            dev.pocket.app.model.ProviderProtocol.CLAUDE_LOGIN -> Unit
            dev.pocket.app.model.ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            dev.pocket.app.model.ProviderProtocol.ANTHROPIC_GATEWAY -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            dev.pocket.app.model.ProviderProtocol.OPENAI_RESPONSES,
            dev.pocket.app.model.ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
        }
        if (profile.kind.protocol != dev.pocket.app.model.ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = profile.model
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = profile.model
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = profile.model
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = profile.model
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            if (!authToken.isNullOrBlank()) {
                if (profile.kind == dev.pocket.app.model.ProviderKind.ANTHROPIC) {
                    environment["ANTHROPIC_API_KEY"] = authToken
                } else {
                    environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
