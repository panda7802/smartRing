package com.zx.smartring.settings

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar
import java.util.TimeZone

data class RecitationWindow(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int
) {
    init {
        require(startMinuteOfDay in MINUTE_RANGE)
        require(endMinuteOfDay in MINUTE_RANGE)
        require(startMinuteOfDay != endMinuteOfDay)
    }

    private companion object {
        val MINUTE_RANGE = 0 until 24 * 60
    }
}

data class AppSettings(
    val dailyReminderEnabled: Boolean,
    val recitationWindow: RecitationWindow?,
    val screenTimeoutSeconds: Int
)

object AppSettingsStore {
    private const val PREFERENCES_NAME = "smart_ring_preferences"
    private const val KEY_DAILY_REMINDER = "daily_reminder_enabled"
    private const val KEY_RECITATION_START = "recitation_start_minute"
    private const val KEY_RECITATION_END = "recitation_end_minute"
    private const val KEY_SCREEN_TIMEOUT = "screen_timeout_seconds"
    private const val UNSET_MINUTE = -1
    private const val DEFAULT_SCREEN_TIMEOUT_SECONDS = 10
    val SCREEN_TIMEOUT_OPTIONS = intArrayOf(10, 20, 30)

    fun get(context: Context): AppSettings {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val start = preferences.getInt(KEY_RECITATION_START, UNSET_MINUTE)
        val end = preferences.getInt(KEY_RECITATION_END, UNSET_MINUTE)
        val window = runCatching { RecitationWindow(start, end) }.getOrNull()
        val storedTimeout = preferences.getInt(
            KEY_SCREEN_TIMEOUT,
            DEFAULT_SCREEN_TIMEOUT_SECONDS
        )
        return AppSettings(
            dailyReminderEnabled = preferences.getBoolean(KEY_DAILY_REMINDER, false),
            recitationWindow = window,
            screenTimeoutSeconds = storedTimeout.takeIf {
                it in SCREEN_TIMEOUT_OPTIONS
            } ?: DEFAULT_SCREEN_TIMEOUT_SECONDS
        )
    }

    fun setDailyReminderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DAILY_REMINDER, enabled) }
    }

    fun setRecitationWindow(context: Context, window: RecitationWindow?) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_RECITATION_START, window?.startMinuteOfDay ?: UNSET_MINUTE)
                putInt(KEY_RECITATION_END, window?.endMinuteOfDay ?: UNSET_MINUTE)
            }
    }

    fun setScreenTimeoutSeconds(context: Context, seconds: Int) {
        require(seconds in SCREEN_TIMEOUT_OPTIONS)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_SCREEN_TIMEOUT, seconds) }
    }
}

object ReminderTime {
    fun nextOccurrenceMillis(
        minuteOfDay: Int,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        require(minuteOfDay in 0 until 24 * 60)
        val next = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis
    }
}
