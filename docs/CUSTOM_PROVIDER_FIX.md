# Bug Report & Fix: Custom Provider — "The API key was rejected" / "The API endpoint was not found"

**Repository:** JONIMONI09/Mobile-Harness (fork of techjarves/Mobile-Harness)
**Status:** ✅ Fixed — all unit tests green, APK builds successfully
**Related upstream issue:** [techjarves/Mobile-Harness#9 — "Custom API fails with 'The API endpoint was not found'"](https://github.com/techjarves/Mobile-Harness/issues/9) (also #6)

---

## Where to open an issue

| Where | Link |
|---|---|
| Upstream repo (report the original bug) | https://github.com/techjarves/Mobile-Harness/issues |
| Your fork (track your own fixes) | https://github.com/JONIMONI09/Mobile-Harness/issues |

A ready-to-paste issue body is at the bottom of this document.

---

## 1. Symptoms

| Symptom | When it appears |
|---|---|
| "The API endpoint was not found" | Testing/saving a **Custom** provider in Settings |
| "The provider rejected the saved API key." | Starting a session with a Custom provider (Claude Code runtime) |
| Model list loads, but the connection test fails | Custom endpoints whose base URL already ends in `/v1` |
| Valid key "not accepted" on OpenAI-style endpoints | Providers expecting `/v1/chat/completions` + `Authorization: Bearer` (e.g. `https://api.b.ai/v1`) |

---

## 2. Root causes (in the upstream code)

### Bug 1 — Doubled `/v1` in the endpoint path
`ProviderApiClient.messagesEndpoint()` blindly appended `/v1/messages` to the base URL:

```
Base URL:  https://opencode.ai/zen/v1
Old:       https://opencode.ai/zen/v1/v1/messages   ← /v1 doubled → 404
```

Many gateways (opencode zen, OpenRouter-style proxies, …) already hand out base URLs ending in `/v1`, so **every** validation call returned 404 → "The API endpoint was not found". Model discovery still worked because `modelEndpoints()` tried several paths — exactly the behavior reported in issue #9.

### Bug 2 — Runtime: Claude Code appends `/v1/messages` itself
`RuntimeLaunchConfigBuilder` passed the entered base URL **unchanged** as `ANTHROPIC_BASE_URL`. But Claude Code (the CLI running in the proot container) appends `/v1/messages` on its own:

```
ANTHROPIC_BASE_URL = https://opencode.ai/zen/v1
Claude Code:       https://opencode.ai/zen/v1/v1/messages   ← 404/401 at runtime
→ App shows: "The provider rejected the saved API key."
```

The key was often **correct** — a path problem masquerading as an auth error.

### Bug 3 — No path for OpenAI-format endpoints
Custom providers were forced into the **Anthropic protocol** (`ANTHROPIC_GATEWAY`: `x-api-key` + `/v1/messages`). An OpenAI-compatible endpoint (`/v1/chat/completions` + `Authorization: Bearer`) usually answers unknown paths/formats with **401** → the same misleading "key rejected" message even though the key is valid.

The OpenAI↔Anthropic translation (`LocalFormatGateway`) already existed in the code, but was **dead** — no provider type used it for custom endpoints.

---

## 3. Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/jarves/mh/network/ProviderApiClient.kt` | `messagesEndpoint()` → `messagesEndpointCandidates()`: probe candidate paths instead of one blind path; no more `/v1/v1`; auth error only on a real 401/403; friendly HTTP errors with provider detail |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeBridge.kt` | New `normalizeAnthropicBaseUrl()`: strip a trailing `/v1` before setting `ANTHROPIC_BASE_URL` (Claude Code appends `/v1/messages` itself); keep `/anthropic` suffixes (DeepSeek/Kimi) |
| `app/src/main/java/com/jarves/mh/model/Models.kt` | New provider kind `CUSTOM_OPENAI` ("Custom OpenAI API", `OPENAI_CHAT` protocol) |
| `app/src/main/java/com/jarves/mh/runtime/LocalFormatGateway.kt` | `callProvider()` accepts base URLs with and without `/v1` for `/chat/completions` |
| `app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt` | UI `when` branches for `CUSTOM_OPENAI` (accent color `#10A37F`, "GPT" badge) |
| `app/src/test/java/com/jarves/mh/network/CustomProviderEndpointTest.kt` | **New:** 6 regression tests covering all fixes |
| `docs/CUSTOM_PROVIDER_FIX.md` | **New:** this document |

> `app/build.gradle.kts` was only adapted **locally** (NDK 28.2 / buildTools 36.0.0 present on this machine) and is intentionally **not** part of the pushed fix.

---

## 4. The fixes in detail

### Fix 1 — Endpoint candidates (`ProviderApiClient.kt`)
- Validation probes candidates in order and accepts the first non-404 protocol response (200–299 = success).
- Bases ending in `/v1` get `/messages` appended directly — never `/v1/v1/`.
- `/anthropic` suffixes are treated as real path components, not stripped.
- `modelEndpoints()` for `OPENAI_CHAT`/`OPENAI_RESPONSES` tries `$base/models` **and** `$base/v1/models`.
- `authRejected` is only set when a candidate actually answers 401/403; a 404 alone yields "endpoint not found", not a key accusation.
- `friendlyHttpError()` surfaces provider-supplied details (quota, invalid model, …).

### Fix 2 — Runtime base URL normalization (`RuntimeBridge.kt`)
- Trailing `/v1` is stripped before writing `ANTHROPIC_BASE_URL`, so Claude Code can safely append `/v1/messages`.
- `/anthropic` suffixes (DeepSeek/Kimi) are deliberately kept.
- `OPENAI_CHAT`/`OPENAI_RESPONSES` get `ANTHROPIC_BASE_URL = <local gateway URL>` (loopback translation).

### Fix 3 — New provider type "Custom OpenAI API" (`Models.kt`)
```kotlin
CUSTOM_OPENAI(
    "Custom OpenAI API",
    "OpenAI-compatible /v1/chat/completions endpoint",
    ProviderProtocol.OPENAI_CHAT,
    "", "",
    true,
)
```
- Routes through `OPENAI_CHAT` → activates the existing `LocalFormatGateway` (Anthropic ↔ OpenAI translation on loopback).
- **Auth:** `Authorization: Bearer <key>` — exactly what such endpoints expect (no `x-api-key`).

### Fix 4 — Gateway path tolerance (`LocalFormatGateway.kt`)
- Tries `$base/chat/completions` and, when the base doesn't end in `/v1`, `$base/v1/chat/completions` — again without doubling.

---

## 5. Verification

New regression tests in `app/src/test/java/com/jarves/mh/network/CustomProviderEndpointTest.kt`:

| Test | Covers |
|---|---|
| `messagesEndpointCandidates avoids doubled v1…` | No more `/v1/v1` for `https://opencode.ai/zen/v1` |
| `messagesEndpointCandidates covers bare custom base urls` | `…/zen` → correct `/v1/messages` candidates |
| `custom openai provider routes through local format gateway` | `CUSTOM_OPENAI` → loopback gateway + Bearer key forwarded |
| `model discovery candidates include v1-joined openai path` | `/v1/models` without doubling |
| `launch config strips trailing v1 from custom base url` | Clean `ANTHROPIC_BASE_URL` |
| `launch config keeps anthropic suffix for gateway providers` | DeepSeek/Kimi unchanged |

**Result: 18/18 unit tests green** (`:app:testOnlineDebugUnitTest`), Kotlin compile clean, APK build (58.4 MB) successful.

### How to use the fix in the app
1. Install `mobile-harness-fixed.apk`.
2. **Settings → Provider → "Custom OpenAI API"** (green "GPT" badge) for OpenAI-style endpoints such as `https://api.b.ai/v1`:
   - Base URL: `https://api.b.ai/v1`
   - Model: e.g. `gpt-5.2`
   - Key: your API key (sent as `Authorization: Bearer`)
3. Or **"Custom API"** (Anthropic format): base URL with **or without** `/v1` — both now work.

---

## 6. Ready-to-paste issue body

> **Title:** Custom provider fails: "The API endpoint was not found" / "The API key was rejected" (doubled `/v1` path)
>
> **Describe the bug**
> Custom providers whose base URL ends in `/v1` always fail. The app builds `…/v1/v1/messages` when saving (404 → "The API endpoint was not found"), and at runtime Claude Code does the same with `ANTHROPIC_BASE_URL` (404/401 → "The provider rejected the saved API key") even though the API key is valid. OpenAI-compatible endpoints (`/v1/chat/completions` + Bearer) cannot be used at all because Custom forces the Anthropic protocol.
>
> **To reproduce**
> 1. Settings → Provider → Custom API
> 2. Base URL: `https://opencode.ai/zen/v1` (any key/model)
> 3. Test connection → "The API endpoint was not found"
>
> **Expected behavior**
> Base URLs with or without `/v1` must both work, and OpenAI-format endpoints should be selectable as a provider type.
>
> **Cause**
> `ProviderApiClient` blindly appended `/v1/messages`; `RuntimeLaunchConfigBuilder` passed the `/v1` base URL unchanged to Claude Code, which appends `/v1/messages` itself.
>
> **Fix (this fork)**
> Candidate-path probing in `ProviderApiClient`, `/v1` normalization in `RuntimeBridge`, and a new "Custom OpenAI API" provider kind routed through the existing `LocalFormatGateway`. Details: `docs/CUSTOM_PROVIDER_FIX.md`.
