package com.jarves.mh.data

/**
 * Redacts credential-shaped strings from text before it is written to disk
 * (ISSUE-021: "تنقية أسرار من terminal history قبل الحفظ").
 *
 * Scope: applied ONLY at persist time to the project terminal history
 * (command + output). The live in-session terminal is intentionally not
 * redacted — the user typed/sees their own session in real time; what must
 * never happen is a secret surviving into a file that is restored days later.
 *
 * Design rules:
 *  - Provider-specific token shapes first, generic labeled secrets last.
 *  - Idempotent: the "[REDACTED]" marker itself never matches any pattern
 *    (its "[" is outside every value character class), so redacting twice
 *    cannot double-mangle text.
 *  - Conservative: short values (< ~12 chars) and ordinary words are left
 *    alone, so regular commands, code snippets and flags like `token: true`
 *    survive untouched.
 *
 * Pure Kotlin, no Android imports — covered by JVM unit tests.
 */
internal object SecretRedactor {

    private const val REDACTED = "[REDACTED]"

    /**
     * Ordered most-specific first; each pass runs on the output of the
     * previous one. Kotlin keeps "$1" literal (a digit cannot start an
     * identifier), so it reaches the regex engine as a group reference.
     */
    private val SUBSTITUTIONS: List<Pair<Regex, String>> = listOf(
        // Anthropic API keys — must run before the generic sk- rule.
        Regex("""sk-ant-[A-Za-z0-9_\-]{16,}""") to REDACTED,
        // GitHub tokens: classic PAT prefixes and fine-grained PATs.
        Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}""") to REDACTED,
        Regex("""\bgithub_pat_[A-Za-z0-9_]{30,}""") to REDACTED,
        // Google API keys.
        Regex("""\bAIza[A-Za-z0-9_\-]{30,}""") to REDACTED,
        // Slack tokens.
        Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}""") to REDACTED,
        // OpenAI / DeepSeek style keys — after sk-ant so that pass wins.
        Regex("""\bsk-[A-Za-z0-9_\-]{20,}""") to REDACTED,
        // SovereignProxy loopback URL path token (128-bit hex after /t/):
        // keep the /t/ prefix so the URL stays recognizable.
        Regex("""(/t/)[0-9a-fA-F]{16,}""") to ("$1" + REDACTED),
        // Authorization headers keep the scheme word: `Bearer [REDACTED]`.
        Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._\-+/=]{16,}""") to ("$1" + REDACTED),
    )

    /** Generic `api_key = ...` / `TOKEN: ...` labeled assignments; keeps the label, drops the value. */
    private val LABELED_SECRET = Regex(
        """(?i)\b((?:anthropic|openai|deepseek|api)?[_\-]?(?:api[_\-]?key|apikey|token|secret|password|passwd|authorization|credential))(\s*[:=]\s*)(["']?)([A-Za-z0-9._\-+/=]{12,})\3""",
    )

    fun redact(text: String): String {
        var out = text
        SUBSTITUTIONS.forEach { (pattern, replacement) ->
            out = pattern.replace(out, replacement)
        }
        out = LABELED_SECRET.replace(out) { match ->
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + REDACTED + match.groupValues[3]
        }
        return out
    }
}
