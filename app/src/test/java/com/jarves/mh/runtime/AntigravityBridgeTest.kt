package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityBridgeTest {
    @Test
    fun `init event exposes conversation id before task completion`() {
        val event = AntigravityEventParser.parse(
            """{"event":"init","conversation_id":"conversation-early","init":{"model":"gemini"}}""",
        )
        assertEquals(AntigravityParsedEvent.Initialized("conversation-early"), event)
    }

    @Test
    fun `parses streamed response deltas`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"agent_response","text_delta":"hello"}}""",
        )
        assertTrue(parsed is AntigravityParsedEvent.Text)
        assertEquals("hello", (parsed as AntigravityParsedEvent.Text).value)
    }

    @Test
    fun `parses successful result and conversation id`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"result","result":{"conversation_id":"conversation-1","status":"SUCCESS","response":"done"}}""",
        )
        assertTrue(parsed is AntigravityParsedEvent.Result)
        val result = parsed as AntigravityParsedEvent.Result
        assertEquals("conversation-1", result.conversationId)
        assertEquals("SUCCESS", result.status)
        assertEquals("done", result.response)
        assertNull(result.error)
    }

    @Test
    fun `shows the command from official tool info`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{"CommandLine":"python3 hello.py"}}}}""",
        )
        assertEquals(
            AntigravityParsedEvent.ToolStarted("Bash", "python3 hello.py"),
            parsed,
        )
    }

    @Test
    fun `shows the target path from official write tool info`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/workspace/app/main.py"}}}}""",
        )
        assertEquals(
            AntigravityParsedEvent.ToolCompleted("Write", "/workspace/app/main.py"),
            parsed,
        )
    }

    @Test
    fun `ignores unknown and malformed events`() {
        assertNull(AntigravityEventParser.parse("not json"))
        assertNull(AntigravityEventParser.parse("""{"event":"future_event","payload":{}}"""))
    }

    @Test
    fun `extracts a wrapped Google PKCE url`() {
        val output = """
            Your browser should open automatically. If not:
            https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_chall
            enge=challenge&code_challenge_method=S256&state=fresh-state
            If you aren't automatically redirected, paste the authorization code below:
        """.trimIndent()
        val url = extractGoogleOAuthUrl(output)
        assertTrue(url!!.startsWith("https://accounts.google.com/"))
        assertTrue("client_id=client.apps.googleusercontent.com" in url)
        assertTrue("code_challenge=challenge" in url)
        assertTrue("state=fresh-state" in url)
    }

    @Test
    fun `oauth url excludes terminal labels appended after state`() {
        val output = """
            https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_chall
            enge=challenge&code_challenge_method=S256&prompt=consent&state=eqPPegReyKP37OxuRsM0EQ
            ──────────────────────────────────────────────────
            Copy and paste the URL or click on the link below:
            → Click here to authenticate
            After authenticating, copy the code displayed in the browser and paste it below:
            authorization code... shift+up/down Navigate
        """.trimIndent()

        assertEquals(
            "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_challenge=challenge&code_challenge_method=S256&prompt=consent&state=eqPPegReyKP37OxuRsM0EQ",
            extractGoogleOAuthUrl(output),
        )
    }

    @Test
    fun `headless command uses exact model configuration without conflicting effort`() {
        val command = antigravityCommand("gemini-model", "high", "conversation-1")
        assertTrue(command.containsAll(listOf(
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--print-timeout", "60m",
            "--dangerously-skip-permissions",
            "--model", "gemini-model",
            "--conversation", "conversation-1",
        )))
        assertTrue("--effort" !in command)
        assertTrue("--new-project" !in command)
    }

    @Test
    fun `new headless conversation creates an official project`() {
        val command = antigravityCommand("", "high", null)
        assertTrue("--new-project" in command)
        assertTrue("--conversation" !in command)
        assertTrue(command.containsAll(listOf("--effort", "high")))
    }

    @Test
    fun `workspace prompt keeps generated files in the mounted project`() {
        val prompt = antigravityWorkspacePrompt("bold-kalam", "Create hello.py")
        assertTrue("/workspace/bold-kalam" in prompt)
        assertTrue("Do not create project output" in prompt)
        assertTrue(prompt.endsWith("Create hello.py"))
    }
}
