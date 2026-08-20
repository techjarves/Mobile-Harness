package dev.pocket.app.runtime

import dev.pocket.app.model.ProviderKind
import dev.pocket.app.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun openAiRequiresFormatGateway() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchConfigBuilder.build(ProviderProfile(ProviderKind.OPENAI))
        }
    }

    @Test
    fun openAiUsesLocalGatewayWithoutExposingProviderUrlToClaude() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.OPENAI, model = "gpt-test"),
            localGatewayUrl = "http://127.0.0.1:8765/",
        )

        assertEquals("http://127.0.0.1:8765", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("gpt-test", config.environment["ANTHROPIC_MODEL"])
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
    }
}
