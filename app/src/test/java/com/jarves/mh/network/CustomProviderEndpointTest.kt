package com.jarves.mh.network

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Custom API provider bug: gateways whose base URL
 * already ends in /v1 (e.g. https://opencode.ai/zen/v1) must be probed
 * without doubling the path segment (/v1/v1/messages), and the launch config
 * must hand Claude Code a base URL it can append /v1/messages to safely.
 */
class CustomProviderEndpointTest {

    @Test
    fun `messagesEndpointCandidates avoids doubled v1 for custom gateways`() {
        val method = ProviderApiClient::class.java.getDeclaredMethod(
            "messagesEndpointCandidates",
            String::class.java,
            com.jarves.mh.model.ProviderProtocol::class.java,
        )
        method.isAccessible = true

        val candidates = method.invoke(
            ProviderApiClient(),
            "https://opencode.ai/zen/v1",
            com.jarves.mh.model.ProviderProtocol.ANTHROPIC_GATEWAY,
        ) as List<*>

        // Correct path for a /v1-suffixed base: /messages appended directly.
        assertTrue(candidates.contains("https://opencode.ai/zen/v1/messages"))
        // The doubled segment that caused "The API endpoint was not found".
        assertTrue(candidates.none { it.toString().contains("v1/v1") })
    }

    @Test
    fun `messagesEndpointCandidates covers bare custom base urls`() {
        val method = ProviderApiClient::class.java.getDeclaredMethod(
            "messagesEndpointCandidates",
            String::class.java,
            com.jarves.mh.model.ProviderProtocol::class.java,
        )
        method.isAccessible = true

        val candidates = method.invoke(
            ProviderApiClient(),
            "https://my-gateway.example/zen",
            com.jarves.mh.model.ProviderProtocol.ANTHROPIC_GATEWAY,
        ) as List<*>

        assertTrue(candidates.contains("https://my-gateway.example/zen/v1/messages"))
        assertTrue(candidates.first() == "https://my-gateway.example/zen/messages")
    }

    @Test
    fun `custom openai provider routes through local format gateway`() {
        val config = com.jarves.mh.runtime.RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CUSTOM_OPENAI, "https://api.example.com/v1", "gpt-5.2", true),
            authToken = "temporary-secret",
            localGatewayUrl = "http://127.0.0.1:45678",
        )

        // Claude Code talks Anthropic protocol to the local loopback gateway,
        // which translates to OpenAI /chat/completions upstream.
        assertEquals("http://127.0.0.1:45678", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("temporary-secret", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("temporary-secret", config.environment["ANTHROPIC_API_KEY"])
    }

    @Test
    fun `model discovery candidates include v1-joined openai path`() {
        val method = ProviderApiClient::class.java.getDeclaredMethod(
            "modelEndpoints",
            String::class.java,
            com.jarves.mh.model.ProviderProtocol::class.java,
        )
        method.isAccessible = true

        val candidates = method.invoke(
            ProviderApiClient(),
            "https://api.example.com/v1",
            com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT,
        ) as List<*>

        assertTrue(candidates.contains("https://api.example.com/v1/models"))
        assertTrue(candidates.none { it.toString().contains("v1/v1") })
    }

    @Test
    fun `launch config strips trailing v1 from custom base url`() {
        val config = com.jarves.mh.runtime.RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CUSTOM, "https://opencode.ai/zen/v1/", "some-model", true),
            authToken = "temporary-secret",
        )
        assertEquals("https://opencode.ai/zen", config.environment["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun `launch config keeps anthropic suffix for gateway providers`() {
        val config = com.jarves.mh.runtime.RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.DEEPSEEK),
        )
        assertEquals("https://api.deepseek.com/anthropic", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("deepseek-v4-flash", config.environment["ANTHROPIC_MODEL"])
    }
}
