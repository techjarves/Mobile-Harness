package com.jarves.mh.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeBundleStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun clearingCachePreservesPartialAndVerifiedBundle() {
        val cache = temp.newFolder("cache", "runtime-downloads")
        val privateDir = temp.newFolder("no_backup")
        val archive = runtimeBundleDownloadFile(cache, privateDir, "Android", "android.tar.zst")
        val partial = archive.resolveSibling("${archive.name}.part")
        partial.writeText("first bytes")

        cache.deleteRecursively()
        assertTrue("Cache eviction discarded the partial download", partial.isFile)
        assertTrue(partial.renameTo(archive))
        cache.deleteRecursively()
        assertTrue("Cache eviction discarded the verified archive", archive.isFile)
    }

    @Test
    fun adoptsExistingCachedDownloadBeforeCacheCanBeReclaimed() {
        val cache = temp.newFolder("cache", "runtime-downloads")
        val privateDir = temp.newFolder("no_backup")
        for (suffix in listOf(".part", "")) {
            val name = "android.tar.zst"
            val oldFile = cache.resolve(name + suffix)
            val bytes = "previous download".toByteArray()
            oldFile.writeBytes(bytes)
            val archive = runtimeBundleDownloadFile(cache, privateDir, "Android", name)
            val movedFile = archive.resolveSibling(name + suffix)
            assertFalse(oldFile.exists())
            assertArrayEquals(bytes, movedFile.readBytes())
            movedFile.delete()
        }
    }

    @Test
    fun discardsSupersededVersionWithoutDiscardingAnotherBundlesProgress() {
        val cache = temp.newFolder("cache", "runtime-downloads")
        val privateDir = temp.newFolder("no_backup")
        val old = runtimeBundleDownloadFile(cache, privateDir, "Android", "android-v1.tar.zst")
        old.writeText("old archive")
        val partial = old.resolveSibling("${old.name}.part").apply { writeText("old partial") }
        val core = runtimeBundleDownloadFile(cache, privateDir, "Core", "core.tar.zst")
        val corePartial = core.resolveSibling("${core.name}.part").apply { writeText("core progress") }

        runtimeBundleDownloadFile(cache, privateDir, "Android", "android-v2.tar.zst")
        assertFalse(old.exists())
        assertFalse(partial.exists())
        assertTrue(corePartial.isFile)
    }

    @Test
    fun promotesCompleteVerifiedPartialAfterInterruption() {
        val archive = temp.newFile("bundle").apply { writeText("invalid") }
        val partial = temp.newFile("bundle.part").apply { writeText("valid") }

        assertTrue(reuseVerifiedDownload(archive, partial, 5) { it.readText() == "valid" })
        assertArrayEquals("valid".toByteArray(), archive.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun discardsInvalidFullOrOversizedPartialButKeepsResumableBytes() {
        val archive = temp.root.resolve("bundle")
        val partial = temp.root.resolve("bundle.part")
        for (contents in listOf("wrong", "oversized")) {
            partial.writeText(contents)
            assertFalse(reuseVerifiedDownload(archive, partial, 5) { it.readText() == "valid" })
            assertFalse(partial.exists())
            assertFalse(archive.exists())
        }
        partial.writeText("va")
        assertFalse(reuseVerifiedDownload(archive, partial, 5) { error("incomplete file") })
        assertArrayEquals("va".toByteArray(), partial.readBytes())
    }

    @Test
    fun verifiedArchiveDiscardsRedundantPartial() {
        val archive = temp.newFile("bundle").apply { writeText("valid") }
        val partial = temp.newFile("bundle.part").apply { writeText("va") }

        assertTrue(reuseVerifiedDownload(archive, partial, 5) { it.readText() == "valid" })
        assertFalse(partial.exists())
    }
}
