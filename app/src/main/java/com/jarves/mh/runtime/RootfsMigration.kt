package com.jarves.mh.runtime

import android.content.Context
import android.os.StatFs
import com.jarves.mh.BuildConfig
import com.jarves.mh.model.AgentAutonomyMode
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DevStack
import java.io.File

/**
 * One-time Ubuntu 20.04 → 24.04 base migration with a rollback root
 * (ISSUE-008 final slice, roadmap 3i). The decision core lives in
 * [RootfsMigrationPolicy]; this class performs the filesystem work.
 *
 * Design contract (roadmap §4.3.4 — "the biggest operational risk in the plan"):
 *
 *  1. The previous base is NEVER deleted during the upgrade. The active tree
 *     `runtime/ubuntu` is renamed to `runtime/ubuntu.rollback` and stays there
 *     until the first agent session completes successfully on 24.04; only then
 *     is the rollback root removed.
 *  2. The swap is two renames. A crash between them is detected at the next
 *     start ([reconcileAtStartup]) and always resolves by restoring 20.04 —
 *     the conservative branch. A crash before the swap leaves the base
 *     untouched by construction.
 *  3. The upgrade is opt-in and experimental: it is only offered when this app
 *     build pins a digest for the 24.04 Core bundle
 *     ([RootfsMigrationPolicy.UBUNTU_24_BUNDLE]) and never in offline-asset
 *     builds.
 *  4. Fresh installs keep the pinned 20.04 Core bundle until the 24.04 bundle
 *     has passed the device matrix (IT-13); flipping the default is a separate
 *     release decision, not a side effect of this code.
 */
class RootfsMigration(
    private val context: Context,
    private val installer: RuntimeInstaller,
) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val activeRootfs = File(runtimeDir, "ubuntu")
    private val rollbackRootfs = File(runtimeDir, "ubuntu.rollback")
    private val stagingRootfs = File(runtimeDir, "ubuntu.migrating")
    private val stateFile = File(runtimeDir, "ubuntu-migration.json")

    fun currentState(): UbuntuMigrationState =
        RootfsMigrationPolicy.decodeState(stateFile.readTextSafely())
            ?: UbuntuMigrationState()

    fun currentMarker(): String? = installer.rootfsMarkerValue()

    fun rollbackRootExists(): Boolean = rollbackRootfs.isDirectory

    fun isUpgradeAvailable(): Boolean = RootfsMigrationPolicy.canOfferUpgrade(
        phase = currentState().phase,
        currentMarker = currentMarker(),
        bundleAvailable = RootfsMigrationPolicy.UBUNTU_24_BUNDLE != null,
        offlineBuild = BuildConfig.OFFLINE_RUNTIME_BUNDLES,
    )

    /**
     * Repairs a migration interrupted by process death. Returns a human-facing
     * message when something was actually repaired, null when no migration was
     * in flight. Never throws: reconciliation must not block startup.
     */
    fun reconcileAtStartup(): String? = runCatching {
        val state = currentState()
        if (state.phase !in setOf(UbuntuMigrationPhase.RUNNING, UbuntuMigrationPhase.AWAITING_FIRST_SESSION)) {
            // Stale staging from a crash that predated the swap (state stayed
            // IDLE): clean it so the storage is not silently held hostage.
            if (state.phase == UbuntuMigrationPhase.IDLE && stagingRootfs.exists()) stagingRootfs.deleteRecursively()
            return null
        }
        when (RootfsMigrationPolicy.recoveryAction(currentMarker(), rollbackRootfs.isDirectory, stagingRootfs.isDirectory)) {
            MigrationRecovery.RESTORE_ROLLBACK -> {
                stagingRootfs.deleteRecursively()
                if (activeRootfs.exists()) activeRootfs.deleteRecursively()
                check(rollbackRootfs.renameTo(activeRootfs)) { "rollback rename failed" }
                writeState(state.copy(phase = UbuntuMigrationPhase.ROLLED_BACK, lastError = "Upgrade interrupted; Ubuntu 20.04 was restored automatically."))
                "The Ubuntu 24.04 upgrade was interrupted, so the previous Ubuntu base was restored. Nothing was lost."
            }

            MigrationRecovery.DISCARD_STAGING -> {
                stagingRootfs.deleteRecursively()
                writeState(state.copy(phase = UbuntuMigrationPhase.IDLE, lastError = "Upgrade interrupted before activation; nothing changed."))
                "The Ubuntu 24.04 upgrade was interrupted before anything changed. You can retry it from Settings."
            }

            MigrationRecovery.KEEP_AWAITING -> {
                if (!installer.isInstalled()) {
                    // 24.04 active but broken: the rollback root is the safety net.
                    if (rollback()) {
                        "The Ubuntu 24.04 base failed its startup check; Ubuntu 20.04 was restored automatically."
                    } else {
                        writeState(state.copy(phase = UbuntuMigrationPhase.IDLE, lastError = "Ubuntu 24.04 damaged and rollback unavailable; the runtime will be reinstalled."))
                        "The Ubuntu 24.04 base could not be verified. The runtime will be repaired on next setup."
                    }
                } else {
                    writeState(state.copy(phase = UbuntuMigrationPhase.AWAITING_FIRST_SESSION))
                    null
                }
            }

            MigrationRecovery.NOTHING -> null
        }
    }.getOrElse { error ->
        AppLog.w("RootfsMigration", "Startup reconciliation failed: ${error.message}")
        null
    }

    /**
     * Runs the full one-time upgrade. Throws (with a user-readable message)
     * on failure; after the swap any failure first performs [rollback], so an
     * exception never leaves a broken base behind.
     *
     * @param agent agent whose overlay should be re-provisioned on the new base
     * @param stacks optional stacks to re-provision (they are overlays too)
     * @param mode autonomy mode used to regenerate guest settings on 24.04
     */
    suspend fun migrate(
        agent: AgentKind,
        stacks: Set<DevStack>,
        mode: AgentAutonomyMode,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(!BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            "The Ubuntu 24.04 upgrade needs the Mobile Harness online APK."
        }
        val bundle = RootfsMigrationPolicy.UBUNTU_24_BUNDLE
            ?: error("This Mobile Harness build does not carry a verified Ubuntu 24.04 bundle yet.")
        check(currentState().phase != UbuntuMigrationPhase.RUNNING) { "An Ubuntu upgrade is already in flight." }
        check(RootfsMigrationPolicy.isUpgradeSource(currentMarker())) {
            "The current Ubuntu base cannot be upgraded."
        }
        val previous = currentState()
        writeState(
            previous.copy(
                phase = UbuntuMigrationPhase.RUNNING,
                fromMarker = RootfsMigrationPolicy.UBUNTU_20_MARKER,
                toMarker = RootfsMigrationPolicy.UBUNTU_24_MARKER,
                startedAtMillis = System.currentTimeMillis(),
                attempts = previous.attempts + 1,
                lastError = null,
            ),
        )

        try {
            ensureFreeStorage(bundle.compressedBytes)

            onProgress(RuntimeInstallProgress("Downloading the Ubuntu 24.04 runtime", 0.05f))
            val archive = installer.obtainRuntimeBundle(
                bundle,
                preferEmbedded = false,
                from = 0.05f,
                to = 0.45f,
                onProgress,
            )

            val swapped = runCatching {
                onProgress(RuntimeInstallProgress("Unpacking the Ubuntu 24.04 runtime", 0.5f, indeterminate = true))
                stagingRootfs.deleteRecursively()
                stagingRootfs.mkdirs()
                installer.extractZstdTar(archive, stagingRootfs)
                installer.stripMacosMetadataArtifacts(stagingRootfs)
                validateStaging(stagingRootfs)

                onProgress(RuntimeInstallProgress("Preserving agent conversations and credentials", 0.7f, indeterminate = true))
                preserveAgentState(stagingRootfs)
                installer.writeResolver(stagingRootfs)

                onProgress(RuntimeInstallProgress("Activating Ubuntu 24.04", 0.78f, indeterminate = true))
                swapRootfs()
                true
            }

            if (swapped.isFailure) {
                // Nothing was swapped (or the first rename was undone): the
                // 20.04 base is untouched. Clean up and let the user retry.
                stagingRootfs.deleteRecursively()
                writeState(currentState().copy(phase = UbuntuMigrationPhase.IDLE, lastError = swapped.exceptionOrNull()?.message))
                throw swapped.exceptionOrNull() ?: IllegalStateException("Ubuntu 24.04 activation failed")
            }

            // From here the 24.04 base is active. Any failure must roll back.
            try {
                installer.ensureRootfsCompatibilityLinks()
                installer.ensureSettingsAndHooks(mode)
                File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

                onProgress(RuntimeInstallProgress("Verifying the new Ubuntu base", 0.84f, indeterminate = true))
                installer.verifyGuest(
                    installer.installedRuntime().proot,
                    "bash -c 'echo harness-ok' && node --version && git --version",
                    "Ubuntu 24.04 failed its startup check; the previous base will be restored.",
                )
            } catch (failure: Throwable) {
                rollback()
                throw failure
            }

            writeState(
                currentState().copy(
                    phase = UbuntuMigrationPhase.AWAITING_FIRST_SESSION,
                    lastError = null,
                ),
            )
            if (archive.parentFile == File(context.cacheDir, "runtime-downloads")) archive.delete()

            // Re-provision overlays on the new base. The base itself already
            // passed the smoke test, so a provisioning failure (e.g. network)
            // must NOT roll it back — the user simply retries.
            try {
                onProgress(RuntimeInstallProgress("Reinstalling ${agent.title} on Ubuntu 24.04", 0.88f, indeterminate = true))
                installer.ensureInstalled(
                    selectedStacks = stacks,
                    agent = agent,
                    onProgress = { progress ->
                        onProgress(
                            RuntimeInstallProgress(
                                progress.message,
                                0.88f + progress.fraction.coerceIn(0f, 1f) * 0.12f,
                                progress.downloadedBytes,
                                progress.totalBytes,
                                event = progress.event,
                            ),
                        )
                    },
                )
            } catch (provisioning: Throwable) {
                writeState(
                    currentState().copy(
                        lastError = "Ubuntu 24.04 is active, but ${agent.title} could not be reinstalled " +
                            "(${provisioning.message?.take(120)}). Start a task or reopen Settings to finish setup.",
                    ),
                )
            }
        } finally {
            stagingRootfs.deleteRecursively()
        }
    }

    /** Swaps the active rootfs for the staged one, keeping the old base as rollback. */
    private fun swapRootfs() {
        rollbackRootfs.deleteRecursively()
        check(activeRootfs.renameTo(rollbackRootfs)) { "Could not stage the Ubuntu 20.04 rollback copy." }
        try {
            check(stagingRootfs.renameTo(activeRootfs)) { "Could not activate the Ubuntu 24.04 runtime." }
        } catch (failure: Throwable) {
            // Undo the first rename so the base stays exactly as it was.
            rollbackRootfs.renameTo(activeRootfs)
            throw failure
        }
    }

    /**
     * Structural validation of the staged tree BEFORE any swap: the files the
     * app itself requires to boot a rootfs, plus the bundle's own markers.
     */
    private fun validateStaging(staging: File) {
        require(File(staging, "usr/bin/bash").isFile) { "Ubuntu 24.04 bundle is missing Bash." }
        require(File(staging, "usr/bin/env").canExecute()) { "Ubuntu 24.04 bundle is missing env." }
        require(File(staging, "lib/ld-linux-aarch64.so.1").exists()) { "Ubuntu 24.04 bundle is missing the ARM64 loader." }
        require(File(staging, "usr/local/bin/node").canExecute()) { "Ubuntu 24.04 bundle is missing Node.js." }
        require(File(staging, ".pocket-rootfs-version").readTextSafely() == RootfsMigrationPolicy.UBUNTU_24_MARKER) {
            "Ubuntu 24.04 bundle carries an unexpected base marker."
        }
        require(File(staging, ".pocket-runtime-ready").exists()) { "Ubuntu 24.04 bundle is not marked ready." }
    }

    /** Copies the allow-listed agent state from the old base into the new tree. */
    private fun preserveAgentState(staging: File) {
        RootfsMigrationPolicy.AGENT_STATE_PRESERVE_PATHS.forEach { relative ->
            val source = File(activeRootfs, relative)
            if (!source.exists()) return@forEach
            val target = File(staging, relative)
            runCatching {
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = true)
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }.onFailure { error ->
                AppLog.w("RootfsMigration", "Could not preserve $relative: ${error.message}")
            }
        }
    }

    /**
     * Returns the runtime to the 20.04 rollback root. Safe to call when 24.04
     * is active-but-unwanted (user-initiated) or active-but-broken (automatic).
     */
    fun rollback(): Boolean {
        if (!rollbackRootfs.isDirectory) return false
        val state = currentState()
        activeRootfs.deleteRecursively()
        val restored = rollbackRootfs.renameTo(activeRootfs)
        if (restored) {
            writeState(
                state.copy(
                    phase = UbuntuMigrationPhase.ROLLED_BACK,
                    lastError = null,
                ),
            )
        }
        return restored && installer.isInstalled()
    }

    /**
     * Commits the migration: called only after an agent session completed
     * successfully while 24.04 is active. Deletes the rollback root.
     */
    fun finalizeAfterFirstSuccessfulSession() {
        val state = currentState()
        if (state.phase != UbuntuMigrationPhase.AWAITING_FIRST_SESSION) return
        if (!RootfsMigrationPolicy.isUbuntu24(currentMarker())) return
        if (!installer.isInstalled()) return
        rollbackRootfs.deleteRecursively()
        writeState(state.copy(phase = UbuntuMigrationPhase.DONE))
        AppLog.d("RootfsMigration", "Ubuntu 24.04 committed; rollback root removed")
    }

    private fun ensureFreeStorage(compressedBytes: Long) {
        val required = RootfsMigrationPolicy.requiredFreeBytes(compressedBytes)
        val available = runCatching { StatFs(runtimeDir.absolutePath).availableBytes }.getOrNull() ?: return
        check(available >= required) {
            val mb = 1_048_576L
            "The Ubuntu 24.04 upgrade needs about ${required / mb} MB of free storage " +
                "(the previous base is kept as a rollback), but only ${available / mb} MB is free. " +
                "Free up space and try again."
        }
    }

    private fun writeState(state: UbuntuMigrationState) {
        runtimeDir.mkdirs()
        val temporary = File(runtimeDir, "ubuntu-migration.json.tmp")
        runCatching {
            temporary.writeText(RootfsMigrationPolicy.encodeState(state))
            if (!temporary.renameTo(stateFile)) {
                stateFile.writeText(RootfsMigrationPolicy.encodeState(state))
                temporary.delete()
            }
        }.onFailure { error ->
            AppLog.w("RootfsMigration", "Could not persist migration state: ${error.message}")
        }
    }

    private fun File.readTextSafely(): String? = runCatching { readText().trim() }.getOrNull()
}
