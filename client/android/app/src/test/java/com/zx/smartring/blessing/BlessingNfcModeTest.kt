package com.zx.smartring.blessing

import org.junit.Assert.assertEquals
import org.junit.Test

class BlessingNfcModeTest {
    @Test
    fun readsOnlyWhenWriteModeIsInactive() {
        assertEquals(BlessingNfcAction.READ, BlessingNfcMode.action(false, false))
        assertEquals(BlessingNfcAction.READ, BlessingNfcMode.action(false, true))
    }

    @Test
    fun writeModeNeverFallsThroughToRead() {
        assertEquals(BlessingNfcAction.IGNORE, BlessingNfcMode.action(true, false))
        assertEquals(BlessingNfcAction.WRITE, BlessingNfcMode.action(true, true))
    }
}
