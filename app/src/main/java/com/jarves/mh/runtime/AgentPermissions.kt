package com.jarves.mh.runtime

import com.jarves.mh.model.AgentAutonomyMode
import com.jarves.mh.model.RiskLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure decision core of the agent permission system (ISSUE-001).
 *
 * Everything here is plain Kotlin + org.json so the security-critical
 * behavior — which settings land in the guest, how risky a tool call is, and
 * what happens when a permission request cannot be read — is unit-testable on
 * the JVM without Android. The wiring (file watching, UI events, timeouts)
 * lives in the runtime bridges; the *policy* lives here.
 *
 * Fail-closed rules enforced by this module:
 *  - The default mode ([AgentAutonomyMode.APPROVE_RISKY]) never generates
 *    settings or hook scripts containing an always-`"allow"` decision.
 *  - [parsePermissionRequest] returns null for anything it cannot fully
 *    understand; callers must treat null as deny.
 */
object AgentPermissions {

    // ---------------------------------------------------------------------
    // Claude Code guest settings
    // ---------------------------------------------------------------------

    /**
     * The `settings.json` Claude Code reads inside the guest, generated per
     * autonomy mode.
     *
     *  - FULLY_AUTONOMOUS keeps the historical layout: an allow-list for the
     *    workspace tools plus an always-allow PermissionRequest hook.
     *  - APPROVE_RISKY (the default) has no allow-list, denies the network
     *    tools outright, and routes every permission event through the
     *    interactive bridge hook.
     *  - APPROVE_ALL has no allow-list and no deny-list; everything routes
     *    through the interactive bridge hook.
     */
    fun claudeSettingsJson(mode: AgentAutonomyMode): String {
        val permissions = JSONObject()
        when (mode) {
            AgentAutonomyMode.FULLY_AUTONOMOUS -> permissions
                .put("allow", claudeWorkspaceToolRules())
                .put("defaultMode", "acceptEdits")

            AgentAutonomyMode.APPROVE_RISKY -> permissions
                .put("deny", JSONArray().put("WebFetch").put("WebSearch"))
                .put("defaultMode", "default")

            AgentAutonomyMode.APPROVE_ALL -> permissions
                .put("defaultMode", "default")
        }
        val hookCommand = JSONObject()
            .put("type", "command")
            .put("command", HOOK_GUEST_PATH)
        val matcher = if (mode == AgentAutonomyMode.FULLY_AUTONOMOUS) {
            "Bash|Edit|Write|NotebookEdit"
        } else {
            ".*"
        }
        return JSONObject()
            .put("disableAllHooks", false)
            .put("permissions", permissions)
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    JSONArray().put(
                        JSONObject()
                            .put("matcher", matcher)
                            .put("hooks", JSONArray().put(hookCommand)),
                    ),
                ),
            )
            .toString()
    }

    private fun claudeWorkspaceToolRules() = JSONArray().apply {
        put("Bash")
        put("Edit")
        put("Write")
        put("NotebookEdit")
        put("Read")
        put("Glob")
        put("Grep")
    }

    // ---------------------------------------------------------------------
    // Guest-side permission hook
    // ---------------------------------------------------------------------

    /**
     * `/opt/pocket/permission-hook.sh` inside the guest.
     *
     * FULLY_AUTONOMOUS answers every request with a fixed allow decision
     * (historical behavior, explicit opt-in only).
     *
     * Every other mode runs the interactive bridge: the hook drops the
     * PermissionRequest payload into `/pocket-bridge` (bind-mounted to the
     * app's private storage) and waits for the Android side to answer. The
     * script is fail-closed — a missing bridge, a write error, or a timeout
     * denies the request. The decision string is passed through `%s` so the
     * literal `"behavior":"allow"` never appears in the default-mode script.
     */
    fun permissionHookScript(mode: AgentAutonomyMode): String = when (mode) {
        AgentAutonomyMode.FULLY_AUTONOMOUS -> ALWAYS_ALLOW_HOOK
        else -> INTERACTIVE_BRIDGE_HOOK
    }

    private const val ALWAYS_ALLOW_HOOK = """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
"""

    private const val INTERACTIVE_BRIDGE_HOOK = """#!/bin/sh
# Mobile Harness interactive permission bridge (ISSUE-001).
# FAIL-CLOSED: a missing bridge, a write error, or a timeout denies the request.
BRIDGE=/pocket-bridge
TIMEOUT=70

deny() {
    printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny"}}}'
    exit 0
}

payload=${'$'}(cat 2>/dev/null)
[ -n "${'$'}payload" ] || deny
[ -d "${'$'}BRIDGE" ] || deny

id="perm-${'$'}${'$'}-${'$'}(date +%s)"
request_file="${'$'}BRIDGE/${'$'}id.request"
response_file="${'$'}BRIDGE/${'$'}id.response"

printf '%s' "${'$'}payload" > "${'$'}request_file" 2>/dev/null || deny

elapsed=0
while [ "${'$'}elapsed" -lt "${'$'}TIMEOUT" ]; do
    if [ -f "${'$'}response_file" ]; then
        decision=${'$'}(cat "${'$'}response_file" 2>/dev/null)
        rm -f "${'$'}request_file" "${'$'}response_file" 2>/dev/null
        [ "${'$'}decision" = "allow" ] || deny
        printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "${'$'}decision"
        exit 0
    fi
    sleep 1
    elapsed=${'$'}((elapsed + 1))
done

rm -f "${'$'}request_file" 2>/dev/null
deny
"""

    /** Guest path of the hook script referenced by the generated settings. */
    const val HOOK_GUEST_PATH = "/opt/pocket/permission-hook.sh"

    // ---------------------------------------------------------------------
    // Other agents
    // ---------------------------------------------------------------------

    /**
     * `DSH_PERMISSION_MODE` for the DeepSeek Harness CLI. Only the explicit
     * autonomous mode keeps `danger-full-access`; everything else runs dsh in
     * its default (asking) mode, which denies tool calls that have no approval
     * channel instead of approving them.
     */
    fun dshPermissionMode(mode: AgentAutonomyMode): String =
        if (mode == AgentAutonomyMode.FULLY_AUTONOMOUS) "danger-full-access" else "default"

    /**
     * Whether the Antigravity CLI is launched with
     * `--dangerously-skip-permissions`. Same rule: explicit autonomous opt-in
     * only. In careful modes agy's own permission gating denies what it cannot
     * ask about headlessly.
     */
    fun antigravitySkipsPermissions(mode: AgentAutonomyMode): Boolean =
        mode == AgentAutonomyMode.FULLY_AUTONOMOUS

    // ---------------------------------------------------------------------
    // Risk classification and auto-approval policy
    // ---------------------------------------------------------------------

    /**
     * Heuristic risk classification for a tool call (moved verbatim from
     * ClaudeRuntimeBridge so it can be tested and shared).
     */
    fun classifyRisk(tool: String, command: String?): RiskLevel {
        val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
        return when {
            listOf("rm -rf", "git push", "git reset", "sudo", "curl ").any(preview::contains) -> RiskLevel.HIGH
            tool in listOf("Write", "Edit", "NotebookEdit", "Bash") -> RiskLevel.REVIEW
            else -> RiskLevel.SAFE
        }
    }

    /**
     * Whether a request of the given risk may run without asking the user.
     * APPROVE_RISKY auto-approves SAFE/REVIEW and asks for HIGH;
     * APPROVE_ALL asks for everything; FULLY_AUTONOMOUS approves everything.
     */
    fun shouldAutoApprove(mode: AgentAutonomyMode, risk: RiskLevel): Boolean = when (mode) {
        AgentAutonomyMode.FULLY_AUTONOMOUS -> true
        AgentAutonomyMode.APPROVE_RISKY -> risk != RiskLevel.HIGH
        AgentAutonomyMode.APPROVE_ALL -> false
    }

    // ---------------------------------------------------------------------
    // Permission request parsing
    // ---------------------------------------------------------------------

    /** Fully-parsed PermissionRequest payload; anything less is null (= deny). */
    data class ParsedPermissionRequest(
        val toolName: String,
        val command: String?,
        val paths: List<String>,
        val explanation: String,
    )

    /**
     * Parses a PermissionRequest hook payload. Returns null for anything that
     * is not a complete, understandable request — corrupt JSON, missing tool
     * name, blank payloads. Callers must deny on null (ISSUE-001 fail-closed).
     */
    fun parsePermissionRequest(raw: String?): ParsedPermissionRequest? {
        if (raw.isNullOrBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val toolName = json.optString("tool_name").takeIf(String::isNotBlank) ?: return null
        val input = json.optJSONObject("tool_input") ?: JSONObject()
        val command = input.optString("command").takeIf(String::isNotBlank)
        val paths = listOf("file_path", "path", "notebook_path")
            .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
        val explanation = input.optString("description")
            .ifBlank { command.orEmpty() }
            .ifBlank { "$toolName running in project" }
        return ParsedPermissionRequest(toolName, command, paths, explanation)
    }
}
