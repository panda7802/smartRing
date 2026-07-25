package com.zx.smartring.tasbeeh

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zx.smartring.R
import com.zx.smartring.auth.SessionStore
import com.zx.smartring.network.SmartRingApiException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DailyTasbeehActivity : Activity() {
    private lateinit var monthTitle: TextView
    private lateinit var monthSummary: TextView
    private lateinit var selection: TextView
    private lateinit var status: TextView
    private lateinit var retryButton: TextView
    private lateinit var calendarGrid: GridLayout
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var selectedMonth: YearMonth = YearMonth.now(CHINA_ZONE)
    private var countsByDate: Map<LocalDate, Long> = emptyMap()

    override fun attachBaseContext(newBase: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            super.attachBaseContext(newBase)
            return
        }
        val languageTag = newBase.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(PREFERENCE_LANGUAGE, null)
        if (languageTag == null) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        setContentView(R.layout.activity_daily_tasbeeh)

        val root = findViewById<View>(R.id.daily_calendar_root)
        val content = findViewById<View>(R.id.daily_calendar_content)
        val statusBarScrim = findViewById<View>(R.id.daily_calendar_status_bar_scrim)
        monthTitle = findViewById(R.id.daily_calendar_month)
        monthSummary = findViewById(R.id.daily_calendar_summary)
        selection = findViewById(R.id.daily_calendar_selection)
        status = findViewById(R.id.daily_calendar_status)
        retryButton = findViewById(R.id.daily_calendar_retry)
        calendarGrid = findViewById(R.id.daily_calendar_grid)

        applyInsets(root, content, statusBarScrim)
        applySavedLayoutDirection(root)
        findViewById<View>(R.id.daily_calendar_back).setOnClickListener { finish() }
        findViewById<View>(R.id.daily_calendar_previous).setOnClickListener {
            selectedMonth = selectedMonth.minusMonths(1)
            renderCalendar()
        }
        findViewById<View>(R.id.daily_calendar_next).setOnClickListener {
            selectedMonth = selectedMonth.plusMonths(1)
            renderCalendar()
        }
        retryButton.setOnClickListener { loadDailyCounts() }

        if (SessionStore.get(this) == null) {
            Toast.makeText(this, R.string.daily_calendar_login_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        renderCalendar()
        loadDailyCounts()
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
    }

    private fun applyInsets(root: View, content: View, statusBarScrim: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply {
                height = insets.top
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySavedLayoutDirection(root: View) {
        val rightToLeft = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_RTL_LAYOUT, false)
        root.layoutDirection = if (rightToLeft) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun loadDailyCounts() {
        val session = SessionStore.get(this) ?: run {
            Toast.makeText(this, R.string.daily_calendar_login_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        status.visibility = View.VISIBLE
        status.setText(R.string.daily_calendar_loading)
        retryButton.visibility = View.GONE
        executor.execute {
            val result = runCatching { TasbeehApi.dailyCounts(session.token) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { records ->
                    countsByDate = records.associate { it.date to it.count }
                    status.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                    if (records.isEmpty()) status.setText(R.string.daily_calendar_empty)
                    retryButton.visibility = View.GONE
                    renderCalendar()
                }.onFailure(::handleLoadFailure)
            }
        }
    }

    private fun handleLoadFailure(error: Throwable) {
        if (error is SmartRingApiException && error.statusCode == 401) {
            SessionStore.clear(this)
            Toast.makeText(this, R.string.auth_session_expired, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        status.visibility = View.VISIBLE
        status.setText(R.string.daily_calendar_load_failed)
        retryButton.visibility = View.VISIBLE
    }

    private fun renderCalendar() {
        monthTitle.text = getString(
            R.string.daily_calendar_month_format,
            selectedMonth.year,
            selectedMonth.monthValue
        )
        val monthTotal = countsByDate
            .filterKeys { YearMonth.from(it) == selectedMonth }
            .values
            .sum()
        monthSummary.text = getString(R.string.daily_calendar_month_total, monthTotal)
        selection.setText(R.string.daily_calendar_select_hint)
        calendarGrid.removeAllViews()

        val firstDay = selectedMonth.atDay(1)
        repeat(firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value) { index ->
            addCalendarPlaceholder(index)
        }
        for (day in 1..selectedMonth.lengthOfMonth()) {
            val date = selectedMonth.atDay(day)
            val count = countsByDate[date] ?: 0L
            addCalendarDay(date, count)
        }
    }

    private fun addCalendarPlaceholder(index: Int) {
        val placeholder = View(this).apply { visibility = View.INVISIBLE }
        calendarGrid.addView(placeholder, dayCellLayoutParams(index))
    }

    private fun addCalendarDay(date: LocalDate, count: Long) {
        val today = LocalDate.now(CHINA_ZONE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(5))
            setBackgroundResource(
                if (date == today) R.drawable.bg_calendar_today
                else R.drawable.bg_calendar_day
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.daily_calendar_day_description,
                date.year,
                date.monthValue,
                date.dayOfMonth,
                count
            )
            setOnClickListener {
                selection.text = getString(
                    R.string.daily_calendar_selection,
                    date.year,
                    date.monthValue,
                    date.dayOfMonth,
                    count
                )
            }
        }
        container.addView(TextView(this).apply {
            text = date.dayOfMonth.toString()
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@DailyTasbeehActivity, R.color.text_primary))
            textSize = 13f
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.daily_calendar_day_count, count)
            gravity = Gravity.CENTER
            setTextColor(
                ContextCompat.getColor(
                    this@DailyTasbeehActivity,
                    if (count > 0) R.color.deep_teal else R.color.text_secondary
                )
            )
            textSize = 11f
        })
        calendarGrid.addView(container, dayCellLayoutParams(calendarGrid.childCount))
    }

    private fun dayCellLayoutParams(index: Int): GridLayout.LayoutParams {
        val column = index % DAYS_PER_WEEK
        val row = index / DAYS_PER_WEEK
        return GridLayout.LayoutParams(
            GridLayout.spec(row),
            GridLayout.spec(column, 1f)
        ).apply {
            width = 0
            height = dp(58)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        const val DAYS_PER_WEEK = 7
        const val PREFERENCES_NAME = "smart_ring_preferences"
        const val PREFERENCE_LANGUAGE = "app_language"
        const val PREFERENCE_RTL_LAYOUT = "rtl_layout"
    }
}
