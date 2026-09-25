package com.jarves.mh.runtime

import org.json.JSONObject

/**
 * Pure decision core of the Ubuntu 20.04 → 24.04 base migration (ISSUE-008
 * final slice, roadmap 3i).
 *
 * Everything here is plain Kotlin + org.json so the security-relevant
 * behavior — which base markers may boot, what agent state survives a
 * migration, how a crashed migration is classified, and when the upgrade is
 * offered at all — is unit-testable on the JVM without Android. The
 * filesystem orchestration lives in [RootfsMigration]; the *policy* lives
 * here.
 *
 * Fail-closed rules enforced by this module:
 *  - Only markers in [SUPPORTED_ROOTFS_VERSIONS] are considered a valid base;
 *    anything else reads as "not installed" and the installer repairs it.
 *  - The upgrade is offered only when this app build pins a digest for the
 *    24.04 Core bundle ([UBUNTU_24_BUNDLE]) — while it is null the upgrade is
 *    hidden everywhere and nothing can trigger a migration.
 *  - A migration state file that cannot be fully understood decodes as null
 *    and callers treat that as a fresh IDLE state.
 */

enum class UbuntuMigrationPhase {
    /** No migration has run (or the last attempt never reached the swap). */
    IDLE,

    /** A migration is in flight; a fresh start must reconcile before anything else. */
    RUNNING,

    /** 24.04 is active; the 20.04 rollback root is kept until the first successful session. */
    AWAITING_FIRST_SESSION,

    /** 24.04 committed: first session succeeded, rollback root deleted. */
    DONE,

    /** Back on 20.04 after a manual or automatic rollback. Retrying is allowed. */
    ROLLED_BACK,
}

data class UbuntuMigrationState(
    val phase: UbuntuMigrationPhase = UbuntuMigrationPhase.IDLE,
    val fromMarker: String = "",
    val toMarker: String = "",
    val startedAtMillis: Long = 0L,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** What a startup reconciliation should do with the filesystem it finds. */
enum class MigrationRecovery { NOTHING, DISCARD_STAGING, RESTORE_ROLLBACK, KEEP_AWAITING }

object RootfsMigrationPolicy {

    /** Marker inside the 20.04 Core bundle (written by its build script). */
    const val UBUNTU_20_MARKER = "ubuntu-20.04.5-arm64"

    /** Marker inside the 24.04 Core bundle (build-core24-on-rooted-android.sh). */
    const val UBUNTU_24_MARKER = "ubuntu-24.04.5-arm64"

    /** Core-tools marker written by the 24.04 Core bundle. */
    const val UBUNTU_24_CORE_TOOLS_VERSION = "core-bundle-2026.09.6"

    /**
     * Every rootfs marker this app build can boot. A marker outside this set
     * makes `isInstalled()` false and the installer re-extracts the default
     * Core bundle — so adding 24.04 here is what lets a migrated device keep
     * its new base instead of being "repaired" back to 20.04.
     */
    val SUPPORTED_ROOTFS_VERSIONS = setOf(UBUNTU_20_MARKER, UBUNTU_24_MARKER)

    /**
     * The Ubuntu 24.04 Core bundle. Null until the artifact has been built on
     * the reference rooted ARM64 host with
     * `scripts/runtime-bundles/build-core24-on-rooted-android.sh`, published on
     * the runtime release channel, and its digest pinned here. While null the
     * upgrade is hidden everywhere and nothing can trigger a migration.
     *
     * Activation checklist (release engineering, in order):
     *  1. Run the build script; it prints the artifact's SHA-256 and byte size.
     *  2. Upload `pocketdev-core24-arm64-2026.09.6.tar.zst` to the runtime
     *     GitHub release (the channel behind `RUNTIME_RELEASE_BASE_URL`).
     *  3. Fill this in:
     *     `RuntimeBundle("Core 24.04", "pocketdev-core24-arm64-2026.09.6.tar.zst", "<sha256>", <compressedBytes>)`
     *  4. Add the matching entry to `dist/runtime-bundles/manifest.json` — the
     *     CI bundle fetcher fails closed on every entry, so add it only after
     *     the asset is actually published.
     *  5. Only after IT-13 (device matrix Android 9–15 + rollback drill) passes
     *     may a later release make 24.04 the default for fresh installs.
     */
    internal val UBUNTU_24_BUNDLE: RuntimeBundle? = null

    fun isSupportedRootfsVersion(marker: String?): Boolean = marker in SUPPORTED_ROOTFS_VERSIONS

    fun isUbuntu24(marker: String?): Boolean = marker == UBUNTU_24_MARKER

    /** Only a healthy 20.04 base can be upgraded. */
    fun isUpgradeSource(marker: String?): Boolean = marker == UBUNTU_20_MARKER

    fun baseLabel(marker: String?): String = when (marker) {
        UBUNTU_20_MARKER -> "Ubuntu 20.04.5 LTS"
        UBUNTU_24_MARKER -> "Ubuntu 24.04.5 LTS"
        else -> "Ubuntu (unrecognized build)"
    }

    /**
     * Free-space floor for a migration, checked BEFORE any download starts:
     * the compressed archive stays on disk while the tree is extracted next to
     * it, and the previous base must remain in place as the rollback root.
     */
    fun requiredFreeBytes(compressedBytes: Long): Long =
        compressedBytes + estimatedUncompressedBytes(compressedBytes) + HEADROOM_BYTES

    /**
     * Conservative size estimate for the extracted tree. The shipped 20.04
     * Core bundle compresses ~5.1x (369 MB from 72 MB); 6x leaves margin for
     * the fuller 24.04 base without demanding an inflated floor.
     */
    fun estimatedUncompressedBytes(compressedBytes: Long): Long = compressedBytes * 6

    private const val HEADROOM_BYTES = 64L * 1024 * 1024

    /**
     * Guest-side agent state copied from the old base into the new one. These
     * are the directories the agents themselves treat as home state
     * (conversation history, credentials, CLI configuration) — everything else
     * (toolchains, SDKs, node_modules, .gradle caches) is re-provisioned from
     * the bundle overlays and must NOT be carried over.
     */
    val AGENT_STATE_PRESERVE_PATHS = listOf(
        "root/.claude",
        "root/.claude.json",
        "root/.dsh",
        "root/.agy",
        "root/.config",
        "root/.ssh",
        "root/.gitconfig",
    )

    /** True for exactly the paths in [AGENT_STATE_PRESERVE_PATHS] and their children. */
    fun shouldPreservePath(relativePath: String): Boolean {
        val normalized = relativePath.trim('/', '.').replace('\\', '/')
        if (normalized.isBlank()) return false
        return AGENT_STATE_PRESERVE_PATHS.any { preserve ->
            normalized == preserve || normalized.startsWith("$preserve/")
        }
    }

    /**
     * The upgrade is offered only when: this build pins the bundle, the build
     * is the online flavor (offline APKs embed their bundles and cannot fetch
     * a new base), the active base is exactly 20.04, and no migration is in
     * flight or already committed. A rolled-back device may retry.
     */
    fun canOfferUpgrade(
        phase: UbuntuMigrationPhase,
        currentMarker: String?,
        bundleAvailable: Boolean,
        offlineBuild: Boolean,
    ): Boolean = bundleAvailable && !offlineBuild &&
        isUpgradeSource(currentMarker) &&
        phase in setOf(UbuntuMigrationPhase.IDLE, UbuntuMigrationPhase.ROLLED_BACK)

    // ---------------------------------------------------------------------
    // Migration state codec (fail-safe: anything not fully understood reads
    // as null, the same rule as the session journal)
    // ---------------------------------------------------------------------

    fun encodeState(state: UbuntuMigrationState): String = JSONObject()
        .put("phase", state.phase.name)
        .put("fromMarker", state.fromMarker)
        .put("toMarker", state.toMarker)
        .put("startedAtMillis", state.startedAtMillis)
        .put("attempts", state.attempts)
        .putOpt("lastError", state.lastError)
        .toString()

    /** Returns null for anything that is not a fully-formed state document. */
    fun decodeState(raw: String?): UbuntuMigrationState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            UbuntuMigrationState(
                phase = runCatching { UbuntuMigrationPhase.valueOf(obj.getString("phase")) }
                    .getOrElse { return null },
                fromMarker = obj.optString("fromMarker"),
                toMarker = obj.optString("toMarker"),
                startedAtMillis = obj.optLong("startedAtMillis", 0L),
                attempts = obj.optInt("attempts", 0),
                lastError = obj.optString("lastError").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Crash classification (pure)
    // ---------------------------------------------------------------------

    /**
     * Classifies the filesystem a fresh app start finds after a migration was
     * interrupted by process death. Read-only inputs; the caller performs the
     * action. `ubuntuMarker` is null when the active tree is missing or has no
     * readable marker.
     *
     *  - Active tree missing + rollback present → the crash happened between
     *    the two renames. Conservative branch: restore 20.04, discard staging.
     *  - Active tree missing without a rollback → orphaned staging only;
     *    discard it so storage is not silently held hostage.
     *  - Active tree is 24.04 → the swap completed; the state file simply was
     *    not written (or was lost). Resume as AWAITING_FIRST_SESSION.
     *  - Active tree is 20.04 with staging leftovers → crash before the swap;
     *    nothing happened to the base. Discard staging, allow a retry.
     *  - Anything else → leave the filesystem alone; the installer's own
     *    repair paths own that case.
     */
    fun recoveryAction(ubuntuMarker: String?, rollbackExists: Boolean, stagingExists: Boolean): MigrationRecovery = when {
        ubuntuMarker == null && rollbackExists -> MigrationRecovery.RESTORE_ROLLBACK
        ubuntuMarker == null -> MigrationRecovery.DISCARD_STAGING
        isUbuntu24(ubuntuMarker) -> MigrationRecovery.KEEP_AWAITING
        isUpgradeSource(ubuntuMarker) && stagingExists -> MigrationRecovery.DISCARD_STAGING
        else -> MigrationRecovery.NOTHING
    }
}
