package com.zx.smartring.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderTimeTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun schedulesLaterTimeOnSameDay() {
        val now = millis(2026, Calendar.JULY, 23, 5, 29)
        val expected = millis(2026, Calendar.JULY, 23, 5, 30)

        assertEquals(expected, ReminderTime.nextOccurrenceMillis(5 * 60 + 30, now, zone))
    }

    @Test
    fun schedulesTomorrowWhenTimeAlreadyPassed() {
        val now = millis(2026, Calendar.JULY, 23, 5, 31)
        val expected = millis(2026, Calendar.JULY, 24, 5, 30)

        assertEquals(expected, ReminderTime.nextOccurrenceMillis(5 * 60 + 30, now, zone))
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month, day, hour, minute)
    }.timeInMillis
}
