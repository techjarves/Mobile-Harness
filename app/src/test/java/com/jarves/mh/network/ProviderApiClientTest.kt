package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun openRouterProbeIncludesRequestedProviderOrder() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "anthropic/claude-sonnet-4.6",
                protocol = ProviderProtocol.OPENROUTER,
                openRouterProviderOrder = " anthropic, amazon-bedrock, anthropic ",
                openRouterAllowFallbacks = false,
            ),
        )

        val routing = body.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), routing.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(routing.getBoolean("allow_fallbacks"))
        assertEquals(8, body.getInt("max_tokens"))
    }

    @Test
    fun chatAndAnthropicCompatibleProbesUseProviderSafeTokenLimit() {
        val client = ProviderApiClient()
        val chat = JSONObject(client.validationBody("chat-model", ProviderProtocol.OPENAI_CHAT))
        val anthropic = JSONObject(client.validationBody("claude-model", ProviderProtocol.ANTHROPIC))

        assertEquals(8, chat.getInt("max_tokens"))
        assertEquals(8, anthropic.getInt("max_tokens"))
    }

    @Test
    fun openCodeGoEndpointsTargetAnthropicMessages() {
        val client = ProviderApiClient()
        val base = "https://opencode.ai/inference/go/anthropic"

        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
            client.messagesEndpoint(base, ProviderProtocol.ANTHROPIC_GATEWAY),
        )
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1/models",
            client.modelEndpoints(base, ProviderProtocol.ANTHROPIC_GATEWAY).first(),
        )
    }

    @Test
    fun openCodeGoRequestsCarrySessionAndMobileHarnessAgent() {
        val headers = openCodeGoHeaders(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
            sessionId = "ses_abc123",
        )

        assertEquals("ses_abc123", headers["x-opencode-session"])
        assertTrue(headers.getValue("User-Agent").startsWith("MobileHarness/"))
    }

    @Test
    fun otherEndpointsGetNoOpenCodeGoHeaders() {
        assertTrue(openCodeGoHeaders("https://api.deepseek.com/anthropic/v1/messages", "ses_abc123").isEmpty())
        assertTrue(openCodeGoHeaders("https://opencode.ai/zen/v1/responses", "ses_abc123").isEmpty())
    }

    @Test
    fun openCodeGoKeysAreRedactedFromProviderErrors() {
        val message = ProviderApiClient().providerErrorMessage(
            """{"error":{"message":"invalid credential oc_sk_abc12345"}}""",
        )

        assertTrue(message!!.contains("oc_sk_••••"))
        assertFalse(message.contains("oc_sk_abc12345"))
    }
}
