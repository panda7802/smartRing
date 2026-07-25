package com.zx.smartring.auth

import org.json.JSONObject
import com.zx.smartring.network.SmartRingApiException
import com.zx.smartring.network.SmartRingHttpClient
import java.net.HttpURLConnection

object AuthApi {
    fun register(name: String, password: String) {
        val body = JSONObject().put("name", name).put("passwd", password)
        try {
            SmartRingHttpClient.post("/regist", body)
        } catch (error: SmartRingApiException) {
            if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND) throw error
            SmartRingHttpClient.post("/register", body)
        }
    }

    fun login(name: String, password: String): UserSession {
        val response = SmartRingHttpClient.post(
            "/login",
            JSONObject().put("name", name).put("passwd", password)
        )
        val token = response.optString("token").trim()
        if (token.isEmpty()) {
            throw SmartRingApiException(500, "INVALID_RESPONSE", "登录响应缺少 token")
        }
        return UserSession(name, token)
    }
}
