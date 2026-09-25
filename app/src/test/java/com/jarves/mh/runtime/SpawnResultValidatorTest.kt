package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISSUE-029: every NULL/degenerate JNI spawn result must be recognized as a
 * launch failure so the bridge can raise a friendly error instead of crashing
 * with an NPE on `spawned.size`.
 */
class SpawnResultValidatorTest {

    @Test
    fun `a null result is a launch failure`() {
        assertNull(SpawnResultValidator.validate(null))
    }

    @Test
    fun `short arrays are launch failures`() {
        assertNull(SpawnResultValidator.validate(intArrayOf()))
        assertNull(SpawnResultValidator.validate(intArrayOf(1)))
        assertNull(SpawnResultValidator.validate(intArrayOf(1, 2)))
        assertNull(SpawnResultValidator.validate(intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `a non-positive pid is a launch failure`() {
        assertNull(SpawnResultValidator.validate(intArrayOf(0, 5, 6)))
        assertNull(SpawnResultValidator.validate(intArrayOf(-1, 5, 6)))
    }

    @Test
    fun `a healthy result validates with pid and descriptors`() {
        val valid = SpawnResultValidator.validate(intArrayOf(4242, 10, 12))!!
        assertEquals(4242, valid.pid)
        assertEquals(10, valid.inputFd)
        assertEquals(12, valid.outputFd)
    }

    @Test
    fun `a negative output fd stays valid - it only means no pty pump`() {
        val valid = SpawnResultValidator.validate(intArrayOf(99, 7, -1))!!
        assertEquals(99, valid.pid)
        assertEquals(-1, valid.outputFd)
    }
}
