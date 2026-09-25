package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLineType
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ISSUE-034: diffing must never load whole large/binary files into the heap.
 * A 50 MB binary produced by the agent itself has to yield an info card, not
 * an OutOfMemoryError, and small text diffs must keep working.
 */
class WorkspaceCheckpointsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newStore(): WorkspaceCheckpoints = WorkspaceCheckpoints(temp.root)

    @Test
    fun `a huge binary file becomes an info card instead of a heap blowup`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p1")
        store.createCheckpoint("p1", workspace)

        // 50 MB of pseudo-random binary content (guaranteed zero bytes).
        val big = File(workspace, "assets/blob.bin")
        big.parentFile?.mkdirs()
        Random(42).let { random ->
            big.outputStream().use { output ->
                val chunk = ByteArray(1024 * 1024)
                repeat(50) {
                    random.nextBytes(chunk)
                    chunk[0] = 0 // force binary detection in the first sample
                    output.write(chunk)
                }
            }
        }

        val details = store.buildChangeDetails("p1", workspace, listOf("assets/blob.bin"))
        assertEquals(1, details.size)
        val change = details.first()
        assertTrue("the change must be flagged binary", change.binary)
        assertEquals(0, change.additions)
        assertEquals(0, change.deletions)
        assertEquals(DiffLineType.INFO, change.diffLines.single().type)
    }

    @Test
    fun `an oversized text file is summarized without a full diff`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p2")
        store.createCheckpoint("p2", workspace)

        val large = File(workspace, "logs/generated.log")
        large.parentFile?.mkdirs()
        large.printWriter().use { writer ->
            repeat(400_000) { writer.println("line $it with some text to cross the size cap") }
        }
        assertTrue(large.length() > 5L * 1024 * 1024)

        val details = store.buildChangeDetails("p2", workspace, listOf("logs/generated.log"))
        val change = details.single()
        assertFalse(change.binary)
        assertEquals(DiffLineType.INFO, change.diffLines.single().type)
        assertTrue(change.diffLines.single().text.contains("too large"))
    }

    @Test
    fun `small text files keep their line diff`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p3")
        File(workspace, "notes.md").writeText("first\n")
        store.createCheckpoint("p3", workspace)
        File(workspace, "notes.md").writeText("first\nsecond\n")

        val details = store.buildChangeDetails("p3", workspace, listOf("notes.md"))
        val change = details.single()
        assertFalse(change.binary)
        assertEquals(1, change.additions)
        assertEquals(0, change.deletions)
        assertTrue(change.diffLines.any { it.type == DiffLineType.ADDITION && it.text == "second" })
    }

    @Test
    fun `agent state directories never enter a baseline or a changes manifest`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p4")
        File(workspace, "src").mkdirs()
        File(workspace, "src/Main.kt").writeText("fun main() {}\n")
        File(workspace, ".dsh/session.json").apply { parentFile.mkdirs() }.writeText("{}")
        File(workspace, ".agy/state.db").apply { parentFile.mkdirs() }.writeText("x")
        File(workspace, ".gradle/8.14/cache.bin").apply { parentFile.mkdirs() }.writeText("x")
        File(workspace, ".claude/settings.json").apply { parentFile.mkdirs() }.writeText("{}")

        store.createCheckpoint("p4", workspace)

        val backup = File(store.checkpointDir("p4"), "project")
        assertTrue(File(backup, "src/Main.kt").isFile)
        assertFalse(File(backup, ".dsh/session.json").exists())
        assertFalse(File(backup, ".agy/state.db").exists())
        assertFalse(File(backup, ".gradle/8.14/cache.bin").exists())
        assertFalse(File(backup, ".claude/settings.json").exists())

        assertTrue(store.isInternalRuntimePath(".dsh"))
        assertTrue(store.isInternalRuntimePath(".agy/config"))
        assertTrue(store.isInternalRuntimePath(".gradle/caches"))
        assertFalse(store.isInternalRuntimePath("build.gradle.kts"))
    }

    @Test
    fun `a file above the per-file cap is skipped and marked undo-unavailable`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p5")
        File(workspace, "small.txt").writeText("safe\n")
        val huge = File(workspace, "video/raw.mp4")
        huge.parentFile?.mkdirs()
        // Just over the 64 MB per-file cap.
        huge.outputStream().use { output ->
            val chunk = ByteArray(1024 * 1024)
            repeat(65) { output.write(chunk) }
        }

        store.createCheckpoint("p5", workspace)

        assertTrue(store.isUndoUnavailable("p5", "video/raw.mp4"))
        assertFalse(store.isUndoUnavailable("p5", "small.txt"))
        // The skipped file never entered the baseline…
        assertFalse(File(File(store.checkpointDir("p5"), "project"), "video/raw.mp4").exists())
        // …but it still produces an honest info card instead of a crash.
        val details = store.buildChangeDetails("p5", workspace, listOf("video/raw.mp4"))
        assertEquals(DiffLineType.INFO, details.single().diffLines.single().type)
        assertTrue(details.single().diffLines.single().text.contains("Undo is unavailable"))
    }

    @Test
    fun `the total budget caps the baseline and records every skipped path`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p6")
        // 5 files of ~60 MB each: the first four stay under the 256 MB total
        // budget together, the last one must be skipped.
        repeat(5) { index ->
            val file = File(workspace, "blob-$index.bin")
            file.outputStream().use { output ->
                val chunk = ByteArray(1024 * 1024)
                repeat(60) { output.write(chunk) }
            }
        }

        store.createCheckpoint("p6", workspace)

        val skipped = store.skippedLargePaths("p6")
        assertTrue("at least the last blob must be skipped", skipped.isNotEmpty())
        val backup = File(store.checkpointDir("p6"), "project")
        skipped.forEach { path -> assertFalse(File(backup, path).exists()) }
        val backed = (0 until 5).map { "blob-$it.bin" }.count { File(backup, it).isFile }
        assertTrue("the budget must have held for at least one file", backed >= 1)
    }
}
