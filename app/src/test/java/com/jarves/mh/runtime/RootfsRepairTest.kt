package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsRepairTest {
    @Test
    fun replacesCorruptRuntimeWhilePreservingHomeAndWorkspaces() = withLayout { layout ->
        layout.rootfs.resolve("broken-system-file").writeText("broken")
        layout.rootfs.resolve("root/.config/agent/session.json").apply {
            parentFile!!.mkdirs()
            writeText("user-session")
        }
        layout.workspace.resolve("project.txt").writeText("keep-project")
        createValidRootfs(layout.staging)

        RootfsRepair.activate(layout.rootfs, layout.staging, layout.backup, ::isValidRootfs)

        assertTrue(isValidRootfs(layout.rootfs))
        assertFalse(layout.rootfs.resolve("broken-system-file").exists())
        assertEquals("user-session", layout.rootfs.resolve("root/.config/agent/session.json").readText())
        assertEquals("keep-project", layout.workspace.resolve("project.txt").readText())
        assertFalse(layout.backup.exists())
    }

    @Test
    fun invalidReplacementLeavesOriginalRuntimeUntouched() = withLayout { layout ->
        layout.rootfs.resolve("old-system-file").writeText("original")
        layout.rootfs.resolve("root/user.txt").apply { parentFile!!.mkdirs(); writeText("home") }
        layout.staging.mkdirs()

        val failure = runCatching {
            RootfsRepair.activate(layout.rootfs, layout.staging, layout.backup, ::isValidRootfs)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("original", layout.rootfs.resolve("old-system-file").readText())
        assertEquals("home", layout.rootfs.resolve("root/user.txt").readText())
        assertFalse(layout.backup.exists())
    }

    @Test
    fun failedPostActivationValidationRollsBackOriginalRuntimeAndHome() = withLayout { layout ->
        layout.rootfs.resolve("old-system-file").writeText("original")
        layout.rootfs.resolve("root/user.txt").apply { parentFile!!.mkdirs(); writeText("home") }
        createValidRootfs(layout.staging)
        var validations = 0

        val failure = runCatching {
            RootfsRepair.activate(layout.rootfs, layout.staging, layout.backup) {
                validations += 1
                validations == 1
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("original", layout.rootfs.resolve("old-system-file").readText())
        assertEquals("home", layout.rootfs.resolve("root/user.txt").readText())
        assertFalse(layout.backup.exists())
    }

    @Test
    fun interruptedSwapRestoresBackupWhenActiveRootIsMissing() = withLayout { layout ->
        layout.rootfs.resolve("root/user.txt").apply { parentFile!!.mkdirs(); writeText("home") }
        assertTrue(layout.rootfs.renameTo(layout.backup))
        createValidRootfs(layout.staging)

        RootfsRepair.reconcileInterruptedSwap(layout.rootfs, layout.staging, layout.backup, ::isValidRootfs)

        assertEquals("home", layout.rootfs.resolve("root/user.txt").readText())
        assertFalse(layout.backup.exists())
        assertFalse(layout.staging.exists())
    }

    @Test
    fun interruptedSwapFinishesHomeRestoreAfterNewRootActivation() = withLayout { layout ->
        layout.rootfs.resolve("root/user.txt").apply { parentFile!!.mkdirs(); writeText("home") }
        assertTrue(layout.rootfs.renameTo(layout.backup))
        createValidRootfs(layout.rootfs)

        RootfsRepair.reconcileInterruptedSwap(layout.rootfs, layout.staging, layout.backup, ::isValidRootfs)

        assertTrue(isValidRootfs(layout.rootfs))
        assertEquals("home", layout.rootfs.resolve("root/user.txt").readText())
        assertFalse(layout.backup.exists())
    }

    private fun createValidRootfs(directory: File) {
        directory.resolve("usr/bin/bash").apply { parentFile!!.mkdirs(); writeText("bash") }
        directory.resolve(".valid").writeText("yes")
        directory.resolve("root/.profile").apply { parentFile!!.mkdirs(); writeText("fresh") }
    }

    private fun isValidRootfs(directory: File): Boolean =
        directory.resolve("usr/bin/bash").isFile && directory.resolve(".valid").readText() == "yes"

    private fun withLayout(block: (Layout) -> Unit) {
        val directory = Files.createTempDirectory("mh-rootfs-repair-test").toFile()
        try {
            val files = directory.resolve("files").apply { mkdirs() }
            val runtime = files.resolve("runtime").apply { mkdirs() }
            val layout = Layout(
                rootfs = runtime.resolve("ubuntu").apply { mkdirs() },
                staging = runtime.resolve("ubuntu.installing"),
                backup = runtime.resolve("ubuntu.repair-backup"),
                workspace = files.resolve("workspaces/project").apply { mkdirs() },
            )
            block(layout)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Layout(
        val rootfs: File,
        val staging: File,
        val backup: File,
        val workspace: File,
    )
}
