# IT-13 — Ubuntu 24.04 base upgrade: device matrix & rollback drill

**Gate for:** ISSUE-008 (roadmap 3i) · **Blocks:** making the 24.04 Core bundle
the default for fresh installs (and the v1.2.0+ release gate in the
verification plan) · **Runs:** once per candidate bundle build, manually, on
physical devices.

This is the test protocol the audit called the single biggest operational risk
in the whole repair plan: a broken base upgrade bricks the runtime gradually
and silently across device segments. The upgrade therefore ships as an opt-in
experiment, and this matrix is what earns it default status.

## Pre-conditions

- The candidate `pocketdev-core24-arm64-<version>.tar.zst` has been built with
  `scripts/runtime-bundles/build-core24-on-rooted-android.sh` and published on
  the runtime release channel.
- A test APK of the same build pins the bundle digest in
  `RootfsMigrationPolicy.UBUNTU_24_BUNDLE` (otherwise the upgrade row is hidden
  and this protocol cannot run).
- Every device below starts from a **clean v1.4.x online install** with the
  20.04 base, one project with files, and at least one completed agent session
  (so conversation history exists to verify preservation).

## Device matrix (minimum 7 physical devices)

| # | Android | RAM | Chipset | Notes |
|---|---------|-----|---------|-------|
| 1 | 9 (API 28) | 3 GB | Snapdragon (entry) | The floor: oldest supported + tightest RAM — OOM behavior differs here |
| 2 | 10 | 3 GB | MediaTek Helio | Aggressive background killers; matters for mid-migration process death (step 4) |
| 3 | 11 | 6 GB | Snapdragon | Reference mid-tier |
| 4 | 12 | 6 GB | Dimensity | MIUI or similar OEM skin — PackageInstaller/battery quirks |
| 5 | 13 | 6 GB | Snapdragon | Per-app language era; also run the UI pass in RTL |
| 6 | 14 | 12 GB | Snapdragon flagship | Upper bound |
| 7 | 15 | 8 GB | Dimensity | Newest platform; plus a **near-full storage** state (≤ 1.5 GB free) to exercise the space guard |

If a device generation cannot be sourced, document the gap explicitly in the
release notes — do not silently drop a row. A missing row means the upgrade
stays experimental for another cycle.

## Procedure per device

1. **Offer check:** Settings → Linux base shows *Ubuntu 20.04.5 LTS* and the
   experimental upgrade row (bundle pinned, online APK).
2. **Upgrade:** Confirm the dialog → expect download progress with byte
   counts → unpack → "Activating Ubuntu 24.04" → agent re-install → completion
   message. Record total wall-clock time and peak storage use
   (`adb shell du -sh /data/data/com.jarves.mh/files/runtime/*` via run-as on
   debug builds).
3. **Smoke pass:** the section subtitle now reads *Ubuntu 24.04.5 LTS*;
   start a trivial task ("create hello.txt with the text hi") in the
   previously used project — the agent conversation list from 20.04 is
   present, the task completes, and after completion the section shows the
   rollback copy was removed (phase DONE). Verify inside the guest terminal:
   `cat /etc/os-release` → 24.04, `node --version` → v24.19.0,
   `git --version` works.
4. **Interruption drill (device 2 and one other):** repeat the upgrade, kill
   the app mid-download (`adb shell am kill com.jarves.mh`) and once more
   mid-unpack. Next start must show the reconciliation message, leave the
   20.04 base working, and allow a retry. On one device also kill between the
   two renames (mid-"Activating") — next start must restore 20.04
   automatically with the "restored automatically" message.
5. **Manual rollback (device 1):** upgrade, then Settings → Linux base →
   *Return to Ubuntu 20.04*. Expect the 20.04 base back, conversations
   restored, and the upgrade row available again.
6. **Broken-base auto-rollback (device 3):** upgrade, then corrupt the active
   rootfs marker on a debug build (`run-as` → truncate
   `files/runtime/ubuntu/.pocket-rootfs-version`). Next start must restore
   20.04 automatically and log the reconciliation.
7. **Determinism (any two clean devices):** after upgrading, compare
   `dpkg -l | sha256sum` inside the guest — the hashes must match, proving
   both devices hold the identical, bundle-frozen package state
   (ISSUE-008 acceptance).
8. **Maintenance marker:** `/root` marker `.pocket-system-upgrade-version`
   reads `ubuntu-maintenance-v3` and no setup step runs an
   `apt-get upgrade` (logcat during upgrade confirms only
   `dpkg --configure -a` repair).

## Pass criteria

- All matrix rows pass steps 1–3; interruption drill passes on both
  designated devices with zero data loss (projects untouched, conversations
  preserved).
- Determinism hashes match across the two clean devices.
- No ANR during migration on the 3 GB devices; migration completes within a
  documented per-device time budget on a 20 Mbps connection.
- Any failure on any row = the upgrade stays opt-in and the bundle is rebuilt;
  a failure on the interruption drill additionally blocks publishing the
  pinned digest at all.
