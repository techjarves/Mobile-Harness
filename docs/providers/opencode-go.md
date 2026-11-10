# OpenCode Go provider

**Status:** Available for DeepSeek Harness and Claude Code. Not offered for Antigravity.
**Enum:** `ProviderKind.OPENCODE_GO`
**Introduced in:** 1.0.10 (`feat/opencode-go-provider`)

## What it is

OpenCode Go is a subscription product separate from OpenCode Zen. It has its own
endpoint, model catalog, and entitlement, and it permits third-party coding agents.
A Zen key does not grant Go access, and a Go key does not grant Zen access.

Mobile Harness adds Go as its own provider kind instead of reusing
`OPENCODE_ZEN`. Zen remains in the enum so previously saved profiles still load,
but it is hidden from the provider picker.

## Wire contract

| Item | Value |
| --- | --- |
| Base URL | `https://opencode.ai/inference/go/anthropic` |
| Inference | `POST .../anthropic/v1/messages` |
| Catalog | `GET .../anthropic/v1/models` |
| Protocol | Anthropic Messages |
| Auth | `Authorization: Bearer oc_sk_...` or `x-api-key: oc_sk_...` |
| Session header | `x-opencode-session: ses_<alphanumerics>` on every inference call |
| User agent | `MobileHarness/<versionName>` |
| Default model | `deepseek-v4.1-flash` (1M context, 384K output, tools and reasoning) |

The base URL and protocol are fixed in `ProviderKind`. Stored profiles cannot
override them: `ProviderProfile.resolvedBaseUrl` always returns the constant.

The model catalog is public. **Refresh models** works before a key is saved.
Inference and **Test connection** still require a valid Go credential.

## How Mobile Harness wires it

```text
ProviderKind.OPENCODE_GO
├── Settings / discovery        ProviderApiClient
│   ├── GET  /anthropic/v1/models   public, 31 models at time of writing
│   └── POST /anthropic/v1/messages Test connection
├── DeepSeek Harness            DshRuntimeBridge
│   ├── route name              opencode-go
│   ├── api                     anthropic-messages
│   ├── baseURL                 fixed Go base
│   └── settings.yaml headers   x-opencode-session: ses_<project id>
└── Claude Code                 RuntimeLaunchConfigBuilder
    ├── ANTHROPIC_BASE_URL      fixed Go base
    └── session id              Claude Code native x-claude-code-session-id
```

### Session identity

OpenCode Go asks each client to send a **stable** `x-opencode-session` per
conversation for routing and prompt caching. DeepSeek Harness starts a fresh
process per turn, so Mobile Harness seeds that header from the project id rather
than from the per-turn process id. The value is not a secret.

Claude Code already sends `x-claude-code-session-id`, which Go recognizes as a
native session header. Mobile Harness does not override it.

### settings.yaml

For DeepSeek Harness, Mobile Harness writes the header through the
`llm-pi-ai` provider profile:

```yaml
agent-default-model:
  provider: opencode-go
  model: 'deepseek-v4.1-flash'
llm-pi-ai:
  providers:
    opencode-go:
      apiKeyEnv: MH_DSH_API_KEY
      api: anthropic-messages
      baseURL: 'https://opencode.ai/inference/go/anthropic'
      headers:
        x-opencode-session: 'ses_<project id>'
      models:
        - id: 'deepseek-v4.1-flash'
```

The API key is **never** written to `settings.yaml`. It travels only in the
process environment (`MH_DSH_API_KEY`), and error messages redact `oc_sk_...`
values before they reach the UI or logs.

## Setup

1. Open **Settings → Agent → DeepSeek Harness** (or **Claude Code**).
2. Select **OpenCode Go**.
3. Paste a key from a workspace with an active Go subscription.
4. Tap **Refresh models**. The full catalog should appear.
5. Tap **Test connection** to prove inference, not just catalog access.
6. Send a one-word prompt from a project.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `400 MissingSessionID` | The request reached Go without `x-opencode-session`. | Update to a build containing this provider; do not edit `settings.yaml` by hand. |
| `401 Invalid credential` | Key belongs to a Zen-only workspace, is expired, or lacks Go. | Use a Go-enabled key and confirm the plan is active. |
| `401 Go requires a credential` | No key reached the gateway. | Save the key and use **Test connection**. |
| Region or privacy error for DeepSeek models | DeepSeek V4.1 Flash may require a region opt-in. | In the OpenCode Console set **Settings → Privacy → Regions → Global**. |
| Catalog loads but prompts fail | The key is valid for discovery but not for inference. | Recheck the workspace entitlement; discovery is public. |

## Quota and privacy

Go bills against a monthly per-model dollar budget. Usage windows are reported
by the gateway: 5 hours = 20%, weekly = 50%, monthly = 100%. Responses can carry
`opencode.allowance` and `usage.cost`.

Privacy is per model. Some models retain prompts for a limited period or are
offered at a discount in exchange for training data. Check the Console before
using a model with sensitive code.

## Tests

| Test | Covers |
| --- | --- |
| `DshRouteMapperTest.openCodeGoUsesFixedAnthropicMessagesRoute` | Route name, protocol, base URL, key env, default model |
| `DshSettingsBuilderTest` | YAML header block, no key, Custom profile pointed at Go |
| `ProviderApiClientTest` | Endpoints, Go headers, no headers for other endpoints, `oc_sk_` redaction |
| `RuntimeLaunchConfigBuilderTest.openCodeGoUsesFixedAnthropicBaseWithoutLocalGateway` | Claude Code env uses the fixed base and model |
| `AgentProviderPresetTest.openCodeGoIsOfferedToHarnessAndClaudeCodeOnly` | Provider visibility |
| `ProviderLabelsTest` | Title and subtitle resources exist |
