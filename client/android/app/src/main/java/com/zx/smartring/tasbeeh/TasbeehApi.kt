package com.zx.smartring.tasbeeh

import com.zx.smartring.network.SmartRingHttpClient
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class TasbeehSyncResult(
    val date: String,
    val count: String,
    val reset: Boolean
)

data class DailyTasbeehRecord(
    val date: LocalDate,
    val count: Long
)

object TasbeehApi {
    fun markReset(token: String) {
        SmartRingHttpClient.post("/tasbeeh/reset", JSONObject(), token)
    }

    fun syncCount(token: String, count: Int): TasbeehSyncResult {
        require(count >= 0)
        val response = SmartRingHttpClient.post(
            "/tasbeeh/sync",
            JSONObject().put("count", count),
            token
        )
        return TasbeehSyncResult(
            date = response.optString("date"),
            count = response.optString("count"),
            reset = response.optBoolean("reset")
        )
    }

    fun dailyCounts(token: String): List<DailyTasbeehRecord> =
        parseDailyCounts(SmartRingHttpClient.get("/tasbeeh/daily", token))

    internal fun parseDailyCounts(response: JSONObject): List<DailyTasbeehRecord> {
        val records = response.optJSONArray("all") ?: return emptyList()
        return buildList {
            for (index in 0 until records.length()) {
                val item = records.optJSONObject(index) ?: continue
                parseDailyRecord(item.optString("date"), item.opt("count"))?.let(::add)
            }
        }
    }

    internal fun parseDailyRecord(dateValue: String, countValue: Any?): DailyTasbeehRecord? {
        val date = runCatching {
            LocalDate.parse(dateValue, DateTimeFormatter.BASIC_ISO_DATE)
        }.getOrNull() ?: return null
        val count = when (countValue) {
            is Number -> countValue.toLong()
            is String -> countValue.toLongOrNull()
            else -> null
        }?.takeIf { it >= 0 } ?: return null
        return DailyTasbeehRecord(date, count)
    }
}
