package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-core tests for the Ubuntu 20.04 → 24.04 migration (ISSUE-008,
 * roadmap 3i). Everything here is plain JVM: the security-relevant choices —
 * which bases may boot, what agent state survives, how a crashed migration is
 * classified, and when the upgrade is offered at all — must not depend on
 * Android to be verifiable.
 */
class RootfsMigrationTest {

    // ---------------------------------------------------------------------
    // Supported base markers
    // ---------------------------------------------------------------------

    @Test
    fun `both shipped bases are supported`() {
        assertTrue(RootfsMigrationPolicy.isSupportedRootfsVersion("ubuntu-20.04.5-arm64"))
        assertTrue(RootfsMigrationPolicy.isSupportedRootfsVersion("ubuntu-24.04.5-arm64"))
    }

    @Test
    fun `unknown null and malformed markers are unsupported`() {
        assertFalse(RootfsMigrationPolicy.isSupportedRootfsVersion(null))
        assertFalse(RootfsMigrationPolicy.isSupportedRootfsVersion(""))
        assertFalse(RootfsMigrationPolicy.isSupportedRootfsVersion("ubuntu-24.04.6-arm64"))
        assertFalse(RootfsMigrationPolicy.isSupportedRootfsVersion("ubuntu-22.04-arm64"))
        assertFalse(RootfsMigrationPolicy.isSupportedRootfsVersion("Ubuntu-20.04.5-arm64"))
    }

    @Test
    fun `family detection separates the two bases`() {
        assertTrue(RootfsMigrationPolicy.isUbuntu24("ubuntu-24.04.5-arm64"))
        assertFalse(RootfsMigrationPolicy.isUbuntu24("ubuntu-20.04.5-arm64"))
        assertFalse(RootfsMigrationPolicy.isUbuntu24(null))
        assertTrue(RootfsMigrationPolicy.isUpgradeSource("ubuntu-20.04.5-arm64"))
        assertFalse(RootfsMigrationPolicy.isUpgradeSource("ubuntu-24.04.5-arm64"))
        assertFalse(RootfsMigrationPolicy.isUpgradeSource(null))
    }

    @Test
    fun `base labels are human readable and honest about unknown bases`() {
        assertEquals("Ubuntu 20.04.5 LTS", RootfsMigrationPolicy.baseLabel("ubuntu-20.04.5-arm64"))
        assertEquals("Ubuntu 24.04.5 LTS", RootfsMigrationPolicy.baseLabel("ubuntu-24.04.5-arm64"))
        assertEquals("Ubuntu (unrecognized build)", RootfsMigrationPolicy.baseLabel("whatever"))
        assertEquals("Ubuntu (unrecognized build)", RootfsMigrationPolicy.baseLabel(null))
    }

    @Test
    fun `bundle descriptor stays unpinned until release engineering provides a digest`() {
        // The upgrade must be invisible while the 24.04 Core bundle has no
        // pinned digest in this app build (fail closed, like agent releases).
        assertNull(RootfsMigrationPolicy.UBUNTU_24_BUNDLE)
    }

    // ---------------------------------------------------------------------
    // Storage floor
    // ---------------------------------------------------------------------

    @Test
    fun `storage floor covers archive extraction and headroom`() {
        val compressed = 100L * 1024 * 1024
        // compressed + 6x estimate + 64 MB headroom
        val expected = compressed + compressed * 6 + 64L * 1024 * 1024
        assertEquals(expected, RootfsMigrationPolicy.requiredFreeBytes(compressed))
    }

    @Test
    fun `uncompressed estimate is conservative versus the shipped 20-04 bundle`() {
        // The real 20.04 bundle compresses ~5.1x (369 MB from 72 MB); the
        // estimate must be at least that ratio so the floor is never low.
        val compressed = 72_185_773L
        val estimate = RootfsMigrationPolicy.estimatedUncompressedBytes(compressed)
        assertTrue(estimate >= compressed * 5)
        assertTrue(estimate < compressed * 7)
    }

    // ---------------------------------------------------------------------
    // Agent-state preservation allow-list
    // ---------------------------------------------------------------------

    @Test
    fun `agent state directories are preserved`() {
        listOf(
            "root/.claude",
            "root/.claude/projects/-workspace/session.jsonl",
            "root/.claude.json",
            "root/.dsh",
            "root/.dsh/sessions/abc.json",
            "root/.agy",
            "root/.agy/state.json",
            "root/.config",
            "root/.config/gh/hosts.yml",
            "root/.ssh",
            "root/.ssh/id_ed25519",
            "root/.gitconfig",
        ).forEach { path ->
            assertTrue("expected preserved: $path", RootfsMigrationPolicy.shouldPreservePath(path))
        }
    }

    @Test
    fun `toolchains caches and sdk directories are never preserved`() {
        listOf(
            "root/.gradle",
            "root/.gradle/caches/whatever",
            "root/android-sdk",
            "root/android-sdk/build-tools/35.0.0/aapt2",
            "root/maven",
            "root/maven/localMvnRepository",
            "opt/gradle/gradle-8.14.3/bin/gradle",
            "usr/local/lib/dsh",
            "usr/local/lib/dsh/node_modules/.bin/dsh",
            "usr/local/bin/claude",
            "root/.local/bin/agy",
            "root/.npm",
            "root/.cache",
            "etc/passwd",
            "var/lib/dpkg/status",
            "",
            "root",
        ).forEach { path ->
            assertFalse("expected NOT preserved: $path", RootfsMigrationPolicy.shouldPreservePath(path))
        }
    }

    @Test
    fun `prefix lookalikes and traversal attempts are rejected`() {
        // ".claudeEvil" shares the prefix but is neither the exact directory
        // nor a child of it — it must not ride the allow-list.
        assertFalse(RootfsMigrationPolicy.shouldPreservePath("root/.claudeEvil"))
        assertFalse(RootfsMigrationPolicy.shouldPreservePath("root/.claudeX/settings.json"))
        assertFalse(RootfsMigrationPolicy.shouldPreservePath("root/.configEvil/hosts.yml"))
        // Path normalization must not unlock anything either.
        assertFalse(RootfsMigrationPolicy.shouldPreservePath("/root/.gradle"))
        assertFalse(RootfsMigrationPolicy.shouldPreservePath("./root/android-sdk"))
        assertFalse(RootfsMigrationPolicy.shouldPreservePath("root\\android-sdk"))
    }

    // ---------------------------------------------------------------------
    // State codec
    // ---------------------------------------------------------------------

    @Test
    fun `state round-trips through the codec`() {
        val state = UbuntuMigrationState(
            phase = UbuntuMigrationPhase.AWAITING_FIRST_SESSION,
            fromMarker = "ubuntu-20.04.5-arm64",
            toMarker = "ubuntu-24.04.5-arm64",
            startedAtMillis = 1_758_000_000_000L,
            attempts = 2,
            lastError = "provisioning failed",
        )
        assertEquals(state, RootfsMigrationPolicy.decodeState(RootfsMigrationPolicy.encodeState(state)))
    }

    @Test
    fun `null blank and garbage state documents decode as null`() {
        assertNull(RootfsMigrationPolicy.decodeState(null))
        assertNull(RootfsMigrationPolicy.decodeState(""))
        assertNull(RootfsMigrationPolicy.decodeState("   "))
        assertNull(RootfsMigrationPolicy.decodeState("not json at all"))
        assertNull(RootfsMigrationPolicy.decodeState("{\"phase\":42}"))
    }

    @Test
    fun `a state without a valid phase is rejected`() {
        assertNull(RootfsMigrationPolicy.decodeState("""{"phase":"EXPLODING"}"""))
        // The phase field is mandatory: opt-out is not a valid state.
        assertNull(RootfsMigrationPolicy.decodeState("""{"fromMarker":"ubuntu-20.04.5-arm64"}"""))
    }

    @Test
    fun `future keys are ignored and optional fields default safely`() {
        val decoded = RootfsMigrationPolicy.decodeState(
            """{"phase":"IDLE","fromMarker":"x","futureField":{"nested":[1,2]},"lastError":""}""",
        )
        assertNotNull(decoded)
        assertEquals(UbuntuMigrationPhase.IDLE, decoded!!.phase)
        assertEquals(0, decoded.attempts)
        assertEquals(0L, decoded.startedAtMillis)
        assertNull(decoded.lastError)
    }

    // ---------------------------------------------------------------------
    // Crash classification
    // ---------------------------------------------------------------------

    @Test
    fun `crash between the two renames restores the rollback root`() {
        // Active tree missing, rollback present: the only safe move is
        // restoring 20.04, whatever staging still holds.
        assertEquals(
            MigrationRecovery.RESTORE_ROLLBACK,
            RootfsMigrationPolicy.recoveryAction(ubuntuMarker = null, rollbackExists = true, stagingExists = true),
        )
        assertEquals(
            MigrationRecovery.RESTORE_ROLLBACK,
            RootfsMigrationPolicy.recoveryAction(ubuntuMarker = null, rollbackExists = true, stagingExists = false),
        )
    }

    @Test
    fun `orphaned staging without any base is discarded`() {
        assertEquals(
            MigrationRecovery.DISCARD_STAGING,
            RootfsMigrationPolicy.recoveryAction(ubuntuMarker = null, rollbackExists = false, stagingExists = true),
        )
    }

    @Test
    fun `completed swap resumes as awaiting first session`() {
        // The swap finished but the state file was lost: 24.04 is active.
        assertEquals(
            MigrationRecovery.KEEP_AWAITING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-24.04.5-arm64", rollbackExists = true, stagingExists = false),
        )
        assertEquals(
            MigrationRecovery.KEEP_AWAITING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-24.04.5-arm64", rollbackExists = false, stagingExists = true),
        )
        assertEquals(
            MigrationRecovery.KEEP_AWAITING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-24.04.5-arm64", rollbackExists = false, stagingExists = false),
        )
    }

    @Test
    fun `crash before the swap discards staging on an untouched base`() {
        assertEquals(
            MigrationRecovery.DISCARD_STAGING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-20.04.5-arm64", rollbackExists = false, stagingExists = true),
        )
        // A clean 20.04 base with no leftovers needs no recovery at all.
        assertEquals(
            MigrationRecovery.NOTHING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-20.04.5-arm64", rollbackExists = false, stagingExists = false),
        )
        assertEquals(
            MigrationRecovery.NOTHING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-20.04.5-arm64", rollbackExists = true, stagingExists = false),
        )
    }

    @Test
    fun `unrecognized markers are left for the installer repair path`() {
        assertEquals(
            MigrationRecovery.NOTHING,
            RootfsMigrationPolicy.recoveryAction("ubuntu-18.04-arm64", rollbackExists = false, stagingExists = true),
        )
    }

    // ---------------------------------------------------------------------
    // Upgrade availability
    // ---------------------------------------------------------------------

    @Test
    fun `upgrade is offered only for an idle or rolled-back 20-04 online device`() {
        val offered = setOf(UbuntuMigrationPhase.IDLE, UbuntuMigrationPhase.ROLLED_BACK)
        offered.forEach { phase ->
            assertTrue(
                RootfsMigrationPolicy.canOfferUpgrade(phase, "ubuntu-20.04.5-arm64", bundleAvailable = true, offlineBuild = false),
            )
        }
    }

    @Test
    fun `upgrade is never offered mid-flight after commitment or on 24-04`() {
        listOf(
            UbuntuMigrationPhase.RUNNING,
            UbuntuMigrationPhase.AWAITING_FIRST_SESSION,
            UbuntuMigrationPhase.DONE,
        ).forEach { phase ->
            assertFalse(
                RootfsMigrationPolicy.canOfferUpgrade(phase, "ubuntu-20.04.5-arm64", bundleAvailable = true, offlineBuild = false),
            )
        }
        assertFalse(
            RootfsMigrationPolicy.canOfferUpgrade(UbuntuMigrationPhase.IDLE, "ubuntu-24.04.5-arm64", bundleAvailable = true, offlineBuild = false),
        )
        assertFalse(
            RootfsMigrationPolicy.canOfferUpgrade(UbuntuMigrationPhase.IDLE, null, bundleAvailable = true, offlineBuild = false),
        )
    }

    @Test
    fun `upgrade is hidden without a pinned bundle and in offline builds`() {
        assertFalse(
            RootfsMigrationPolicy.canOfferUpgrade(UbuntuMigrationPhase.IDLE, "ubuntu-20.04.5-arm64", bundleAvailable = false, offlineBuild = false),
        )
        assertFalse(
            RootfsMigrationPolicy.canOfferUpgrade(UbuntuMigrationPhase.IDLE, "ubuntu-20.04.5-arm64", bundleAvailable = true, offlineBuild = true),
        )
    }
}
