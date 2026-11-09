package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DEEPSEEK_HARNESS_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class DshHeadlessParserTest {
    @Test
    fun emptyReasoningHeadingStartsSection() {
        assertEquals(DshLine.ReasoningHeading, DshHeadlessParser.parseLine("dsh: reasoning:"))
    }

    @Test
    fun inlineReasoningDeltaIsReasoning() {
        val parsed = DshHeadlessParser.parseLine("dsh: reasoning: checking the workspace")
        assertEquals(DshLine.Reasoning("checking the workspace"), parsed)
    }

    @Test
    fun errorDiagnosticIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: MISSING_CREDENTIAL: llm-deepseek: no API key")
        assertTrue(parsed is DshLine.Diagnostic)
        assertEquals("MISSING_CREDENTIAL: llm-deepseek: no API key", (parsed as DshLine.Diagnostic).text)
    }

    @Test
    fun authFailureIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: AUTH: Authentication Fails, key is invalid")
        assertTrue(parsed is DshLine.Diagnostic)
    }

    @Test
    fun plainTextIsAnswer() {
        val parsed = DshHeadlessParser.parseLine("Here is the summary of your project.")
        assertEquals(DshLine.Answer("Here is the summary of your project."), parsed)
    }

    @Test
    fun whitespaceIsTrimmed() {
        val parsed = DshHeadlessParser.parseLine("   done   ")
        assertEquals(DshLine.Answer("done"), parsed)
    }
}

class DshSdkProtocolParserTest {
    private val parser = DshSdkProtocolParser("session-1")

    @Test
    fun parsesHandshakeAndLifecycle() {
        assertEquals(DshSdkProtocolEvent.Initialized, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"))
        assertEquals(DshSdkProtocolEvent.ShutdownAcknowledged, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"))
        assertEquals(
            DshSdkProtocolEvent.Status(true),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "running"))),
        )
        assertEquals(
            DshSdkProtocolEvent.Status(false),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "idle"))),
        )
    }

    @Test
    fun accumulatesReasoningAndClosesItsBlock() {
        val first = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "Checking "),
                ),
            ),
        )
        val second = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "files"),
                ),
            ),
        )
        val end = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "block-end").put("index", 0).put(
                        "block",
                        JSONObject().put("type", "reasoning").put("text", "Checking files"),
                    ),
                ),
            ),
        )

        assertTrue(first is DshSdkProtocolEvent.Reasoning && first.startsNewBlock && first.text == "Checking ")
        assertTrue(second is DshSdkProtocolEvent.Reasoning && !second.startsNewBlock && second.text == "Checking files")
        assertTrue(end is DshSdkProtocolEvent.Reasoning && end.isFinal && end.text == "Checking files")
    }

    @Test
    fun boundsLargeCumulativeReasoningToItsLatestContent() {
        val hugePrefix = "old reasoning ".repeat(1_000)
        parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 4).put("step", 1).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", hugePrefix),
                ),
            ),
        )
        val latest = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 4).put("step", 1).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "LATEST_MARKER"),
                ),
            ),
        )

        assertTrue(latest is DshSdkProtocolEvent.Reasoning)
        latest as DshSdkProtocolEvent.Reasoning
        assertTrue(latest.text.length <= 8_000)
        assertTrue(latest.text.endsWith("LATEST_MARKER"))
    }

    @Test
    fun compactsOnlyABoundedWindowOfLargeOutput() {
        val compact = compactDshText("ignored".repeat(10_000) + "\n  useful   tail ", 100, 80)

        assertTrue(compact.length <= 80)
        assertTrue(compact.endsWith("useful tail"))
    }

    @Test
    fun parsesToolCallResultAndAssistantText() {
        val call = parser.parseLine(sessionEvent("tool/call", JSONObject()
            .put("callId", "call-1").put("name", "bash")
            .put("arguments", "{\"command\":\"pwd && ls\"}")))
        val resultBlock = JSONObject().put("type", "tool-result").put("toolCallId", "call-1")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "done")))
        val result = parser.parseLine(sessionEvent("tool/result", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(resultBlock)))))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Finished"),
            )))))

        assertTrue(call is DshSdkProtocolEvent.ToolStarted && call.name == "Bash" && call.detail == "pwd && ls")
        assertTrue(result is DshSdkProtocolEvent.ToolCompleted && result.name == "Bash" && result.summary == "done")
        assertEquals(DshSdkProtocolEvent.AssistantText("Finished"), answer)
    }

    @Test
    fun streamsTextDeltasAndDoesNotRepeatCompletedMessage() {
        val first = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "Hel"))))
        val second = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "lo"))))
        val end = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "block-end").put("index", 0).put("block", JSONObject()
                    .put("type", "text").put("text", "Hello")))))
        val completed = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        assertEquals(DshSdkProtocolEvent.AssistantText("Hel"), first)
        assertEquals(DshSdkProtocolEvent.AssistantText("lo"), second)
        assertEquals(DshSdkProtocolEvent.Ignored, end)
        assertEquals(DshSdkProtocolEvent.Ignored, completed)
    }

    @Test
    fun recognizesRepeatedAssistantMessageBeforeParsingItsLargePayload() {
        parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "Done"))))
        val repeated = """{"jsonrpc":"2.0","method":"session.event","params":{"event":{"type":"assistant/message","data":{"message":{"content":[]}}}}}"""

        assertTrue(parser.consumeDuplicateAssistantMessage(repeated))
        assertFalse(parser.consumeDuplicateAssistantMessage(repeated))
    }

    @Test
    fun normalTurnEndCountsAsCompletedActivity() {
        val completed = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put("reason", JSONObject().put("kind", "completed")),
            ),
        )

        assertEquals(DshSdkProtocolEvent.TurnCompleted, completed)
    }

    @Test
    fun parsesTheRealCompletionSequenceEvenWhenFinishChunkIsPresent() {
        val finishChunk = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 8).put(
                    "chunk",
                    JSONObject().put("type", "finish").put(
                        "reason",
                        JSONObject().put("kind", "stop"),
                    ),
                ),
            ),
        )
        val turnEnd = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put("turn", 1).put("reason", JSONObject().put("kind", "completed")),
            ),
        )
        val idle = parser.parseLine(
            notification(
                "session.status",
                JSONObject().put("sessionId", "session-1").put("status", "idle"),
            ),
        )
        val shutdown = parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}")

        assertEquals(DshSdkProtocolEvent.Ignored, finishChunk)
        assertEquals(DshSdkProtocolEvent.TurnCompleted, turnEnd)
        assertEquals(DshSdkProtocolEvent.Status(false), idle)
        assertEquals(DshSdkProtocolEvent.ShutdownAcknowledged, shutdown)
    }

    @Test
    fun nullJsonRpcErrorUsesUsefulFallback() {
        val failed = parser.parseLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("error", JSONObject().put("message", JSONObject.NULL))
                .toString(),
        )

        assertEquals(
            DshSdkProtocolEvent.Failed("DeepSeek Harness SDK request 2 failed"),
            failed,
        )
    }

    @Test
    fun nullTurnErrorUsesUsefulFallback() {
        val failed = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put(
                    "reason",
                    JSONObject()
                        .put("kind", "error")
                        .put("error", JSONObject().put("message", JSONObject.NULL)),
                ),
            ),
        )

        assertEquals(DshSdkProtocolEvent.Failed("DeepSeek Harness turn failed"), failed)
    }

    private fun sessionEvent(type: String, data: JSONObject): String = notification(
        "session.event",
        JSONObject().put("sessionId", "session-1").put(
            "event",
            JSONObject().put("type", type).put("seq", 1).put("time", 1).put("data", data),
        ),
    )

    private fun notification(method: String, params: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", method)
        .put("params", params)
        .toString()
}

class DshRouteMapperTest {
    @Test
    fun deepseekUsesNativeRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals("deepseek-official", route.name)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertNull(route.custom)
    }

    @Test
    fun zenUsesFixedUrlAndResponsesProtocol() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN))
        assertEquals("opencode-zen", route.name)
        assertEquals("openai-responses", route.custom?.api)
        assertEquals("https://opencode.ai/zen/v1", route.custom?.baseUrl)
    }

    @Test
    fun customHonorsDshApiChoice() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example/v1", model = "m", dshApi = "openai-completions")
        val route = DshRouteMapper.forProfile(profile)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://gw.example/v1", route.custom?.baseUrl)
    }

    @Test
    fun nvidiaNimUsesFixedOpenAiCompletionsRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM))
        assertEquals("nvidia-nim", route.name)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://integrate.api.nvidia.com/v1", route.custom?.baseUrl)
    }

    @Test
    fun openCodeGoUsesFixedAnthropicMessagesRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_GO))
        assertEquals("opencode-go", route.name)
        assertEquals("anthropic-messages", route.custom?.api)
        assertEquals("https://opencode.ai/inference/go/anthropic", route.custom?.baseUrl)
        assertEquals(DshRuntimeBridge.FALLBACK_KEY_ENV, route.keyEnv)
        assertEquals("deepseek-v4.1-flash", route.defaultModel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionIsRejected() {
        DshRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE))
    }
}

class DshSettingsBuilderTest {
    @Test
    fun openCodeGoSettingsCarrySessionHeaderAndNeverTheKey() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_GO)
        val route = withOpenCodeGoSession(
            DshRouteMapper.forProfile(profile),
            profile,
            "6f1c9a0e-2b3d-4e5f-8a9b-0c1d2e3f4a5b",
        )
        val yaml = buildDshSettings(route, profile)

        assertTrue(yaml, yaml.contains("x-opencode-session: 'ses_6f1c9a0e2b3d4e5f8a9b0c1d2e3f4a5b'"))
        assertTrue(yaml, yaml.contains("api: anthropic-messages"))
        assertTrue(yaml, yaml.contains("baseURL: 'https://opencode.ai/inference/go/anthropic'"))
        assertTrue(yaml, yaml.contains("apiKeyEnv: ${DshRuntimeBridge.FALLBACK_KEY_ENV}"))
        assertFalse(yaml, yaml.contains("oc_sk_"))
    }

    @Test
    fun customProfilePointedAtGoStillGetsASessionHeader() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://opencode.ai/inference/go/anthropic",
            model = "deepseek-v4.1-flash",
            dshApi = "anthropic-messages",
        )
        val route = withOpenCodeGoSession(DshRouteMapper.forProfile(profile), profile, "abc-123")

        assertEquals("ses_abc123", route.headers["x-opencode-session"])
    }

    @Test
    fun openCodeGoSessionSurvivesRouteRewritesWhenTheKindIsGo() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_GO)
        val routed = DshRouteMapper.forProfile(profile).copy(
            custom = DshCustomRoute("anthropic-messages", "http://127.0.0.1:12345"),
        )
        val route = withOpenCodeGoSession(routed, profile, "abc-123")

        assertEquals("ses_abc123", route.headers["x-opencode-session"])
        assertEquals("http://127.0.0.1:12345", route.custom?.baseUrl)
    }

    @Test
    fun sessionIdIsPrefixedAndStrippedOfPunctuation() {
        assertEquals("ses_abc123", openCodeGoSessionId("abc-123"))
    }

    @Test
    fun routeWithoutHeadersOmitsTheHeadersBlock() {
        val profile = ProviderProfile(ProviderKind.ANTHROPIC)
        val route = withOpenCodeGoSession(
            DshRouteMapper.forProfile(profile),
            profile,
            "abc-123",
        )
        val yaml = buildDshSettings(route, profile)
        assertTrue(route.headers.isEmpty())
        assertFalse(yaml, yaml.contains("headers:"))
    }
}

class AgentProviderPresetTest {
    @Test
    fun customGatewayUrlSuggestsProtocolWithoutRemovingManualChoice() {
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.example.com/v1/"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://api.example.com/anthropic"))
        assertEquals("openai-responses", inferredDshApiForUrl("https://api.example.com/v1/responses"))
    }

    @Test
    fun deepSeekHarnessValidationUsesSelectedCustomProtocol() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "model",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun openCodeZenPresetIsLocked() {
        val zen = ProviderKind.OPENCODE_ZEN
        assertEquals("https://opencode.ai/zen/v1", zen.defaultBaseUrl)
        assertTrue(zen.fixedBaseUrl)
        assertTrue(zen.fixedProtocol)
        assertEquals("https://opencode.ai/zen/v1", ProviderProfile(zen).resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedUrl() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_ZEN, baseUrl = "https://evil.example/", model = "x")
        assertEquals("https://opencode.ai/zen/v1", profile.resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedProtocol() {
        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM, dshApi = "anthropic-messages")
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals("openai-completions", DshRouteMapper.forProfile(profile).custom?.api)
    }

    @Test
    fun dshHarnessExcludesClaudeSubscription() {
        assertFalse(ProviderKind.CLAUDE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.DEEPSEEK in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.NVIDIA_NIM in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.OPENCODE_GO in DEEPSEEK_HARNESS_PROVIDERS)
        assertEquals(7, DEEPSEEK_HARNESS_PROVIDERS.size)
    }

    @Test
    fun openCodeGoIsOfferedToHarnessAndClaudeCodeOnly() {
        assertTrue(ProviderKind.OPENCODE_GO in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertTrue(ProviderKind.OPENCODE_GO in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.OPENCODE_GO in providersForAgent(AgentKind.ANTIGRAVITY))
    }

    @Test
    fun openCodeGoPresetIsLockedToAnthropicMessages() {
        val go = ProviderKind.OPENCODE_GO
        assertEquals("https://opencode.ai/inference/go/anthropic", go.defaultBaseUrl)
        assertEquals("deepseek-v4.1-flash", go.defaultModel)
        assertTrue(go.fixedBaseUrl)
        assertTrue(go.fixedProtocol)
        assertEquals("anthropic-messages", defaultDshApiForProvider(go))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(ProviderProfile(go), AgentKind.DEEPSEEK_HARNESS))
        assertEquals("https://opencode.ai/inference/go/anthropic", ProviderProfile(go, baseUrl = "https://evil.example/").resolvedBaseUrl)
    }

    @Test
    fun openCodeZenIsNotOfferedForAnyAgent() {
        AgentKind.entries.forEach { agent ->
            assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(agent))
        }
    }

    @Test
    fun agentKindsAreStable() {
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.valueOf("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.valueOf("DEEPSEEK_HARNESS"))
        assertEquals(AgentKind.ANTIGRAVITY, AgentKind.fromStored("antigravity"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.fromStored("DEEPSEEK_HARNESS"))
    }
}
