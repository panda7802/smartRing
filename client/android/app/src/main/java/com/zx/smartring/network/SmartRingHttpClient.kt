package com.zx.smartring.network

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SmartRingApiException(
    val statusCode: Int,
    val code: String,
    override val message: String
) : IOException(message)

object SmartRingHttpClient {
    private const val BASE_URL = "https://www.panzhenghao.cn/smartRing"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return execute(connection) {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
        }
    }

    fun get(path: String, token: String? = null): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return execute(connection)
    }

    private fun execute(
        connection: HttpURLConnection,
        beforeResponse: () -> Unit = {}
    ): JSONObject {
        return try {
            beforeResponse()
            val status = connection.responseCode
            val responseText = (if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                throw SmartRingApiException(
                    status,
                    response.optString("code", "HTTP_$status"),
                    response.optString("message", "HTTP $status")
                )
            }
            response
        } finally {
            connection.disconnect()
        }
    }
}
