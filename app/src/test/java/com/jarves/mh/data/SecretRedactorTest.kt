package com.jarves.mh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-021 acceptance criterion: "سجل طرفية منقّى" — the terminal history
 * persisted to disk must not contain credential-shaped values.
 *
 * All test vectors are syntactically valid but FAKE keys constructed for
 * this test — no real credential is present in this repository.
 */
class SecretRedactorTest {

    private fun redacted(text: String): String = SecretRedactor.redact(text)

    @Test
    fun `anthropic key shapes are redacted`() {
        val key = "sk-ant-api03-FAKEFAKEFAKEFAKEFAKEFAKE"
        assertEquals("echo [REDACTED]", redacted("echo $key"))
        assertEquals("[REDACTED]", redacted(key))
    }

    @Test
    fun `anthropic key beats generic sk rule`() {
        // The sk-ant- pass runs first, so the generic sk- pattern must not
        // partially consume the already-redacted marker.
        val out = redacted("ANTHROPIC_API_KEY=sk-ant-api03-FAKEFAKEFAKEFAKEFAKE")
        assertEquals("ANTHROPIC_API_KEY=[REDACTED]", out)
    }

    @Test
    fun `openai and deepseek sk keys are redacted`() {
        assertEquals("export KEY=[REDACTED]", redacted("export KEY=sk-proj-FAKE0000000000000000000000000000"))
        assertEquals("[REDACTED]", redacted("sk-FAKEFAKEFAKEFAKEFAKEFAKE123456"))
    }

    @Test
    fun `github token prefixes are redacted`() {
        assertEquals("gh auth login --with-token <<< [REDACTED]", redacted("gh auth login --with-token <<< ghp_" + "A".repeat(36)))
        assertEquals("[REDACTED]", redacted("github_pat_" + "B".repeat(40)))
        assertEquals("[REDACTED]", redacted("gho_" + "C".repeat(40)))
    }

    @Test
    fun `google and slack keys are redacted`() {
        assertEquals("[REDACTED]", redacted("AIza" + "D".repeat(35)))
        assertEquals("[REDACTED]", redacted("xoxp-FAKE-FAKE-FAKE-FAKE"))
    }

    @Test
    fun `bearer headers keep the scheme word`() {
        assertEquals("Authorization: Bearer [REDACTED]", redacted("Authorization: Bearer abcdef0123456789ABCDEF"))
        assertEquals("bearer [REDACTED]", redacted("bearer abcdef0123456789ABCDEF"))
    }

    @Test
    fun `sovereign proxy url keeps the path prefix`() {
        assertEquals(
            "base_url=http://127.0.0.1:45123/t/[REDACTED]",
            redacted("base_url=http://127.0.0.1:45123/t/0123456789abcdef0123456789abcdef"),
        )
    }

    @Test
    fun `labeled env assignments keep the label`() {
        assertEquals("ANTHROPIC_API_KEY=[REDACTED]", redacted("ANTHROPIC_API_KEY=abc123def456ghi789"))
        assertEquals("deepseek_api_key: [REDACTED]", redacted("deepseek_api_key: abc123def456ghi789"))
        assertEquals("password = [REDACTED]", redacted("password = hunter2hunter2hunter2"))
        assertEquals("token=\"[REDACTED]\"", redacted("token=\"abcdef123456789abcdef\""))
    }

    @Test
    fun `short and ordinary values survive untouched`() {
        assertEquals("token: true", redacted("token: true"))
        assertEquals("api_key=short", redacted("api_key=short"))
        assertEquals("version = 1.2.3", redacted("version = 1.2.3"))
        assertEquals("client_secret = None", redacted("client_secret = None"))
        assertEquals("git commit -m \"fix token parse\"", redacted("git commit -m \"fix token parse\""))
    }

    @Test
    fun `regular commands and urls are not mangled`() {
        val line = "curl -s https://api.github.com/repos/example/project/releases | jq -r .tag_name"
        assertEquals(line, redacted(line))
        val npm = "npm install --save-dev typescript@5.6.2 && npm run build"
        assertEquals(npm, redacted(npm))
    }

    @Test
    fun `short sk- shaped strings are not redacted`() {
        assertEquals("prefix sk-tooshort", redacted("prefix sk-tooshort"))
    }

    @Test
    fun `redaction is idempotent`() {
        val dirty = "ANTHROPIC_API_KEY=sk-ant-api03-FAKEFAKEFAKEFAKEFAKE and Bearer abcdef0123456789ABCD " +
            "http://127.0.0.1:9999/t/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ghp_" + "F".repeat(36)
        val once = redacted(dirty)
        assertEquals(once, redacted(once))
        assertFalse(once.contains("sk-ant"))
        assertFalse(once.contains("Bearer abcdef"))
        assertFalse(once.contains("/t/aaaa"))
    }

    @Test
    fun `multiline terminal dumps are redacted line by line`() {
        val dump = """
            user@localhost:~$ env
            HOME=/root
            ANTHROPIC_API_KEY=sk-ant-api03-FAKEFAKEFAKEFAKEFAKEFAKE
            PATH=/usr/local/bin:/usr/bin:/bin
            GH_TOKEN=ghs_FAKEFAKEFAKEFAKEFAKEFAKEFAKE
        """.trimIndent()
        val out = redacted(dump)
        assertFalse(out.contains("sk-ant"))
        assertFalse(out.contains("ghs_"))
        assertTrue(out.contains("HOME=/root"))
        assertTrue(out.contains("PATH=/usr/local/bin:/usr/bin:/bin"))
    }
}
