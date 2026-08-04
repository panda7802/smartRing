package com.zx.smartring.chat.network

import com.zx.smartring.chat.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object DeepSeekChatApi {
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-v4-flash"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_CONTEXT_MESSAGES = 20

    private const val SYSTEM_PROMPT =
        "你是一个谨慎、尊重、知识严谨的伊斯兰信仰问答与反思助手。" +
            "你不是并且绝不能自称、扮演或模拟真主、先知、天使或任何神圣启示；" +
            "不得用“真主直接对你说”等方式呈现生成内容。" +
            "只回答与伊斯兰信仰、《古兰经》、圣训、历史、礼拜、伦理和穆斯林日常生活有关的问题；" +
            "用户询问无关内容时，温和地引导回主题。" +
            "使用用户的语言回答，区分公认事实、学派差异与一般建议；" +
            "不要虚构经文编号、圣训来源或教法结论，不确定时明确说明。" +
            "涉及教法裁决、医疗、法律、财务或安全时，说明内容仅供参考，" +
            "并建议咨询可信赖的当地合格学者或相关专业人士。语气庄重、温和、清晰、简洁。"

    @Throws(IOException::class, DeepSeekApiException::class)
    fun complete(apiKey: String, history: List<ChatMessage>): String {
        if (apiKey.isBlank()) throw MissingDeepSeekKeyException()

        val requestMessages = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put("content", SYSTEM_PROMPT)
            )
            history.takeLast(MAX_CONTEXT_MESSAGES).forEach { message ->
                put(
                    JSONObject()
                        .put("role", message.role.apiValue)
                        .put("content", message.content)
                )
            }
        }
        val requestBody = JSONObject()
            .put("model", MODEL)
            .put("messages", requestMessages)
            .put("stream", false)
            .put("max_tokens", 1200)

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { stream ->
                stream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val responseText = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (statusCode !in 200..299) {
                throw DeepSeekApiException(statusCode, extractErrorMessage(responseText))
            }

            val content = JSONObject(responseText)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (content.isBlank()) throw EmptyDeepSeekResponseException()
            content
        } finally {
            connection.disconnect()
        }
    }

    private fun extractErrorMessage(responseText: String): String? {
        if (responseText.isBlank()) return null
        return runCatching {
            JSONObject(responseText)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }
}

class DeepSeekApiException(
    val statusCode: Int,
    message: String?
) : IOException(message ?: "DeepSeek request failed with HTTP $statusCode")

class MissingDeepSeekKeyException : IllegalStateException("DeepSeek API key is not configured")

class EmptyDeepSeekResponseException : IOException("DeepSeek returned an empty response")
