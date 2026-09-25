package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

/**
 * Placeholder secret handed to agents whose real credential is injected by the
 * [SovereignProxy] or the [LocalFormatGateway] on the host side (ISSUE-010).
 * The CLI only needs a non-empty value to start; the guest process env never
 * carries the real key, so `/proc/<pid>/environ` stays secret-free.
 */
const val PROXY_MANAGED_SECRET = "mobile-harness-proxy"

/**
 * Builds the launch environment for a Claude-compatible agent (ISSUE-039:
 * executable and translation model come from the [AgentProfile], and this
 * builder no longer lives inside the `RuntimeBridge` contract file).
 *
 * Credential routing, in order of preference:
 *  1. [proxyUrl] — the sovereign proxy injects the real key host-side. The
 *     guest env only receives [PROXY_MANAGED_SECRET].
 *  2. [localGatewayUrl] — the format gateway already holds the key host-side
 *     for OpenAI-protocol providers; the guest env receives the placeholder too.
 *  3. neither — legacy direct delivery via env (still used by the DSH native
 *     route, which has no configurable base URL; see SECURITY.md).
 */
object RuntimeLaunchConfigBuilder {
    fun build(
        profile: ProviderProfile,
        agent: AgentProfile = AgentProfiles.CLAUDE_CODE,
        authToken: String? = null,
        localGatewayUrl: String? = null,
        proxyUrl: String? = null,
    ): RuntimeLaunchConfig {
        val environment = linkedMapOf("DISABLE_AUTOUPDATER" to "1")
        val keyProtected = proxyUrl != null || localGatewayUrl != null
        when (profile.kind.protocol) {
            ProviderProtocol.CLAUDE_LOGIN -> {
                if (proxyUrl != null) {
                    // The proxy forwards to api.anthropic.com and attaches the
                    // OAuth bearer itself; the guest never sees the token.
                    environment["ANTHROPIC_BASE_URL"] = proxyUrl.trimEnd('/')
                    environment["ANTHROPIC_API_KEY"] = PROXY_MANAGED_SECRET
                    environment["ANTHROPIC_AUTH_TOKEN"] = ""
                } else {
                    require(!authToken.isNullOrBlank()) { "Enter a Claude subscription token first" }
                    environment["CLAUDE_CODE_OAUTH_TOKEN"] = authToken
                    // Claude Code gives API-key variables precedence over OAuth. Explicitly
                    // clear them so a previous API provider can never shadow this token.
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["ANTHROPIC_AUTH_TOKEN"] = ""
                }
            }
            ProviderProtocol.ANTHROPIC,
            ProviderProtocol.ANTHROPIC_GATEWAY,
            ProviderProtocol.OPENROUTER,
            -> {
                environment["ANTHROPIC_BASE_URL"] =
                    (proxyUrl ?: profile.baseUrl).trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENAI_RESPONSES,
            ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = agent.translationModel
                    ?: error("Agent '${agent.kind.stableId}' cannot run behind a translation gateway")
            }
        }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (profile.kind.protocol != ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (!authToken.isNullOrBlank()) {
                if (keyProtected) {
                    // The proxy/gateway holds the real key; the guest env stays clean.
                    environment["ANTHROPIC_AUTH_TOKEN"] = PROXY_MANAGED_SECRET
                    environment["ANTHROPIC_API_KEY"] = PROXY_MANAGED_SECRET
                } else if (profile.kind == com.jarves.mh.model.ProviderKind.LLM_ROUTER) {
                    environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["OPENROUTER_API_KEY"] = authToken
                } else {
                    environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                    environment["ANTHROPIC_API_KEY"] = authToken
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = agent.executable,
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
