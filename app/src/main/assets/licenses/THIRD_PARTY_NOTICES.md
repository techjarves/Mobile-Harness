# Third-Party Notices — Mobile Harness

This file inventories every third-party component that Mobile Harness
redistributes or embeds, where it comes from, and under which license it is
used (ISSUE-026). First-party code is MIT-licensed in the repository root
`LICENSE`.

## 1. Components compiled into the app (NDK / native bridge)

| Component | Source | License | Notice file |
|---|---|---|---|
| PRoot | https://github.com/termux/proot (submodule `third_party/proot`) | GPL-2.0 | `assets/licenses/proot-GPL-2.0.txt` |
| libandroid-shmem | https://github.com/termux/libandroid-shmem (submodule `third_party/libandroid-shmem`) | BSD-3-Clause | `assets/licenses/libandroid-shmem-BSD-3-Clause.txt` |
| talloc | https://talloc.samba.org (vendored `third_party/talloc`) | LGPL-3.0-or-later | `assets/licenses/talloc-LGPL-3.0-or-later.txt` |

## 2. Runtime binaries redistributed inside the offline APK

These are the pinned, SHA-256-verified bundles listed in
`dist/runtime-bundles/manifest.json`. Every bundle is downloaded from the
project's own GitHub release channel and its digest is pinned at build time;
updates are restricted to versions with digests pinned inside the app
(`VerifiedAgentReleases`, ISSUE-002/008).

| Component | Origin | License / terms |
|---|---|---|
| Claude Code CLI (`claude`) | Anthropic official npm distribution, repackaged for ARM64 Linux | MIT — `assets/licenses/claude-code-android-MIT.txt` |
| DeepSeek Harness (`dsh`) | DeepSeek official npm distribution | As published by DeepSeek for the CLI package |
| Antigravity CLI (`agy`) | Google's official CLI distribution | As published by Google for the CLI package |
| Ubuntu 20.04 userland (rootfs) | Built from official Ubuntu 20.04 ARM64 packages | Ubuntu licence terms (GPL et al. per package); provenance: standard `ubuntu-base` + `apt` package set |
| Node.js | nodejs.org official ARM64 Linux build | Node.js licence (MIT-ish); binary distributed unmodified |
| Android SDK / Gradle / Maven mirror for ARM64 | Built by the project from official AOSP/Google/Gradle artefacts (`android-tools-2026.09.1` release) | Per upstream: Android SDK Terms, Apache-2.0 (Gradle), per-artefact (Maven) |

**Redistribution note:** if the project's legal review restricts redistributing
any of the above inside an offline APK, the fallback is the `online` build
flavour, which downloads the same verified bundles on first run instead of
embedding them.

## 3. JVM libraries (resolved by Gradle at build time)

- `org.apache.commons:commons-compress` — Apache-2.0
- `com.github.luben:zstd-jni` — BSD-2-Clause
- `org.jetbrains.kotlinx:kotlinx-coroutines-*` — Apache-2.0
- AndroidX / Jetpack Compose (BOM 2025.02.00) — Apache-2.0
- `org.json:json` (test-only) — JSON License

The exact resolved versions are recorded by Gradle for each release and
attached to the release alongside the CycloneDX SBOM
(`scripts/generate_sbom.py`, ISSUE-019).

## 4. Update/toolchain endpoints

- App updates: signed APKs from the project's GitHub Releases, verified by
  signature + SHA-256 + versionCode (`AppUpdater`).
- Runtime bundles and Android toolchain assets: the project's GitHub Releases
  (`android-tools-2026.09.1`), SHA-256 pinned in code (ISSUE-003).
- Agent updates: only versions whose digest is pinned inside the app build
  (ISSUE-002/008).
