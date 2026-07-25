package com.zx.smartring.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sq666ProtocolTest {
    @Test
    fun buildsDocumentedResetFrame() {
        val expected = hex(
            "FE FC 38 00 01 00 01 00 0D 00 " +
                "01 01 00 64 00 00 00 00 00 21 00 00 00"
        )

        assertArrayEquals(expected, Sq666Protocol.buildResetCountFrame())
    }

    @Test
    fun parsesCountReport() {
        val parser = Sq666FrameStreamParser()
        val frame = parser.append(
            hex(
                "FE FC 33 00 01 00 01 00 0B 00 " +
                    "B4 CD 63 6A 00 00 02 00 B4 CD 00"
            )
        ).single()

        val report = Sq666Protocol.parseCountReport(frame)!!

        assertEquals(0x6A63CDB4L, report.deviceTimestamp)
        assertEquals(2, report.currentCount)
    }

    @Test
    fun handlesFragmentedAndCombinedFrames() {
        val first = Sq666Protocol.buildFrame(
            Sq666Protocol.COMMAND_COUNT_REPORT,
            hex("01 00 00 00 00 00 03 00 00 00 00")
        )
        val second = Sq666Protocol.buildResetCountFrame()
        val parser = Sq666FrameStreamParser()

        assertTrue(parser.append(first.copyOfRange(0, 7)).isEmpty())
        val frames = parser.append(first.copyOfRange(7, first.size) + second)

        assertEquals(2, frames.size)
        assertEquals(3, Sq666Protocol.parseCountReport(frames[0])?.currentCount)
        assertEquals(0L, Sq666Protocol.parseAcknowledgedCount(frames[1]))
    }

    @Test
    fun resynchronizesAfterInvalidBytes() {
        val valid = Sq666Protocol.buildResetCountFrame()
        val parser = Sq666FrameStreamParser()

        val frames = parser.append(hex("00 12 FE 11 7F") + valid)

        assertEquals(1, frames.size)
        assertEquals(Sq666Protocol.COMMAND_ACTIVATE_TASK, frames.single().command)
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
