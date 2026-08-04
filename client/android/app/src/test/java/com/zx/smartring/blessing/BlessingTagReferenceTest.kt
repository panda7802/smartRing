package com.zx.smartring.blessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlessingTagReferenceTest {
    @Test
    fun acceptsUuidOnlyNdefPayload() {
        val uuid = "2f540f07-9d13-4f55-a564-3d2038f1ca72"
        assertEquals(uuid, BlessingTagReference.fromNdefPayload(uuid)?.blessingId)
    }

    @Test
    fun rejectsUnsafeOrOversizedReferences() {
        assertNull(BlessingTagReference.fromNdefPayload(""))
        assertNull(BlessingTagReference.fromNdefPayload("uuid/with/path"))
        assertNull(BlessingTagReference.fromNdefPayload("a".repeat(65)))
    }
}
