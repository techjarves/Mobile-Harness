package com.jarves.mh.runtime

import com.jarves.mh.crypto.Minisign
import org.json.JSONObject
import java.util.Locale

/**
 * One agent entry from the project-signed update feed (roadmap 3k):
 * the version, download URL, and SHA-512 digest — all three vouched for
 * by an offline Ed25519 key pinned in [UpdateSigningKeys].
 */
internal data class VerifiedAgentRelease(
    val agent: String,
    val version: String,
    val url: String,
    val sha512: String,
)

/** The verified contents of one signed manifest (any subset of agents). */
internal data class SignedUpdateFeed(
    val claudeCode: VerifiedAgentRelease?,
    val deepSeekHarness: VerifiedAgentRelease?,
    val antigravity: VerifiedAgentRelease?,
)

/**
 * Parser + policy gate for the project-controlled agent update manifest
 * (ISSUE-002 long-term fix). The manifest is plain JSON served from the
 * repo's `agent-updates` branch; a detached minisign signature covers the
 * exact served bytes, so no JSON canonicalization is involved anywhere.
 *
 * Fail-closed by construction: signature failure, unknown key id, policy
 * violation (schema, version charset, non-https URL, malformed digest),
 * or missing trust anchors all collapse to a single null. The caller then
 * falls back to the per-release pinned allowlist (VerifiedAgentReleases)
 * — never to unsigned data.
 */
internal object SignedUpdateManifest {

    const val AGENT_CLAUDE_CODE = "claude-code"
    const val AGENT_DEEPSEEK_HARNESS = "deepseek-harness"
    const val AGENT_ANTIGRAVITY = "antigravity"

    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_SIGNATURE_BYTES = 8 * 1024

    /** Same charset the dsh updater already enforces for npm versions. */
    private val VERSION = Regex("[0-9A-Za-z.+-]{1,64}")
    private val SHA512 = Regex("[0-9a-fA-F]{128}")

    /**
     * Verifies the minisign signature over [manifestJson] and parses the
     * feed. [trustedKeys] defaults to the production pins; tests inject
     * their own. Any failure -> null.
     */
    fun parse(
        manifestJson: ByteArray,
        minisignSignatureFile: ByteArray,
        trustedKeys: List<Minisign.PublicKey> = UpdateSigningKeys.publicKeys,
    ): SignedUpdateFeed? {
        if (manifestJson.size > MAX_MANIFEST_BYTES) return null
        if (minisignSignatureFile.size > MAX_SIGNATURE_BYTES) return null
        if (trustedKeys.isEmpty()) return null
        val signature = Minisign.parseSignatureFile(minisignSignatureFile) ?: return null
        val key = trustedKeys.firstOrNull { it.sameKeyId(signature.keyId) } ?: return null
        if (!Minisign.verify(key, manifestJson, signature)) return null
        return parseVerifiedBody(manifestJson)
    }

    private fun parseVerifiedBody(bytes: ByteArray): SignedUpdateFeed? = runCatching {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        if (root.optInt("schema") != 1) return@runCatching null
        val agents = root.optJSONObject("agents") ?: return@runCatching null
        val knownAgents = listOf(AGENT_CLAUDE_CODE, AGENT_DEEPSEEK_HARNESS, AGENT_ANTIGRAVITY)
        val parsed = knownAgents.associateWith { parseEntry(agents, it) }
        // A known-agent entry that exists but violates policy invalidates the
        // entire feed (fail-closed): the signing runbook sanity-checks these
        // fields before signing, so a signed manifest carrying an invalid
        // entry means the signing side was bypassed — trust nothing in it.
        if (knownAgents.any { agents.has(it) && parsed[it] == null }) return@runCatching null
        SignedUpdateFeed(
            claudeCode = parsed[AGENT_CLAUDE_CODE],
            deepSeekHarness = parsed[AGENT_DEEPSEEK_HARNESS],
            antigravity = parsed[AGENT_ANTIGRAVITY],
        )
    }.getOrNull()

    private fun parseEntry(agents: JSONObject, agent: String): VerifiedAgentRelease? {
        val entry = agents.optJSONObject(agent) ?: return null
        val version = entry.optString("version")
        val url = entry.optString("url")
        val sha512 = entry.optString("sha512").lowercase(Locale.ROOT)
        if (!VERSION.matches(version)) return null
        if (!url.startsWith("https://") || url.length > 2048) return null
        if (!SHA512.matches(sha512)) return null
        return VerifiedAgentRelease(agent, version, url, sha512)
    }
}
