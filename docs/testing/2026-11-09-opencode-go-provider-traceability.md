# OpenCode Go provider — traceability and test history

**Branch:** `feat/opencode-go-provider`
**Base:** `fork/main` at `76b6725`
**Implementation commit:** `cf7dcc9` (`feat: add OpenCode Go as a first-class provider`)
**Date of this report:** 2026-11-09
**Device:** Xiaomi `2312DRA50G` (`garnet`), Android 15, serial `ba4ba6fb`

---

## 1. Purpose

This document records what the OpenCode Go provider changes, how it was
verified, and what remains dependent on a real Go credential. It is committed
with the branch so the review can be audited later.

Scope is provider-only: OpenCode Go is added as a first-class provider for
DeepSeek Harness and Claude Code. The OpenCode harness and Pi are out of scope.

---

## 2. What changed

| Area | File | Change |
| --- | --- | --- |
| Provider kind | `model/Models.kt` | New `OPENCODE_GO`, fixed base URL and protocol, added to DSH providers and `defaultDshApiForProvider` |
| DSH routing | `runtime/DshRuntimeBridge.kt` | `DshRoute.headers`, pure `buildDshSettings`, `withOpenCodeGoSession`, `opencode-go` route |
| HTTP client | `network/ProviderApiClient.kt` | Go user agent and session header, `oc_sk_` redaction, testable endpoint helpers |
| Claude Code | `runtime/RuntimeBridge.kt` | Fixed base URL comes from `resolvedBaseUrl` |
| ViewModel | `ui/MainViewModel.kt` | Discovery, validation, and ping use `resolvedBaseUrl` |
| UI | `ui/AgentScreen.kt`, `ui/ModelLabels.kt`, `ui/PocketDevApp.kt` | Public discovery, default model list, labels, color, mark |
| Strings | 7 `res/values*/strings.xml` | Provider title and subtitle |
| Tests | `app/src/test/...` | Route, YAML, headers, redaction, Claude env, visibility, labels |
| Docs | `README.md`, `docs/providers/opencode-go.md` | Provider row, setup and contract reference |

`OPENCODE_ZEN` and its Zen request headers are unchanged. Antigravity keeps an
empty provider list.

---

## 3. Contract used

| Check | Result |
| --- | --- |
| `GET https://opencode.ai/inference/go/anthropic/v1/models` | `200`, 31 models on 2026-11-09 |
| `POST .../anthropic/v1/messages` without `x-opencode-session` | `400 MissingSessionID` |
| `POST` with `x-opencode-session`, no key | `401` |
| Default model | `deepseek-v4.1-flash` |

The catalog stayed public during verification. The app therefore treats Go like
OpenRouter for discovery: **Refresh models** works without a key, while
**Test connection** and inference require one.

---

## 4. Static gates

Run from `/tmp/opencode/mh-opencode-go` with JDK 17 and Android SDK 35:

```bash
export JAVA_HOME=/home/hunterhn/.local/jdk-17
export ANDROID_HOME=/home/hunterhn/Android/Sdk
./gradlew :app:testOnlineDebugUnitTest --console=plain
./gradlew :app:lintOnlineDebug --console=plain
./gradlew :app:assembleOnlineDebug --console=plain
```

| Gate | Result |
| --- | --- |
| Unit tests | **243/243**, 0 failures, 0 errors, 0 skipped |
| Lint | **0 errors, 144 warnings, 1 hint** (baseline unchanged) |
| `assembleOnlineDebug` | **BUILD SUCCESSFUL** |
| APK installed on `ba4ba6fb` | **Success** |

New or extended test classes:

```text
DshRouteMapperTest
DshSettingsBuilderTest
ProviderApiClientTest
RuntimeLaunchConfigBuilderTest
AgentProviderPresetTest
ProviderLabelsTest
```

Watched RED→GREEN additions:

- `OPENCODE_GO` unresolved before the enum existed.
- `buildDshSettings` and `withOpenCodeGoSession` unresolved before the pure
  settings builder and header helper existed.
- Go endpoint and header assertions failed before the client helpers existed.

---

## 5. Device verification

Verified on `ba4ba6fb` with the debug APK:

| # | Scenario | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Provider appears for DeepSeek Harness | PASS | **OpenCode Go** row with localized subtitle in Settings → Agent |
| 2 | Base URL is fixed | PASS | UI shows `https://opencode.ai/inference/go/anthropic` and does not allow editing |
| 3 | Default model | PASS | `deepseek-v4.1-flash` selected |
| 4 | Public model discovery without a key | PASS | “Se descubrieron 31 modelos de OpenCode Go.” |
| 5 | No crash and no secret leak | PASS | Logcat: no `oc_sk_`, no `FATAL EXCEPTION` |
| 6 | Previous provider preserved | PASS | Restored OpenRouter after inspection |

The saved provider was returned to OpenRouter after testing so the device was
not left on a provider without credentials.

### Pending with the user's key

These steps require pasting a Go-enabled key in the app. The key was not read
from `pocket_secrets.xml`, logged, or committed.

1. **Test connection** succeeds.
2. DeepSeek Harness answers a one-word prompt.
3. DeepSeek Harness completes a `bash` tool call and writes a workspace file.
4. DeepSeek Harness reasoning does not return `400` for `thinking`.
5. Claude Code answers and completes a tool call with the same provider.
6. `settings.yaml` in the rootfs contains `x-opencode-session` and no key.
7. Antigravity still does not offer OpenCode Go.

If step 1 returns `401`, check that the key belongs to a Go-enabled workspace,
not Zen. If step 2 returns `400 MissingSessionID`, confirm the build contains
`withOpenCodeGoSession` and that `settings.yaml` has the `headers` block.

---

## 6. Decisions taken during review

| Decision | Why | Cost if wrong |
| --- | --- | --- |
| Session id is seeded from the project id, not the per-turn process id | Go asks for a stable per-conversation id; DSH starts a new process every turn | Different chats in one project share cache affinity |
| Claude Code uses its native `x-claude-code-session-id` instead of `ANTHROPIC_CUSTOM_HEADERS` | OpenCode's validated-client table says Go recognizes the native header | Re-adding a custom header is a one-line change |
| The header is added for the Go kind **or** a Custom profile pointed at the Go endpoint | Existing Custom profiles should not silently fail with `400` | None found |
| Key environment keeps the existing `MH_DSH_API_KEY` constant | The environment is built from `DshRuntimeBridge.FALLBACK_KEY_ENV`; using a second name would break the launch | None |

---

## 7. Known limitations

- Claude Code cannot be exercised without a Go credential on this device; the
  env contract is covered by `RuntimeLaunchConfigBuilderTest`.
- `LocalFormatGateway` still reads `profile.baseUrl` for fixed-URL OpenAI
  providers. This is pre-existing and does not affect Go, which uses the
  Anthropic Messages route directly.
- The quota figures in the documentation come from OpenCode's published
  behavior and are not asserted by tests.
