package com.zx.smartring.blessing

import org.json.JSONObject

data class BlessingPayload(
    val blessingId: String,
    val senderUserId: Long,
    val nickname: String,
    val message: String,
    val packageName: String,
    val createdAt: String
) {
    companion object {
        const val VERSION = 1
        const val MIME_TYPE = "application/vnd.com.zx.smartring.blessing"
        const val MAX_NICKNAME_LENGTH = 40
        const val MAX_MESSAGE_LENGTH = 280

        fun fromJson(text: String): BlessingPayload? = runCatching {
            val value = JSONObject(text)
            if (value.optInt("v", value.optInt("version")) != VERSION) return null
            val blessingId = value.optString("i", value.optString("blessingId")).trim()
            val senderUserId = value.optLong("u", value.optLong("senderUserId"))
            val nickname = value.optString("n", value.optString("nickname")).trim()
            val message = value.optString("m", value.optString("message")).trim()
            val packageName = value.optString("p", value.optString("packageName")).trim()
            val createdAt = value.optString("t", value.optString("createdAt")).trim()
            if (
                blessingId.isEmpty() || blessingId.length > 64 || senderUserId <= 0L ||
                nickname.isEmpty() || nickname.length > MAX_NICKNAME_LENGTH ||
                message.isEmpty() || message.length > MAX_MESSAGE_LENGTH ||
                packageName.isEmpty() || packageName.length > 255
            ) {
                return null
            }
            BlessingPayload(
                blessingId,
                senderUserId,
                nickname,
                message,
                packageName,
                createdAt
            )
        }.getOrNull()
    }
}

data class BlessingTagReference(
    val blessingId: String,
    val legacyPayload: BlessingPayload? = null
) {
    companion object {
        fun fromNdefPayload(text: String): BlessingTagReference? {
            val value = text.trim()
            if (value.startsWith("{")) {
                val legacy = BlessingPayload.fromJson(value) ?: return null
                return BlessingTagReference(legacy.blessingId, legacy)
            }
            if (
                value.isEmpty() || value.length > 64 ||
                value.any { !it.isLetterOrDigit() && it != '-' && it != '_' }
            ) {
                return null
            }
            return BlessingTagReference(value)
        }
    }
}

data class BlessingHistoryItem(
    val eventId: Long,
    val blessingId: String,
    val nickname: String,
    val message: String,
    val senderUserId: Long,
    val senderName: String,
    val recipientUserId: Long,
    val recipientName: String,
    val createdAt: String,
    val isSelf: Boolean
)

data class BlessingHistory(
    val sent: List<BlessingHistoryItem>,
    val received: List<BlessingHistoryItem>
)
