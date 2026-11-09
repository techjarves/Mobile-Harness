# PR 4 — Runtime recovery: traceability and test history

**PR:** https://github.com/hunterhn/Mobile-Harness/pull/4
**Issue:** https://github.com/techjarves/Mobile-Harness/issues/89
**Branch:** `feat/runtime-recovery`
**Base:** `fix/pty-output-drain`
**Split tip before this document:** `2b4f964` (`fix: polish reconnect banner copy and surface`)
**Date of this report:** 2026-11-09
**Owner:** Dennis

---

## 1. Purpose

This document records what PR 4 changes, which tests were run, what was verified on device, and where the evidence lives. It is intentionally committed on the PR branch so the history stays with the code and the review can be audited later.

This is a **docs-only commit** on top of the nine code commits. It does not change runtime behavior or test results.

---

## 2. What this PR does

PR 4 makes runtime recovery a mandatory, fail-closed contract:

- `RuntimeBridge.recoveryProfile` is required for every runtime.
- `RuntimeRecoveryPolicy` decides resume / retry / manual based on capabilities and side-effect facts.
- DSH continues the **same session in place** after a `TRANSPORT` failure when safe.
- Turns that complete without a user-facing answer get a **nudge** and, if needed, a localized fallback.
- The UI gets a sticky reconnect banner, a manual retry card, “Ver cambios” and a side-effect warning dialog.
- Claude Code and Antigravity declare their recovery capabilities.
- The recovery path is fail-closed: when the runtime cannot prove it is safe, the app asks the user instead of replaying work.

The user-facing goal is issue #89: DeepSeek Harness stopped after roughly five minutes with exit code 0 and required a manual “continúa”. The PR targets that failure family by retrying transport failures, continuing the same session, and giving a visible recovery path when it cannot continue.

---

## 3. Commit history

Code commits in PR 4 (`fix/pty-output-drain..feat/runtime-recovery` before this document):

| Commit | Date | Subject |
|---|---|---|
| `a9c3c32` | 2026-11-08 | fix: auto-retry DSH transport failures |
| `63faec3` | 2026-11-08 | fix: nudge DSH turns without a user-facing answer |
| `e0ab0ea` | 2026-11-08 | fix: harden DSH transport retry shutdown |
| `6fa424e` | 2026-11-08 | feat: add mandatory runtime recovery contract |
| `79cd565` | 2026-11-08 | feat: continue DSH sessions in place after transport failures |
| `a2510a0` | 2026-11-08 | feat: route transport recovery through the runtime policy |
| `bd634b8` | 2026-11-08 | feat: show reconnect banner and manual retry |
| `898b3fa` | 2026-11-08 | feat: declare claude and antigravity recovery capabilities |
| `2b4f964` | 2026-11-08 | fix: polish reconnect banner copy and surface |

History context: PR 4 was split from the original `feat/transport-turn-auto-retry` branch (41 commits) into four stacked PRs. The split plan is `docs/handoff/2026-11-08-pr-split-plan.md`. The `feat/runtime-recovery` tree is identical to `65a4dfb` except `README.md`.

Scope: **27 files, +2978 / −46** before this document.

---

## 4. Test environment

| Item | Value |
|---|---|
| Device | Xiaomi `2312DRA50G` (`garnet`), Android 15 (API 35), 1220×2712, 480 dpi |
| Serial | `ba4ba6fb` |
| Package | `com.jarves.mh` 1.0.10 (versionCode 11), online debug |
| Build | `:app:assembleOnlineDebug` |
| Provider | `mh-openrouter`, `deepseek/deepseek-v4.1-flash-1017` |
| DSH | `0.1.2-rc.1` |
| Test project | Quiet Bose / Curious Hopper (quick projects; the user’s project was not modified) |

Reproduction commands:

```bash
export JAVA_HOME=/home/hunterhn/.local/jdk-17
./gradlew :app:testOnlineDebugUnitTest --console=plain
./gradlew :app:lintOnlineDebug --rerun-tasks --console=plain
./gradlew :app:assembleOnlineDebug --console=plain

ADB=/home/hunterhn/Android/Sdk/platform-tools/adb
$ADB -s ba4ba6fb install -r app/build/outputs/apk/online/debug/app-online-debug.apk
$ADB -s ba4ba6fb shell am start -W -n com.jarves.mh/.MainActivity
```

---

## 5. Static gates

Verified on 2026-11-09 on `feat/runtime-recovery`:

| Gate | Result |
|---|---|
| Unit tests | **229/229** (34 classes), 0 failures, 0 errors, 0 skipped |
| Lint | **0 errors**, 144 warnings, 1 hint |
| `assembleOnlineDebug` | **BUILD SUCCESSFUL** |

Relevant test classes:

```text
DshTransportRecoveryTest
FinalAnswerStateMachineTest
RuntimeRecoveryContractTest
RuntimeRecoveryPolicyTest
RecoveryStateTest
RecoveryUiTextTest
TransportRetryTest
DshBridgeTest
ClaudeRecoveryTest
AntigravityRecoveryTest
```

---

## 6. Device recovery scenarios — 7/7 PASS

Evidence date: 2026-11-07. Full details: `.superpowers/device-tests/2026-11-07-recovery/evidence.md`.

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Reconnect banner without tools | PASS | `banner-1-reconnecting.png`, `banner-2-final.png` |
| 2 | Exhausted retries + manual retry | PASS | `exhausted-1-card.png`, `exhausted-2-retried.png`, `exhausted-3-retried-final.png` |
| 3 | Mutating tool not duplicated | PASS | `mutating-1-banner.png`, `mutating-2-final.png`, `retry-ui3.txt` |
| 4 | Process death after a tool | PASS | `kill-1-card.png`, `kill-2-dialog.png`, `kill-3-changes.png`, `kill-4-back-to-chat.png` |
| 5 | Stop during the retry gap | PASS | `stop-1-stopped.png`, `stop-2-followup.png` |
| 6 | Normal turn unaffected | PASS | `normal-1-final.png` |
| 7 | Non-transport failure copy | PASS (via #4) | `kill-1-card.png`, `RecoveryUiTextTest` |

Key assertions:

- A `TRANSPORT` failure shows the amber banner and accepts `{"id":5}` on the same session.
- A mutating tool writes exactly one `attempt-…` and one `done-…` line; the continuation does not replay it.
- Killing the DSH process after a tool does **not** auto-restart; the card says “No se pudo completar” and the warning dialog appears.
- “Ver cambios” navigates to Changes; the card persists when returning to Chat.
- Stop during the gap sends no `{"id":5}` and no second `Route:`; the next prompt works normally.
- A normal turn shows no banner and no card.

---

## 7. Smoke tests — 3/3 PASS

Evidence date: 2026-11-07. Full details: `.superpowers/device-tests/2026-11-07-smoke/evidence.md`.

| Test | Result | Evidence |
|---|---|---|
| A — in-place continuation without tools | PASS | `A-final.png`, `runtime.log`, `logcat-A.txt` |
| B — mutating tool not duplicated | PASS | `B-final.png`, `retry-smoke.txt`, `runtime-B.log` |
| C — stop during recovery | PASS | `C-after-stop.png`, `C-followup.png`, `runtime-C-*.log` |

Smoke A confirms one `Route:` and turn 2 `completed` after the network returns inside the 5 s window. Smoke B confirms one `attempt-…` and one `done-…`. Smoke C confirms no `{"id":5}` after stop and a normal following prompt.

---

## 8. Fix A+B evidence (pre-split)

The earlier Fix A+B validation on `36cb5f4` is preserved in:

```text
.superpowers/device-tests/2026-11-06-fix-ab-evidence.md
.superpowers/device-tests/2026-11-06-redmi/fix-ab/
```

It covers the 168/168 unit baseline, the first Wi-Fi drop/recovery, the missing-shutdown bug fixed by `346dc6f`, and the deterministic no-text nudge tests.

---

## 9. Evidence hosting

The full visual evidence for PR 4 is hosted on the public fork branch:

```text
https://github.com/hunterhn/Mobile-Harness/tree/pr-evidence
https://raw.githubusercontent.com/hunterhn/Mobile-Harness/pr-evidence/pr4/contact-sheet.png
```

Individual screenshots are in `pr-evidence/pr4/`. The PR body embeds the key ones and links the contact sheet.

Local (ignored) evidence folders:

```text
.superpowers/device-tests/2026-11-07-recovery/
.superpowers/device-tests/2026-11-07-smoke/
.superpowers/device-tests/2026-11-06-fix-ab-evidence.md
.superpowers/device-tests/2026-11-06-redmi/fix-ab/
```

---

## 10. Known limitations / not verified

- **Realme GT8:** the exact device from #89 was not available. Verification used a Redmi Note and a Lenovo Tab P11. The failure family and recovery path are the same, but a direct Realme run is still desirable.
- **“Retry anyway” with mutating tools:** the warning dialog was shown and “Ver cambios” was exercised; the destructive “Retry anyway” path was not executed (it would have repeated a `sleep 150`).
- **Antigravity auto-resume:** remains best-effort; manual retry carries the correct context.
- **Nudge watchdog:** 30 s; a nudge that reasons longer than that may be cut.
- **Fail-closed:** if a runtime cannot prove it is safe to continue, the app shows the manual path instead of auto-recovering. This is intentional.

---

## 11. Traceability summary

| Item | Link / path |
|---|---|
| PR 4 | https://github.com/hunterhn/Mobile-Harness/pull/4 |
| Issue #89 | https://github.com/techjarves/Mobile-Harness/issues/89 |
| Split plan | `docs/handoff/2026-11-08-pr-split-plan.md` |
| Recovery evidence | `.superpowers/device-tests/2026-11-07-recovery/evidence.md` |
| Smoke evidence | `.superpowers/device-tests/2026-11-07-smoke/evidence.md` |
| Fix A+B evidence | `.superpowers/device-tests/2026-11-06-fix-ab-evidence.md` |
| Visual evidence branch | https://github.com/hunterhn/Mobile-Harness/tree/pr-evidence |
| PR 4 contact sheet | https://raw.githubusercontent.com/hunterhn/Mobile-Harness/pr-evidence/pr4/contact-sheet.png |

No secrets or API keys are included in this document or its evidence.
