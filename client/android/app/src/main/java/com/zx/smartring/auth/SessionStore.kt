package com.zx.smartring.auth

import android.content.Context

data class UserSession(val name: String, val token: String)

object SessionStore {
    private const val PREFERENCES_NAME = "smart_ring_auth"
    private const val KEY_NAME = "name"
    private const val KEY_TOKEN = "token"

    fun get(context: Context): UserSession? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val name = preferences.getString(KEY_NAME, null).orEmpty()
        val token = preferences.getString(KEY_TOKEN, null).orEmpty()
        return if (name.isNotBlank() && token.isNotBlank()) UserSession(name, token) else null
    }

    fun save(context: Context, session: UserSession) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, session.name)
            .putString(KEY_TOKEN, session.token)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
