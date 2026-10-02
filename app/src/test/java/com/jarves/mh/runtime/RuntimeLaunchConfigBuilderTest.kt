package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeLaunchConfigBuilderTest {
    @Test
    fun gatewayProfileUsesCustomBaseUrlAndModel() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.LLM_ROUTER, "https://gateway.example/", "model-a", true),
        )

        assertEquals("https://gateway.example", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("model-a", config.environment["ANTHROPIC_MODEL"])
        assertEquals("1", config.environment["DISABLE_AUTOUPDATER"])
        assertFalse(config.arguments.contains("--dangerously-skip-permissions"))
    }

    @Test
    fun kimiUsesItsAnthropicCompatibleEndpointDirectly() {
        val config = RuntimeLaunchConfigBuilder.build(ProviderProfile(ProviderKind.KIMI))

        assertEquals("https://api.moonshot.ai/anthropic", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("kimi-k2.6", config.environment["ANTHROPIC_MODEL"])
    }

    @Test
    fun configuresEveryClaudeModelRoleAndInMemoryAuth() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CUSTOM, "https://example.test/anthropic", "custom-model", true),
            authToken = "temporary-secret",
        )

        assertEquals("custom-model", config.environment["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("custom-model", config.environment["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("custom-model", config.environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("custom-model", config.environment["CLAUDE_CODE_SUBAGENT_MODEL"])
        assertEquals("1", config.environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"])
        assertEquals("temporary-secret", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("temporary-secret", config.environment["ANTHROPIC_API_KEY"])
    }

    @Test
    fun openRouterMatchesVerifiedClaudeCodeEnvironment() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.LLM_ROUTER, "https://openrouter.ai/api/", "stealth/ox-alpha", true),
            authToken = "temporary-openrouter-secret",
        )

        assertEquals("https://openrouter.ai/api", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("temporary-openrouter-secret", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("temporary-openrouter-secret", config.environment["OPENROUTER_API_KEY"])
        assertEquals("", config.environment["ANTHROPIC_API_KEY"])
    }

    @Test
    fun openRouterCanUseLocalRoutingGateway() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.LLM_ROUTER),
            authToken = "temporary-openrouter-secret",
            localGatewayUrl = "http://127.0.0.1:23456",
        )

        assertEquals("http://127.0.0.1:23456", config.environment["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun claudeSubscriptionUsesOAuthTokenWithoutApiKeyFallback() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CLAUDE),
            authToken = "subscription-token",
        )

        assertEquals("subscription-token", config.environment["CLAUDE_CODE_OAUTH_TOKEN"])
        assertEquals("", config.environment["ANTHROPIC_API_KEY"])
        assertEquals("", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertNull(config.environment["ANTHROPIC_BASE_URL"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionRequiresToken() {
        RuntimeLaunchConfigBuilder.build(ProviderProfile(ProviderKind.CLAUDE))
    }

    @Test
    fun nvidiaNimUsesOpenAiCompatibilityGateway() {
        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        val config = RuntimeLaunchConfigBuilder.build(
            profile,
            authToken = "nvapi-secret",
            localGatewayUrl = "http://127.0.0.1:12345",
        )

        assertEquals("http://127.0.0.1:12345", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("claude-sonnet-4-6", config.environment["ANTHROPIC_MODEL"])
    }

    @Test
    fun omniRouteUsesOpenAiCompatibilityGateway() {
        val profile = ProviderProfile(ProviderKind.OMNIROUTE, baseUrl = "http://100.64.0.1:20128/v1", model = "auto")
        val config = RuntimeLaunchConfigBuilder.build(
            profile,
            authToken = "omniroute-key",
            localGatewayUrl = "http://127.0.0.1:20128",
        )

        assertEquals("http://127.0.0.1:20128", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("claude-sonnet-4-6", config.environment["ANTHROPIC_MODEL"])
        assertEquals("omniroute-key", config.environment["ANTHROPIC_AUTH_TOKEN"])
    }
}
