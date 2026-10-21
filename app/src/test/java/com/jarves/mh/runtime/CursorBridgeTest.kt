package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DEEPSEEK_HARNESS_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorBridgeTest {
    @Test
    fun commandContainsHeadlessFlagsAndWorkspace() {
        val command = cursorCommand("demo", model = "", sessionId = null, prompt = "hello")
        listOf(
            "-p",
            "--force",
            "--trust",
            "--sandbox",
            "disabled",
            "--disable-auto-update",
            "--output-format",
            "stream-json",
            "--stream-partial-output",
            "--workspace",
            "/workspace/demo",
        ).forEach { token -> assertTrue(command.contains(token)) }
        assertEquals("hello", command.last())
    }

    @Test
    fun commandOmitsModelAndResumeWhenBlank() {
        val command = cursorCommand("demo", model = "  ", sessionId = "", prompt = "hello")
        assertFalse(command.contains("--model"))
        assertFalse(command.contains("--resume"))
    }

    @Test
    fun commandIncludesModelAndResumeWithoutAntigravityFlags() {
        val command = cursorCommand("demo", model = "composer-2", sessionId = "sess-1", prompt = "again")
        assertTrue(command.contains("--model"))
        assertTrue(command.contains("composer-2"))
        assertTrue(command.contains("--resume"))
        assertTrue(command.contains("sess-1"))
        assertFalse(command.contains("--effort"))
        assertFalse(command.contains("--dangerously-skip-permissions"))
        assertEquals("again", command.last())
    }

    @Test
    fun parserKeepsInitSessionId() {
        val event = CursorEventParser.parse(
            """{"type":"system","subtype":"init","session_id":"sess-early","model":"default"}""",
        )
        assertEquals(CursorParsedEvent.Session("sess-early"), event)
    }

    @Test
    fun parserKeepsPartialAssistantDeltaAndDropsDuplicateFlush() {
        val partial = CursorEventParser.parse(
            """{"type":"assistant","timestamp_ms":10,"message":{"content":[{"type":"text","text":"hello"}]}}""",
        )
        assertEquals(CursorParsedEvent.AssistantDelta("hello"), partial)
        val flush = CursorEventParser.parse(
            """{"type":"assistant","timestamp_ms":10,"model_call_id":"call-1","message":{"content":[{"type":"text","text":"hello"}]}}""",
        )
        assertNull(flush)
        val end = CursorEventParser.parse(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}""",
        )
        assertNull(end)
    }

    @Test
    fun parserMapsReadToolCall() {
        val event = CursorEventParser.parse(
            """{"type":"tool_call","subtype":"started","tool_call":{"readToolCall":{"args":{"path":"/workspace/demo/hello.txt"}}}}""",
        )
        assertEquals(CursorParsedEvent.ToolStarted("Read", "/workspace/demo/hello.txt"), event)
    }

    @Test
    fun parserMapsWriteToolCallCompleted() {
        val event = CursorEventParser.parse(
            """{"type":"tool_call","subtype":"completed","tool_call":{"writeToolCall":{"args":{"path":"/workspace/demo/hello.txt"},"result":{"success":"wrote"}}}}""",
        )
        assertEquals(CursorParsedEvent.ToolCompleted("Write", "/workspace/demo/hello.txt"), event)
    }

    @Test
    fun parserMapsSuccessResult() {
        val event = CursorEventParser.parse(
            """{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","result":"done"}""",
        )
        assertEquals(CursorParsedEvent.Success("sess-1", "done"), event)
    }

    @Test
    fun parserIgnoresMalformedJsonAndUnknownType() {
        assertNull(CursorEventParser.parse("not-json"))
        assertNull(CursorEventParser.parse("""{"type":"future-event","session_id":"x"}"""))
    }

    @Test
    fun redactionReplacesApiKeyValue() {
        val redacted = cursorRedact("api_key=supersecret token=abc")
        assertFalse(redacted.contains("supersecret"))
        assertTrue(redacted.contains("api_key="))
        assertTrue(redacted.contains("••••"))
    }

    @Test
    fun workspacePreambleStaysInsideTheProject() {
        val prompt = cursorWorkspacePrompt("demo", "build it", listOf(ChatMessage(fromUser = true, text = "build it")))
        assertTrue(prompt.contains("/workspace/demo"))
        assertTrue(prompt.contains("assembleDebug"))
        assertTrue(prompt.contains("--no-daemon"))
        assertTrue(prompt.contains("nohup"))
        assertFalse(prompt.contains("antigravity", ignoreCase = true))
        assertFalse(prompt.contains("manage_task"))
    }

    @Test
    fun cursorHasNoProviderPicker() {
        assertTrue(providersForAgent(AgentKind.CURSOR_CLI).isEmpty())
    }

    @Test
    fun existingProviderSetsStayTheSame() {
        assertTrue(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.CLAUDE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.DEEPSEEK in DEEPSEEK_HARNESS_PROVIDERS)
        assertEquals(7, DEEPSEEK_HARNESS_PROVIDERS.size)
        assertTrue(providersForAgent(AgentKind.ANTIGRAVITY).isEmpty())
    }

    @Test
    fun friendlyErrorKeepsOrdinaryFileNotFound() {
        val message = cursorFriendlyError("src/Main.kt:12: error: file not found")
        assertEquals("src/Main.kt:12: error: file not found", message)
        assertEquals(CURSOR_INCOMPATIBLE, cursorFriendlyError("pty.node: GLIBC_2.34 not found"))
        assertEquals(CURSOR_INCOMPATIBLE, cursorFriendlyError("cursor-agent: not found"))
    }

    @Test
    fun friendlyErrorDoesNotTreatEvery401OrLoginAsAuth() {
        assertEquals(CURSOR_NEEDS_KEY, cursorFriendlyError("request failed with HTTP 401"))
        assertEquals("build id 14019", cursorFriendlyError("build id 14019"))
        assertEquals(CURSOR_NEEDS_KEY, cursorFriendlyError("Please login to continue"))
        assertEquals("catalog refreshed", cursorFriendlyError("catalog refreshed"))
    }

    @Test
    fun friendlyErrorRedactsSecretsAndStaysStable() {
        val redacted = cursorFriendlyError("api_key=supersecret")
        assertFalse(redacted.contains("supersecret"))
        assertEquals(CURSOR_TIMEOUT, cursorFriendlyError(CURSOR_TIMEOUT))
        assertEquals(CURSOR_NETWORK, cursorFriendlyError("connect i/o timeout"))
        val scrubbed = scrubCursorSecret("sent key crsr_live_secret_value", "crsr_live_secret_value")
        assertFalse(scrubbed.contains("crsr_live_secret_value"))
    }

    @Test
    fun modelListReadsJsonObject() {
        val models = parseCursorModelList("""{"models":[{"id":"composer-2"},"auto"]}""")
        assertEquals(listOf("composer-2", "auto"), models)
    }

    @Test
    fun modelListReadsCursorTextCatalog() {
        val output = """
            Available models

            ${'\u001B'}[36mcomposer-2.5${'\u001B'}[39m - Composer 2.5 (current, default)
            gpt-5.5 - GPT-5.5

            Tip: use --model <id> to switch.
        """.trimIndent()
        val catalog = parseCursorModelCatalog(output)
        assertEquals(listOf("composer-2.5", "gpt-5.5"), catalog.ids)
        assertEquals("composer-2.5", catalog.defaultId)
    }

    @Test
    fun buildInstructionsAreNotReportedAsATimeout() {
        val message = cursorFriendlyError("gradle failed: timeout 15m assembleDebug exited 1")
        assertFalse(message == CURSOR_TIMEOUT)
        assertTrue(message.contains("exited 1"))
    }

    @Test
    fun storedAgentKindRoundTrips() {
        assertEquals(AgentKind.CURSOR_CLI, AgentKind.fromStored("cursor-cli"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored(null))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("nope"))
    }
}
