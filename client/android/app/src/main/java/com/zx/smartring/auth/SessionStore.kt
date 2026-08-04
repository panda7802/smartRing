package com.zx.smartring.auth

import android.content.Context

data class UserSession(val name: String, val token: String, val userId: Long? = null)

object SessionStore {
    private const val PREFERENCES_NAME = "smart_ring_auth"
    private const val KEY_NAME = "name"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"

    fun get(context: Context): UserSession? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val name = preferences.getString(KEY_NAME, null).orEmpty()
        val token = preferences.getString(KEY_TOKEN, null).orEmpty()
        val userId = preferences.getLong(KEY_USER_ID, 0L).takeIf { it > 0L }
        return if (name.isNotBlank() && token.isNotBlank()) {
            UserSession(name, token, userId)
        } else {
            null
        }
    }

    fun save(context: Context, session: UserSession) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, session.name)
            .putString(KEY_TOKEN, session.token)
            .apply {
                if (session.userId == null) remove(KEY_USER_ID)
                else putLong(KEY_USER_ID, session.userId)
            }
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
