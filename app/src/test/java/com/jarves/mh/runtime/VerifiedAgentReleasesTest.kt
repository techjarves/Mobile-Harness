package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-002 / ISSUE-008: agent updates may only install versions this app
 * build has pinned digests for. An endpoint that serves an unknown version or
 * a different checksum must fail closed.
 */
class VerifiedAgentReleasesTest {

    @Test
    fun `the bundled agy version has a pinned sha512 digest`() {
        val digest = VerifiedAgentReleases.agyDigest("1.1.27")
        assertNotNull(digest)
        // SHA-512 hex = 128 characters.
        assertEquals(128, digest!!.length)
        assertTrue(digest.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `an unknown agy version is not verified`() {
        assertNull(VerifiedAgentReleases.agyDigest("99.0.0-totally-new"))
        assertFalse(VerifiedAgentReleases.isVerifiedAgyRelease("99.0.0-totally-new"))
    }

    @Test
    fun `the bundled dsh version has a pinned npm tarball digest`() {
        val digest = VerifiedAgentReleases.dshDigest("0.1.2-rc.1")
        assertNotNull(digest)
        assertEquals(128, digest!!.length)
        assertTrue(digest.matches(Regex("[0-9a-f]+")))
        assertTrue(VerifiedAgentReleases.isVerifiedDshRelease("0.1.2-rc.1"))
    }

    @Test
    fun `an unknown dsh version is not verified`() {
        assertNull(VerifiedAgentReleases.dshDigest("0.2.0"))
        assertFalse(VerifiedAgentReleases.isVerifiedDshRelease("0.2.0"))
    }

    @Test
    fun `every pinned digest is a well formed sha512 hex string`() {
        (VerifiedAgentReleases.AGY_TARBALL_SHA512.values + VerifiedAgentReleases.DSH_TARBALL_SHA512.values)
            .forEach { digest ->
                assertEquals(128, digest.length)
                assertTrue(digest.matches(Regex("[0-9a-f]+")))
            }
    }
}
