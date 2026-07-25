package com.zx.smartring.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Sq666Frame(
    val command: Int,
    val payload: ByteArray
)

data class Sq666CountReport(
    val deviceTimestamp: Long,
    val currentCount: Int
)

object Sq666Protocol {
    const val COMMAND_COUNT_REPORT = 0x0033
    const val COMMAND_ACTIVATE_TASK = 0x0038
    private const val HEADER_SIZE = 10

    fun buildResetCountFrame(): ByteArray {
        val payload = byteArrayOf(
            0x01,
            0x01, 0x00,
            0x64, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x21, 0x00, 0x00, 0x00
        )
        return buildFrame(COMMAND_ACTIVATE_TASK, payload)
    }

    fun buildFrame(command: Int, payload: ByteArray): ByteArray {
        require(command in 0..0xffff)
        require(payload.size <= 0xffff)
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0xfe.toByte())
            .put(0xfc.toByte())
            .putShort(command.toShort())
            .putShort(1)
            .putShort(1)
            .putShort(payload.size.toShort())
            .put(payload)
            .array()
    }

    fun parseCountReport(frame: Sq666Frame): Sq666CountReport? {
        if (frame.command != COMMAND_COUNT_REPORT || frame.payload.size < 8) return null
        return Sq666CountReport(
            deviceTimestamp = u32le(frame.payload, 0),
            currentCount = u16le(frame.payload, 6)
        )
    }

    fun parseAcknowledgedCount(frame: Sq666Frame): Long? {
        if (frame.command != COMMAND_ACTIVATE_TASK || frame.payload.size < 9) return null
        return u32le(frame.payload, 5)
    }

    fun u16le(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size)
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    fun u32le(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < data.size)
        return (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
    }
}

class Sq666FrameStreamParser(
    private val maximumPayloadLength: Int = 4_096
) {
    private var buffered = ByteArray(0)

    fun append(chunk: ByteArray): List<Sq666Frame> {
        if (chunk.isEmpty()) return emptyList()
        buffered += chunk
        val frames = mutableListOf<Sq666Frame>()
        while (true) {
            val magicIndex = findMagic(buffered)
            if (magicIndex < 0) {
                buffered = if (buffered.lastOrNull() == 0xfe.toByte()) {
                    byteArrayOf(0xfe.toByte())
                } else {
                    ByteArray(0)
                }
                break
            }
            if (magicIndex > 0) buffered = buffered.copyOfRange(magicIndex, buffered.size)
            if (buffered.size < HEADER_SIZE) break

            val payloadLength = Sq666Protocol.u16le(buffered, 8)
            if (payloadLength > maximumPayloadLength) {
                buffered = buffered.copyOfRange(1, buffered.size)
                continue
            }
            val frameLength = HEADER_SIZE + payloadLength
            if (buffered.size < frameLength) break

            frames += Sq666Frame(
                command = Sq666Protocol.u16le(buffered, 2),
                payload = buffered.copyOfRange(HEADER_SIZE, frameLength)
            )
            buffered = buffered.copyOfRange(frameLength, buffered.size)
        }
        return frames
    }

    fun reset() {
        buffered = ByteArray(0)
    }

    private fun findMagic(data: ByteArray): Int {
        for (index in 0 until data.lastIndex) {
            if (data[index] == 0xfe.toByte() && data[index + 1] == 0xfc.toByte()) {
                return index
            }
        }
        return -1
    }

    private companion object {
        const val HEADER_SIZE = 10
    }
}
