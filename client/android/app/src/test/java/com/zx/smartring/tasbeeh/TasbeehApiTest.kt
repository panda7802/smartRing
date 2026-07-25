package com.zx.smartring.tasbeeh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TasbeehApiTest {
    @Test
    fun parsesStringAndNumericCounts() {
        assertEquals(
            DailyTasbeehRecord(LocalDate.of(2026, 7, 25), 10),
            TasbeehApi.parseDailyRecord("20260725", "10")
        )
        assertEquals(
            DailyTasbeehRecord(LocalDate.of(2026, 7, 24), 25),
            TasbeehApi.parseDailyRecord("20260724", 25)
        )
    }

    @Test
    fun rejectsInvalidDateAndNegativeCount() {
        assertNull(TasbeehApi.parseDailyRecord("bad-date", "8"))
        assertNull(TasbeehApi.parseDailyRecord("20260723", "-1"))
    }
}
