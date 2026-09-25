# Security Policy

This document explains the security-relevant design decisions in Mobile Harness, how to report vulnerabilities, and the boundaries of the sandbox model. It reflects the current release; see the [audit roadmap](https://github.com/techjarves/Mobile-Harness) issues for planned hardening work.

## Reporting a Vulnerability

Please use [GitHub Security Advisories](https://github.com/techjarves/Mobile-Harness/security/advisories/new) to report vulnerabilities privately. Do not open public issues for exploitable defects. We aim to respond within 7 days and follow coordinated disclosure with a 90-day window.

## Security Model in One Paragraph

All coding agents run inside a PRoot-based Ubuntu 20.04 ARM64 guest that lives in the app's private storage. PRoot maps filesystems and identities in **user space** — it is a compatibility/translational layer, not a security container. It confines guest processes to the app's own data directory (no root, no other apps' storage, no system writes), and Android's standard app sandbox remains the outer boundary. Treat the guest as "a folder with a toolchain", not as a hardened VM.

## Known Design Decisions

### 1. Agent tool permissions follow a user-selected autonomy mode (v1.1.0)

**This is the most important thing to understand before using Mobile Harness.**

Three modes are selectable in Settings → Agent permissions, and the choice applies to all three agents:

- **Approve risky actions (default, including for upgrades from v1.0.x):** Claude Code runs with no auto-allow list; destructive and network commands (`rm -rf`, `git push`, `sudo`, `curl`, …) are surfaced to you as an approval card with a 60-second auto-deny timeout, and the network tools `WebFetch`/`WebSearch` are denied outright. DeepSeek Harness runs with `DSH_PERMISSION_MODE=default` and Antigravity without `--dangerously-skip-permissions`, so tool calls those CLIs cannot ask about headlessly are **denied, not auto-approved**.
- **Approve everything:** every Claude Code tool call waits for your approval card; dsh and agy behave as in the careful mode above.
- **Fully autonomous (explicit opt-in):** the historical v1.0.x behavior — Claude Code auto-allows the workspace tools, dsh runs `danger-full-access`, agy runs with `--dangerously-skip-permissions`. Fastest, and the most dangerous: a prompt injection can run any command inside the sandbox.

The approval channel itself is **fail-closed**: a corrupt or unreadable permission request, a dead bridge, or a timeout always results in `deny` — never `allow`.

Consequence regardless of mode: treat **imported repositories** and pasted prompts from unknown sources as untrusted. A README, a test fixture, or a tool output can carry prompt-injection instructions that the agent may act on — in careful modes it will ask you (verify what you approve!), in the autonomous mode it will simply act.

### 2. Read-only Android system binds inside the guest

The guest binds `/system`, `/apex`, `/vendor`, and `/product` from the host Android OS **read-only**. This is required because the ARM64 Android build toolchain (notably `aapt2`) resolves Bionic's `/system/bin/linker64` and, on newer Android releases, APEX libraries from the host OS partitions — the guest cannot build Android apps without them.

Impact: guest processes (and therefore the agents) can **read** Android system files, but cannot write them; PRoot confines all writes to the app sandbox. This is a deliberate, bounded architectural trade-off, documented here so it is a decision rather than an accident.

### 3. Provider credentials stay on the host side (v1.2.0 — sovereign proxy)

Claude Code and DeepSeek Harness custom routes no longer receive the real provider key. A loopback **sovereign proxy** holds the key inside the app process, injects it only when talking to the real upstream, and hands the guest a random per-session `http://127.0.0.1:<port>/t/<token>` URL plus a placeholder value — so `/proc/*/environ` inside the guest exposes no secrets. OpenAI-protocol providers keep using the format gateway, which already held the key host-side; since v1.2.0 their guest env also carries only the placeholder.

One documented exception remains: **dsh's native `deepseek-official` route** has no configurable base URL inside the CLI, so its key still travels via environment for that single provider. Since v1.6.0 the project-controlled Ed25519-signed update channel (§5) provides the safe path to ship a patched dsh build the day upstream adds proxy support — until then the exception stays documented here.

GitHub sign-in follows the same principle (v1.2.0): the gh OAuth token is moved into the Android Keystore vault, gh is logged out inside the guest, and every later gh invocation receives the token transiently via `GH_TOKEN` — nothing secret rests in the guest filesystem.

### 4. Local gateways on loopback, tokened per session (v1.1.0)

For OpenAI-protocol providers, a tiny local gateway listens on `127.0.0.1` (ephemeral port) to translate the wire format. Since v1.1.0 every gateway URL carries a random 128-bit path token (`/t/<token>/…`) that other apps on the device must present or receive `403`; request bodies are capped at 2 MB, header lines at 16 KB, and connections run on a small fixed thread pool. The sovereign proxy (§3) applies the same token and caps (32 MB body limit, sized for image-bearing requests). The residual limitation: like every loopback listener on Android, the port itself is reachable from other apps on the same device during an active session — but without the token they can neither use the gateway nor the provider key behind it.

### 5. Agent updates install only releases a trusted anchor has verified — pinned per app release, or signed with the project's offline key (v1.6.0)

The Antigravity auto-updater manifest and the npm registry are treated as *data sources*, not trust anchors: an update is only offered when the target version's tarball digest is pinned inside the app build (`VerifiedAgentReleases`), the download is verified against that pinned digest, and dsh installs from the verified local tarball instead of an open-ended `npm install`. A compromised endpoint can at worst serve a byte-identical copy of a binary this app already shipped.

Since v1.6.0 a second, lighter trust anchor exists (roadmap 3k, the long-term ISSUE-002 fix): a **project-signed update feed** — a manifest on the repo's `agent-updates` branch signed with an offline Ed25519 (minisign) key whose public half is pinned in `UpdateSigningKeys`. When a signed entry vouches for a version, URL, and SHA-512, the app can offer that update without waiting for the next app release; the download is still digest-verified on device. The signature covers the exact served bytes, so no JSON canonicalization is involved. Verification is fail-closed at every step: unknown key id, tampered byte, non-https URL, malformed digest, or unsupported schema all collapse to "no feed" and the pinned allowlist remains the only path. Claude Code updates gain the same signed option alongside their official `downloads.claude.ai` checksum path. The channel ships **dormant** — the key list is empty until the owner completes the activation runbook (`docs/release/signing-agent-updates.md`), which is locked by a unit test so no contributor can silently invent a trust anchor. Honest trade-off: while dormant, the practical update cadence is unchanged (a new agent version still needs an app release to pin its digest); activating the key is a one-time, five-minute operational step.

### 6. Conversation storage is bounded, and terminal history is scrubbed at rest (v1.3.0)

Chats live in a SQLite database inside the app's private storage (same File-Based Encryption as before, no new permissions). Storage is bounded per chat (2,000 messages, 100 KB per message body, 100 chats per project — oldest entries drop first, mirroring the long-standing 100-line terminal history cap). Credentials that look like API keys, tokens, or passwords are stripped from the terminal history **before it is written to disk**; the live terminal you are reading is not modified. Two honest trade-offs: (a) the redaction is pattern-based, so a long random-looking constant in code you `cat` through the terminal may occasionally be masked in the restored scrollback, and (b) conversations beyond the caps exist only in memory until they age out — if you need a permanent transcript, export the project. Upgrading from earlier versions imports every existing chat into the store once, verified, and only then removes the old JSON files; rolling back to a pre-1.3.0 build will therefore show old chats as empty (the data itself remains in the database).

### 7. Interrupted tasks resume from a journal that is safe to ignore (v1.4.0)

When a task starts, one small JSON entry (`sessions/active.json` in the app's private storage) records the agent, project, chat, the raw user request and — once observed — the agent's own conversation id (Claude Code `session_id`, Antigravity `conversationId`). Any terminal event deletes it. If the OS kills the app mid-task, the surviving entry is detected at the next start and shown as a "Task interrupted — Resume / Dismiss" banner (plus a notification from the restarted foreground service, which is `START_STICKY` while work is in flight).

Resume prefers the agent's native context: Claude Code is re-launched with `--resume <session_id>`, Antigravity with `--conversation <id>`, and DeepSeek Harness rebuilds from the persisted transcript. If the id was never captured (kill before the first event), Claude falls back to the transcript rebuild too — you lose the native context, not the task. Honest trade-offs: (a) the journal stores the raw request text (capped at 8 KB) so it can be re-sent — do not type state secrets into a prompt; (b) the resumed prompt explicitly tells the agent it was interrupted, which slightly changes the conversation vs. an uninterrupted run; (c) a kill during the final moments of a task may resume work that had actually finished server-side — the agent will simply confirm and stop. The journal is deliberately best-effort: a missing or corrupt file reads as "no task in flight" and can never block a session.

### 8. The Ubuntu base can be upgraded to 24.04 once, with a rollback root (v1.5.0)

Ubuntu 20.04 reached end of life in May 2025; a rootfs that no longer receives glibc/OpenSSL patches is a supply-chain liability, not a stability feature. The fix (roadmap 3i) ships in two halves. First, **on-device maintenance no longer upgrades anything**: the unattended `apt-get upgrade` is gone entirely, security patches ship inside rebuilt, digest-pinned Core bundles, and on-device maintenance is repair-only (`dpkg --configure -a` plus a best-effort apt fix — a dead mirror can never block setup). Two devices on the same bundle therefore hold byte-identical package state. Second, an **opt-in, experimental migration** to Ubuntu 24.04 (Settings → Linux base) downloads the new base, validates it, and swaps it in by rename while keeping the entire 20.04 environment on disk as a rollback root.

The rollback contract is deliberately conservative: the 20.04 rollback copy is deleted only after your **first task completes successfully** on 24.04, and you can return to 20.04 manually until then. A crash at any point during the upgrade is reconciled at next start — a crash between the two renames always restores 20.04, never half-finishes. Agent conversations, credentials, and CLI configuration (`.claude`, `.dsh`, `.agy`, `.config`, `.ssh`) are copied onto the new base; toolchains, SDKs, and caches are not — they are re-downloaded as the same verified overlays a fresh install uses. Honest trade-offs: (a) the upgrade needs free storage for both bases side by side (the dialog states the requirement before anything is downloaded); (b) the upgrade itself is only offered in online APK builds once this build pins a verified 24.04 bundle digest — the row stays hidden until then, exactly like unverified agent releases; (c) fresh installs keep receiving 20.04 until the 24.04 bundle passes the IT-13 device matrix (Android 9–15 plus the rollback drill in `docs/testing/`), because making it the default is a release decision earned by test evidence, not a side effect of shipping the code.

## What Is Already Hardened

- Provider keys at rest are encrypted with **AES-256-GCM** keys held in **Android Keystore** (hardware-backed where available).
- Since v1.2.0 the **active key never enters the guest environment** for Claude Code and dsh custom routes (sovereign proxy, §3); the GitHub OAuth token lives in the same vault.
- All runtime bundle downloads (rootfs, agents, toolchains) are verified against **SHA-256/SHA-512 checksums pinned in the app** before extraction; interrupted downloads resume rather than restart, and downloads/extractions check free storage before writing (570 MB archives fail with a clear message instead of a half-broken install).
- Agent in-app updates are additionally restricted to **per-release pinned digests** or **project Ed25519-signed manifests** (§5) — never to what an endpoint serves.
- In-app self-updates verify the **signing certificate, versionCode, and SHA-256** of the downloaded APK before offering installation.
- Archive extraction enforces path-traversal protection with a canonical-prefix check, and rejects symlink escapes.
- The project preview WebView blocks all non-loopback navigation and requests, with file/content access explicitly disabled.
- Agent permission requests travel through a fail-closed bridge: unreadable, malformed, or timed-out requests are denied, and the default mode never writes an always-allow decision into the guest.
- Workspaces have bounded checkpoints: files above 64 MB (or projects above a 256 MB baseline budget) are not copied; Undo is honestly reported as unavailable for them instead of silently destroying the file.
- Conversations are stored in a bounded SQLite store with per-chat and per-message caps, and terminal history saved to disk is scrubbed of credential-shaped values (v1.3.0, §6).
- Tasks interrupted by a process death are journaled and resumable with one tap — natively for Claude Code and Antigravity, from the transcript for DeepSeek Harness (v1.4.0, §7); repeated interruptions point the user at the battery-optimization exemption list.
- The release build is shrunk and optimized with R8, and debug/verbose logging (including any raw agent output) is stripped from release logcat.
