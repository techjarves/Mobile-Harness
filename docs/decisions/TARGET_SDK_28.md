# ADR: targetSdk = 28 for the direct APK (ISSUE-012)

- **Status:** Accepted, revisit with the roadmap 3j validation matrix
- **Date:** 2026-09-25
- **Scope:** `app/build.gradle.kts` (`targetSdk = if (playBuild) 36 else 28`), Play track

## Context

Mobile Harness runs its coding agents through PRoot, which relies on the
`PR_SET_PTRACER` personality and `/proc` behaviours that behave differently
on newer Android releases. The direct-distribution APK (GitHub releases,
F-Droid) has shipped targetSdk 28 since v1.0.0 and that combination is the
*proven* execution path on the 9-device reference matrix.

The Play build already targets SDK 36 behind the `-PplayBuild=true` flag; it
is distributed only after its runtime path is validated on the same matrix.

## Decision

1. The direct APK stays on **targetSdk 28** until the staged upgrade is
   validated per level: 28 → 33 → 36, with a full agent session
   (install → prompt → build → undo/keep) executed on every reference device
   at each level.
2. The Play channel remains the early-warning surface for newer target SDKs.
3. `REQUEST_INSTALL_PACKAGES` is retained only for the in-app APK update flow
   and is declared with its Play-content rationale in
   `docs/play/REVIEWER_INSTRUCTIONS.md`.

## Consequences

- Store review on channels that require targetSdk ≥ 33: handled by the Play
  build; the direct/F-Droid channel documents the exception (this ADR).
- `START_NOT_STICKY`/foreground-service and WorkManager behaviours are pinned
  to the 28 semantics; any migration re-tests the notification safeguard
  (ISSUE-036) at each level.
- This decision is revisited as part of roadmap 3j (trial upgrade on the
  experimental channel only).

## References

- Phase-3 audit, ISSUE-012 (targetSdk=28 + REQUEST_INSTALL_PACKAGES decision)
- Roadmap §4.3.2 (3j: documented decision + staged trial upgrade)
