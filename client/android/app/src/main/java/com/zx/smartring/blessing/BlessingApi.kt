package com.zx.smartring.blessing

import com.zx.smartring.network.SmartRingApiException
import com.zx.smartring.network.SmartRingHttpClient
import org.json.JSONArray
import org.json.JSONObject

data class BlessingReceiveResult(val duplicate: Boolean, val isSelf: Boolean)
data class AccountIdentity(val userId: Long, val name: String)

object BlessingApi {
    fun tag(blessingId: String): BlessingPayload {
        val response = SmartRingHttpClient.get("/blessings/tags/$blessingId")
        return parsePayload(response)
    }

    fun account(token: String): AccountIdentity {
        val response = SmartRingHttpClient.get("/me", token)
        val userId = response.optLong("userId")
        val name = response.optString("name").trim()
        if (userId <= 0L || name.isEmpty()) invalidResponse("账户信息不完整")
        return AccountIdentity(userId, name)
    }

    fun createTag(
        token: String,
        nickname: String,
        message: String,
        packageName: String
    ): BlessingPayload {
        val response = SmartRingHttpClient.post(
            "/blessings/tags",
            JSONObject()
                .put("nickname", nickname)
                .put("message", message)
                .put("packageName", packageName),
            token
        )
        return parsePayload(response)
    }

    private fun parsePayload(response: JSONObject): BlessingPayload =
        BlessingPayload(
            blessingId = response.optString("blessingId").trim(),
            senderUserId = response.optLong("senderUserId"),
            nickname = response.optString("nickname").trim(),
            message = response.optString("message").trim(),
            packageName = response.optString("packageName").trim(),
            createdAt = response.optString("createdAt").trim()
        ).also {
            if (
                it.blessingId.isEmpty() || it.senderUserId <= 0L ||
                it.nickname.isEmpty() || it.message.isEmpty() ||
                it.packageName.isEmpty() || it.createdAt.isEmpty()
            ) {
                invalidResponse("祈福贴纸响应不完整")
            }
        }

    fun receive(token: String, blessingId: String, eventId: String): BlessingReceiveResult {
        val response = SmartRingHttpClient.post(
            "/blessings/receive",
            JSONObject().put("blessingId", blessingId).put("eventId", eventId),
            token
        )
        val event = response.optJSONObject("event") ?: invalidResponse("祈福记录响应不完整")
        return BlessingReceiveResult(
            duplicate = response.optBoolean("duplicate"),
            isSelf = event.optBoolean("isSelf")
        )
    }

    fun history(token: String): BlessingHistory {
        val response = SmartRingHttpClient.get("/blessings", token)
        return BlessingHistory(
            sent = parseHistory(response.optJSONArray("sent") ?: JSONArray()),
            received = parseHistory(response.optJSONArray("received") ?: JSONArray())
        )
    }

    private fun parseHistory(values: JSONArray): List<BlessingHistoryItem> =
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                add(
                    BlessingHistoryItem(
                        eventId = value.optLong("eventId"),
                        blessingId = value.optString("blessingId"),
                        nickname = value.optString("nickname"),
                        message = value.optString("message"),
                        senderUserId = value.optLong("senderUserId"),
                        senderName = value.optString("senderName"),
                        recipientUserId = value.optLong("recipientUserId"),
                        recipientName = value.optString("recipientName"),
                        createdAt = value.optString("createdAt"),
                        isSelf = value.optBoolean("isSelf")
                    )
                )
            }
        }

    private fun invalidResponse(message: String): Nothing =
        throw SmartRingApiException(500, "INVALID_RESPONSE", message)
}
