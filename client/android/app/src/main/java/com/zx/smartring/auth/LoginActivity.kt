package com.zx.smartring.auth

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zx.smartring.R
import com.zx.smartring.network.SmartRingApiException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LoginActivity : Activity() {
    private lateinit var formPanel: View
    private lateinit var accountPanel: View
    private lateinit var nameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: TextView
    private lateinit var registerButton: TextView
    private lateinit var statusView: TextView
    private lateinit var accountName: TextView
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var busy = false

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
        setContentView(R.layout.activity_login)

        val root = findViewById<View>(R.id.login_root)
        val content = findViewById<View>(R.id.login_content)
        val statusBarScrim = findViewById<View>(R.id.login_status_bar_scrim)
        formPanel = findViewById(R.id.auth_form_panel)
        accountPanel = findViewById(R.id.auth_account_panel)
        nameInput = findViewById(R.id.auth_name)
        passwordInput = findViewById(R.id.auth_password)
        loginButton = findViewById(R.id.auth_login)
        registerButton = findViewById(R.id.auth_register)
        statusView = findViewById(R.id.auth_status)
        accountName = findViewById(R.id.auth_account_name)

        applyInsets(root, content, statusBarScrim)
        applySavedLayoutDirection(root)
        findViewById<View>(R.id.auth_back).setOnClickListener { finish() }
        loginButton.setOnClickListener { submit(register = false) }
        registerButton.setOnClickListener { submit(register = true) }
        findViewById<View>(R.id.auth_logout).setOnClickListener { logout() }
        showCurrentSession()
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
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply { height = bars.top }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySavedLayoutDirection(root: View) {
        val rtl = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_RTL_LAYOUT, false)
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun showCurrentSession() {
        val session = SessionStore.get(this)
        formPanel.visibility = if (session == null) View.VISIBLE else View.GONE
        accountPanel.visibility = if (session == null) View.GONE else View.VISIBLE
        if (session != null) {
            accountName.text = session.name
        }
        statusView.visibility = View.GONE
    }

    private fun submit(register: Boolean) {
        if (busy) return
        val name = nameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (name.isEmpty() || password.isEmpty()) {
            showStatus(R.string.auth_fields_required)
            return
        }
        setBusy(true)
        executor.execute {
            val result = runCatching {
                if (register) AuthApi.register(name, password)
                AuthApi.login(name, password)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { session ->
                    SessionStore.save(this, session)
                    setResult(RESULT_OK)
                    Toast.makeText(
                        this,
                        if (register) R.string.auth_register_success else R.string.auth_login_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }.onFailure { showApiError(it) }
            }
        }
    }

    private fun logout() {
        SessionStore.clear(this)
        setResult(RESULT_OK)
        nameInput.text.clear()
        passwordInput.text.clear()
        showCurrentSession()
        Toast.makeText(this, R.string.auth_logout_success, Toast.LENGTH_SHORT).show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        loginButton.isEnabled = !value
        registerButton.isEnabled = !value
        loginButton.alpha = if (value) 0.55f else 1f
        registerButton.alpha = if (value) 0.55f else 1f
        if (value) showStatus(R.string.auth_processing)
    }

    private fun showApiError(error: Throwable) {
        val stringId = if (error is SmartRingApiException) {
            when (error.code) {
                "USER_EXISTS" -> R.string.auth_user_exists
                "LOGIN_FAILED" -> R.string.auth_login_failed
                else -> 0
            }
        } else {
            0
        }
        if (stringId != 0) {
            showStatus(stringId)
        } else {
            statusView.text = getString(R.string.auth_network_error)
            statusView.visibility = View.VISIBLE
        }
    }

    private fun showStatus(stringId: Int) {
        statusView.setText(stringId)
        statusView.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES_NAME = "smart_ring_preferences"
        const val PREFERENCE_LANGUAGE = "app_language"
        const val PREFERENCE_RTL_LAYOUT = "rtl_layout"
    }
}
