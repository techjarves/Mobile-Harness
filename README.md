# Pocket Dev Android Alpha

Pocket Dev is a beginner-friendly Android shell for an on-device coding agent. It downloads Claude Code directly from Anthropic and runs it inside a private ARM64 Ubuntu environment without installing Termux. The APK embeds Termux's Android-patched PRoot runtime components so Linux programs can run under Android's app seccomp policy.

## What is implemented

- First-launch setup consent screen followed by checksum-verified downloads with live byte progress, percentage, and rotating beginner-friendly status messages
- Returning-launch initialization screen that verifies and starts the installed Claude Code runtime before opening Home
- Claude Code release checks run only during app startup; sending a chat message reuses the initialized local runtime without showing setup text in chat
- Device compatibility onboarding with Lite/Full tier indication
- Claude subscription, Anthropic, LLMrouter, OpenAI, Kimi, and custom-provider profiles
- Live model discovery with a directly selectable list and manual model-ID fallback
- Credential and model validation before provider settings are saved
- Android Keystore-backed API-key encryption
- Projects and starter-project creation
- Files tab backed by the selected project's real app-private workspace, with automatic refresh after Claude Code changes
- Chat, Files, Changes, Preview, and Activity workspace tabs
- Real Claude Code streaming events and explicit write/shell approval flow
- Claude launch-configuration builder using streaming JSON mode, custom base URL, and all default model roles
- Foreground execution service with a Stop action
- Checksum-verified Ubuntu 20.04 ARM64 rootfs and Anthropic Claude Code downloads
- Unit tests for gateway configuration and unsafe bypass prevention

## Runtime boundary

`ClaudeRuntimeBridge` installs a private ARM64 Ubuntu environment, downloads Claude Code directly from Anthropic, starts it in `stream-json` mode, and relays permission requests into Android approval cards.

Anthropic's proprietary binary is not bundled in the APK. PRoot is not a security sandbox; only trusted user projects should be opened. OpenAI/Kimi still require the planned Anthropic-format loopback adapter; direct Anthropic-compatible endpoints work now.

## Build

Requirements: JDK 17+, Android SDK 36.

```bash
./gradlew test assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Attribution

The runtime design is informed by [ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android), particularly its verified download, smoke-test, compatibility, and rollback work. If source from that MIT-licensed repository is ported verbatim, retain its copyright notice in a dedicated `NOTICE` file.
