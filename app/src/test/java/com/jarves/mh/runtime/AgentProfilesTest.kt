package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-039: executable and translation-model wiring lives in per-agent
 * profiles. Adding an agent must not touch the shared bridge contract, and
 * the Claude translation model id may only appear in the Claude profile.
 */
class AgentProfilesTest {

    @Test
    fun `every built-in agent has a profile with a distinct executable`() {
        val executables = AgentKind.entries.map { kind ->
            val profile = AgentProfiles.forKind(kind)
            assertEquals(kind, profile.kind)
            assertTrue(profile.executable.startsWith("/"))
            profile.executable
        }
        assertEquals(executables.size, executables.distinct().size)
    }

    @Test
    fun `claude code carries the translation model id and only claude does`() {
        assertNotNull(AgentProfiles.CLAUDE_CODE.translationModel)
        assertEquals("claude-sonnet-4-6", AgentProfiles.CLAUDE_CODE.translationModel)
        AgentKind.entries
            .filterNot { it == AgentKind.CLAUDE_CODE }
            .forEach { kind -> assertEquals(null, AgentProfiles.forKind(kind).translationModel) }
    }

    @Test
    fun `the builder launches the configured agent executable`() {
        val config = RuntimeLaunchConfigBuilder.build(
            profile = ProviderProfile(ProviderKind.ANTHROPIC, hasSecret = true),
            agent = AgentProfiles.forKind(AgentKind.CLAUDE_CODE),
            authToken = "k",
        )
        assertEquals("/usr/local/bin/claude", config.executable)

        val dshConfig = RuntimeLaunchConfigBuilder.build(
            profile = ProviderProfile(ProviderKind.ANTHROPIC, hasSecret = true),
            agent = AgentProfiles.forKind(AgentKind.DEEPSEEK_HARNESS),
            authToken = "k",
        )
        assertEquals("/usr/local/bin/dsh", dshConfig.executable)
        assertNotEquals(config.executable, dshConfig.executable)
    }

    @Test
    fun `an agent without a translation model cannot run behind the format gateway`() {
        val error = kotlin.runCatching {
            RuntimeLaunchConfigBuilder.build(
                profile = ProviderProfile(ProviderKind.OPENCODE_ZEN, hasSecret = true),
                agent = AgentProfiles.forKind(AgentKind.DEEPSEEK_HARNESS),
                authToken = "k",
                localGatewayUrl = "http://127.0.0.1:9/t/abc",
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
