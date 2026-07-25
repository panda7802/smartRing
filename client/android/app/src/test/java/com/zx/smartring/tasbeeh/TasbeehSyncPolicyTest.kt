package com.zx.smartring.tasbeeh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasbeehSyncPolicyTest {
    @Test
    fun uploadsZeroDuringNormalSynchronization() {
        assertTrue(TasbeehSyncPolicy.shouldUpload(0, postResetSyncPending = false))
    }

    @Test
    fun skipsResetGeneratedZeroBeforeFirstAccumulatingSync() {
        assertFalse(TasbeehSyncPolicy.shouldUpload(0, postResetSyncPending = true))
    }

    @Test
    fun uploadsFirstPositiveCountAfterReset() {
        assertTrue(TasbeehSyncPolicy.shouldUpload(1, postResetSyncPending = true))
    }
}
