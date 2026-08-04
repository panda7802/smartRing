package com.zx.smartring.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zx.smartring.BuildConfig
import com.zx.smartring.R
import com.zx.smartring.auth.LoginActivity
import com.zx.smartring.auth.SessionStore
import com.zx.smartring.chat.model.ChatMessage
import com.zx.smartring.chat.model.ChatRole
import com.zx.smartring.chat.network.DeepSeekApiException
import com.zx.smartring.chat.network.DeepSeekChatApi
import com.zx.smartring.chat.network.EmptyDeepSeekResponseException
import com.zx.smartring.chat.network.FaithChatCloudApi
import com.zx.smartring.chat.network.MissingDeepSeekKeyException
import com.zx.smartring.network.SmartRingApiException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class FaithChatActivity : Activity() {
    private lateinit var root: View
    private lateinit var content: View
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var status: TextView
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<ChatMessage>()
    private var requestInFlight = false

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
        setContentView(R.layout.activity_faith_chat)

        root = findViewById(R.id.faith_chat_root)
        content = findViewById(R.id.faith_chat_content)
        messagesContainer = findViewById(R.id.faith_chat_messages)
        scrollView = findViewById(R.id.faith_chat_scroll)
        input = findViewById(R.id.faith_chat_input)
        sendButton = findViewById(R.id.faith_chat_send)
        status = findViewById(R.id.faith_chat_status)

        applyInsets()
        applySavedLayoutDirection()
        findViewById<View>(R.id.faith_chat_back).setOnClickListener { finish() }
        sendButton.setOnClickListener { submitQuestion() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitQuestion()
                true
            } else {
                false
            }
        }

        val session = SessionStore.get(this)
        if (session == null) {
            Toast.makeText(this, R.string.faith_chat_login_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        loadHistory(session.token)
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applyInsets() {
        val statusBarScrim = findViewById<View>(R.id.faith_chat_status_bar_scrim)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(
                systemInsets.left,
                systemInsets.top,
                systemInsets.right,
                max(systemInsets.bottom, imeInsets.bottom)
            )
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply {
                height = systemInsets.top
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySavedLayoutDirection() {
        val rightToLeft = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_RTL_LAYOUT, false)
        root.layoutDirection = if (rightToLeft) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun submitQuestion() {
        if (requestInFlight) return
        val session = SessionStore.get(this)
        if (session == null) {
            handleSessionExpired()
            return
        }
        val question = input.text.toString().trim()
        if (question.isBlank()) {
            Toast.makeText(this, R.string.faith_chat_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        if (BuildConfig.DEEPSEEK_API_KEY.isBlank()) {
            showStatus(R.string.faith_chat_missing_key)
            return
        }

        val userMessage = ChatMessage(ChatRole.USER, question)
        history += userMessage
        addMessageBubble(userMessage)
        input.text.clear()
        setLoading(true)

        val requestHistory = history.toList()
        executor.execute {
            val answer = try {
                DeepSeekChatApi.complete(BuildConfig.DEEPSEEK_API_KEY, requestHistory)
            } catch (error: Throwable) {
                runOnUiThread {
                    if (!isDestroyed) {
                        setLoading(false)
                        showRequestError(error)
                    }
                }
                return@execute
            }
            val cloudSaveError = runCatching {
                FaithChatCloudApi.saveExchange(session.token, question, answer)
            }.exceptionOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setLoading(false)
                val assistantMessage = ChatMessage(ChatRole.ASSISTANT, answer)
                history += assistantMessage
                addMessageBubble(assistantMessage)
                when {
                    cloudSaveError == null -> Unit
                    cloudSaveError is SmartRingApiException &&
                        cloudSaveError.statusCode == 401 -> handleSessionExpired()
                    else -> showStatus(R.string.faith_chat_save_failed)
                }
            }
        }
    }

    private fun loadHistory(token: String) {
        setLoading(true, R.string.faith_chat_loading_history)
        executor.execute {
            val result = runCatching { FaithChatCloudApi.history(token) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setLoading(false)
                result.onSuccess { messages ->
                    history.clear()
                    history.addAll(messages)
                    messagesContainer.removeAllViews()
                    if (messages.isEmpty()) {
                        addWelcomeMessage()
                    } else {
                        messages.forEach(::addMessageBubble)
                    }
                    scrollToBottom()
                }.onFailure { error ->
                    if (error is SmartRingApiException && error.statusCode == 401) {
                        handleSessionExpired()
                    } else {
                        showStatus(R.string.faith_chat_history_failed)
                    }
                }
            }
        }
    }

    private fun addWelcomeMessage() {
        addMessageBubble(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = getString(R.string.faith_chat_welcome)
            )
        )
    }

    private fun setLoading(
        loading: Boolean,
        loadingMessageRes: Int = R.string.faith_chat_thinking
    ) {
        requestInFlight = loading
        sendButton.isEnabled = !loading
        input.isEnabled = !loading
        if (loading) {
            showStatus(loadingMessageRes)
        } else {
            status.visibility = View.GONE
        }
    }

    private fun showRequestError(error: Throwable) {
        when (error) {
            is MissingDeepSeekKeyException -> showStatus(R.string.faith_chat_missing_key)
            is EmptyDeepSeekResponseException -> showStatus(R.string.faith_chat_empty_response)
            is DeepSeekApiException -> {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.faith_chat_service_error, error.statusCode)
            }
            is IOException -> showStatus(R.string.faith_chat_network_error)
            else -> showStatus(R.string.faith_chat_unknown_error)
        }
    }

    private fun showStatus(messageRes: Int) {
        status.visibility = View.VISIBLE
        status.setText(messageRes)
    }

    private fun handleSessionExpired() {
        SessionStore.clear(this)
        Toast.makeText(this, R.string.auth_session_expired, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun addMessageBubble(message: ChatMessage) {
        val isUser = message.role == ChatRole.USER
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        val roleLabel = TextView(this).apply {
            text = getString(
                if (isUser) R.string.faith_chat_user_label else R.string.faith_chat_assistant_label
            )
            setTextColor(ContextCompat.getColor(context, R.color.chat_gold))
            textSize = 11f
            setPadding(dp(5), 0, dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
            }
        }
        val bubble = TextView(this).apply {
            text = message.content
            setTextColor(ContextCompat.getColor(context, R.color.chat_ivory))
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = ContextCompat.getDrawable(
                context,
                if (isUser) {
                    R.drawable.bg_faith_user_bubble
                } else {
                    R.drawable.bg_faith_assistant_bubble
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
            }
        }
        row.addView(roleLabel)
        row.addView(bubble)
        messagesContainer.addView(row)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCES_NAME = "smart_ring_preferences"
        private const val PREFERENCE_LANGUAGE = "app_language"
        private const val PREFERENCE_RTL_LAYOUT = "rtl_layout"
    }
}
