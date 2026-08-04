package com.zx.smartring.network

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

class SmartRingApiException(
    val statusCode: Int,
    val code: String,
    override val message: String
) : IOException(message)

object SmartRingHttpClient {
    private const val BASE_URL = "https://www.panzhenghao.cn/smartRing"
    private const val GET_CONNECT_TIMEOUT_MS = 8_000
    private const val POST_CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val GET_MAX_ATTEMPTS = 4
    private val GET_RETRY_DELAYS_MS = longArrayOf(250, 750, 1_500)

    fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = POST_CONNECT_TIMEOUT_MS
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
        return retryTransientIo(GET_MAX_ATTEMPTS, GET_RETRY_DELAYS_MS) {
            val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = GET_CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            execute(connection)
        }
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

internal fun <T> retryTransientIo(
    maxAttempts: Int,
    retryDelaysMs: LongArray,
    sleeper: (Long) -> Unit = Thread::sleep,
    operation: () -> T
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    for (attempt in 0 until maxAttempts) {
        try {
            return operation()
        } catch (error: IOException) {
            if (error is SmartRingApiException || attempt == maxAttempts - 1) throw error
            val delayMs = retryDelaysMs.getOrElse(attempt) { retryDelaysMs.lastOrNull() ?: 0L }
            try {
                sleeper(delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("HTTP retry interrupted").apply {
                    initCause(interrupted)
                }
            }
        }
    }
    error("unreachable")
}
