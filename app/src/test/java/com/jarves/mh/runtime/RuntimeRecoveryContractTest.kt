package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeRecoveryContractTest {

    @Test
    fun deepSeekHarnessCannotResumePersistedButReportsToolActivity() {
        assertEquals(false, DSH_RECOVERY_PROFILE.canResumePersisted)
        assertEquals(true, DSH_RECOVERY_PROFILE.reportsToolActivity)
    }

    @Test
    fun claudeReportsToolActivity() {
        assertEquals(true, CLAUDE_RECOVERY_PROFILE.reportsToolActivity)
    }

    @Test
    fun antigravityCanResumePersisted() {
        assertEquals(true, ANTIGRAVITY_RECOVERY_PROFILE.canResumePersisted)
    }
}
