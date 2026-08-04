package com.zx.smartring.chat.model

enum class ChatRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant")
}

data class ChatMessage(
    val role: ChatRole,
    val content: String
)
