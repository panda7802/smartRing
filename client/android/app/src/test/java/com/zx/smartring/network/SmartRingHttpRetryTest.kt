package com.zx.smartring.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketTimeoutException

class SmartRingHttpRetryTest {
    @Test
    fun retriesTransientIoUntilRequestSucceeds() {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = retryTransientIo(
            maxAttempts = 4,
            retryDelaysMs = longArrayOf(10, 20, 30),
            sleeper = delays::add
        ) {
            attempts += 1
            if (attempts < 3) throw SocketTimeoutException("connect timed out")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), delays)
    }

    @Test
    fun doesNotRetryHttpApplicationErrors() {
        var attempts = 0

        assertThrows(SmartRingApiException::class.java) {
            retryTransientIo(
                maxAttempts = 4,
                retryDelaysMs = longArrayOf(0),
                sleeper = {}
            ) {
                attempts += 1
                throw SmartRingApiException(401, "UNAUTHORIZED", "expired")
            }
        }

        assertEquals(1, attempts)
    }
}
