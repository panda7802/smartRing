package com.zx.smartring.chat.network

import com.zx.smartring.chat.model.ChatMessage
import com.zx.smartring.chat.model.ChatRole
import com.zx.smartring.network.SmartRingHttpClient
import org.json.JSONObject

object FaithChatCloudApi {
    fun history(token: String): List<ChatMessage> {
        val response = SmartRingHttpClient.get("/faith-chat/history", token)
        val messages = response.optJSONArray("messages") ?: return emptyList()
        return buildList {
            for (index in 0 until messages.length()) {
                val item = messages.optJSONObject(index) ?: continue
                val content = item.optString("content").trim()
                val role = when (item.optString("role")) {
                    ChatRole.USER.apiValue -> ChatRole.USER
                    ChatRole.ASSISTANT.apiValue -> ChatRole.ASSISTANT
                    else -> continue
                }
                if (content.isNotEmpty()) add(ChatMessage(role, content))
            }
        }
    }

    fun saveExchange(token: String, userMessage: String, assistantMessage: String) {
        SmartRingHttpClient.post(
            "/faith-chat/exchange",
            JSONObject()
                .put("user", userMessage)
                .put("assistant", assistantMessage),
            token
        )
    }
}
