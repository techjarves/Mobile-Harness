package com.jarves.mh.runtime

import java.io.File

/** Failure-safe activation of a rebuilt Linux root while retaining the user's Linux home. */
internal object RootfsRepair {
    fun reconcileInterruptedSwap(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        if (!backup.exists()) return

        if (!rootfs.exists()) {
            check(backup.renameTo(rootfs)) { "Could not restore the previous Linux environment" }
            staging.deleteRecursively()
            return
        }

        if (validate(rootfs)) {
            restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Repaired Linux environment failed validation" }
            check(backup.deleteRecursively()) { "Could not remove the previous Linux environment" }
            staging.deleteRecursively()
            return
        }

        rollback(rootfs, staging, backup)
    }

    fun activate(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        require(staging.isDirectory && validate(staging)) {
            "Rebuilt Linux environment failed validation"
        }
        check(!backup.exists()) { "A previous Linux environment repair is still pending" }

        val hadRootfs = rootfs.exists()
        if (hadRootfs) {
            check(rootfs.renameTo(backup)) { "Could not preserve the previous Linux environment" }
        }

        try {
            check(staging.renameTo(rootfs)) { "Could not activate the rebuilt Linux environment" }
            if (hadRootfs) restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Rebuilt Linux environment failed validation after activation" }
            if (hadRootfs) {
                check(backup.deleteRecursively()) { "Could not remove the previous Linux environment" }
            }
        } catch (error: Throwable) {
            if (hadRootfs && backup.exists()) {
                runCatching { rollback(rootfs, staging, backup) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun restoreHome(backup: File, rootfs: File) {
        val preservedHome = File(backup, "root")
        if (!preservedHome.exists()) return
        val freshHome = File(rootfs, "root")
        if (freshHome.exists()) {
            check(freshHome.deleteRecursively()) { "Could not replace the rebuilt Linux home" }
        }
        check(preservedHome.renameTo(freshHome)) { "Could not restore the Linux home" }
    }

    private fun rollback(rootfs: File, staging: File, backup: File) {
        val activeHome = File(rootfs, "root")
        val backupHome = File(backup, "root")
        if (!backupHome.exists() && activeHome.exists()) {
            check(activeHome.renameTo(backupHome)) { "Could not preserve the Linux home during rollback" }
        }
        if (rootfs.exists()) {
            check(rootfs.deleteRecursively()) { "Could not remove the failed Linux environment" }
        }
        check(backup.renameTo(rootfs)) { "Could not restore the previous Linux environment" }
        staging.deleteRecursively()
    }
}
