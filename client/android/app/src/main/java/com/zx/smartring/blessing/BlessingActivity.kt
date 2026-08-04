package com.zx.smartring.blessing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zx.smartring.R
import com.zx.smartring.auth.LoginActivity
import com.zx.smartring.auth.SessionStore
import com.zx.smartring.network.SmartRingApiException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BlessingActivity : Activity(), NfcAdapter.ReaderCallback {
    private lateinit var nicknameInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var writeButton: TextView
    private lateinit var writeStatus: TextView
    private lateinit var historyStatus: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var sentTab: TextView
    private lateinit var receivedTab: TextView
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var nfcAdapter: NfcAdapter? = null
    @Volatile private var pendingPayload: BlessingPayload? = null
    @Volatile private var writeModeActive = false
    @Volatile private var writing = false
    private var history = BlessingHistory(emptyList(), emptyList())
    private var showingSent = true

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
        setContentView(R.layout.activity_blessing)
        applyInsets()

        nicknameInput = findViewById(R.id.blessing_nickname)
        messageInput = findViewById(R.id.blessing_message)
        writeButton = findViewById(R.id.blessing_write_button)
        writeStatus = findViewById(R.id.blessing_write_status)
        historyStatus = findViewById(R.id.blessing_history_status)
        historyList = findViewById(R.id.blessing_history_list)
        sentTab = findViewById(R.id.blessing_sent_tab)
        receivedTab = findViewById(R.id.blessing_received_tab)

        val session = SessionStore.get(this)
        if (session == null) {
            Toast.makeText(this, R.string.blessing_login_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        nicknameInput.setText(session.name)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            writeButton.isEnabled = false
            writeButton.alpha = 0.5f
            writeStatus.setText(R.string.blessing_nfc_unavailable)
        }

        findViewById<View>(R.id.blessing_back).setOnClickListener { finish() }
        writeButton.setOnClickListener { prepareWrite() }
        sentTab.setOnClickListener {
            showingSent = true
            renderHistory()
        }
        receivedTab.setOnClickListener {
            showingSent = false
            renderHistory()
        }
        findViewById<View>(R.id.blessing_history_refresh).setOnClickListener { loadHistory() }
        renderHistory()
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

    private fun applyInsets() {
        val root = findViewById<View>(R.id.blessing_root)
        val content = findViewById<View>(R.id.blessing_content)
        val scrim = findViewById<View>(R.id.blessing_status_bar_scrim)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            scrim.layoutParams = scrim.layoutParams.apply { height = insets.top }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun prepareWrite() {
        if (writing || writeModeActive) {
            if (pendingPayload != null) writeStatus.setText(R.string.blessing_hold_near_tag)
            return
        }
        if (nfcAdapter?.isEnabled != true) {
            writeStatus.setText(R.string.blessing_enable_nfc)
            return
        }
        if (pendingPayload != null) {
            writeStatus.setText(R.string.blessing_hold_near_tag)
            return
        }
        val nickname = nicknameInput.text.toString().trim()
        val message = messageInput.text.toString().trim()
        when {
            nickname.isEmpty() || message.isEmpty() -> {
                writeStatus.setText(R.string.blessing_fields_required)
                return
            }
            nickname.length > BlessingPayload.MAX_NICKNAME_LENGTH -> {
                writeStatus.setText(R.string.blessing_nickname_too_long)
                return
            }
            message.length > BlessingPayload.MAX_MESSAGE_LENGTH -> {
                writeStatus.setText(R.string.blessing_message_too_long)
                return
            }
        }
        val session = SessionStore.get(this) ?: run {
            Toast.makeText(this, R.string.blessing_login_required, Toast.LENGTH_SHORT).show()
            return
        }
        writeModeActive = true
        setWriteBusy(true)
        writeStatus.setText(R.string.blessing_registering)
        executor.execute {
            val result = runCatching {
                BlessingApi.createTag(session.token, nickname, message, packageName)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setWriteBusy(false)
                result.onSuccess { payload ->
                    pendingPayload = payload
                    SessionStore.save(this, session.copy(userId = payload.senderUserId))
                    writeButton.setText(R.string.blessing_waiting_for_tag_button)
                    writeStatus.setText(R.string.blessing_hold_near_tag)
                }.onFailure(::handleWriteFailure)
            }
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        when (BlessingNfcMode.action(writeModeActive, pendingPayload != null)) {
            BlessingNfcAction.READ -> {
                val scanned = runCatching { BlessingNfc.read(tag) }.getOrNull() ?: return
                runOnUiThread { openBlessing(scanned) }
                return
            }
            BlessingNfcAction.IGNORE -> return
            BlessingNfcAction.WRITE -> Unit
        }
        val payload = pendingPayload ?: return
        synchronized(this) {
            if (writing) return
            writing = true
        }
        val result = runCatching {
            val ndefMessage: NdefMessage = BlessingNfc.message(payload)
            BlessingNfc.write(tag, ndefMessage)
        }
        runOnUiThread {
            writing = false
            if (isDestroyed) return@runOnUiThread
            result.onSuccess {
                pendingPayload = null
                writeModeActive = false
                writeButton.setText(R.string.blessing_prepare_write)
                writeStatus.setText(R.string.blessing_write_success)
                Toast.makeText(this, R.string.blessing_write_success, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Log.w(TAG, "Unable to write blessing NFC tag", error)
                writeStatus.text = when {
                    error.message?.contains("read-only", ignoreCase = true) == true ->
                        getString(R.string.blessing_tag_read_only)
                    error.message?.contains("too small", ignoreCase = true) == true ->
                        getString(R.string.blessing_tag_too_small)
                    else -> getString(R.string.blessing_write_failed)
                }
            }
        }
    }

    private fun openBlessing(reference: BlessingTagReference) {
        startActivity(
            Intent(this, BlessingDisplayActivity::class.java)
                .putExtra(BlessingDisplayActivity.EXTRA_BLESSING_ID, reference.blessingId)
        )
    }

    private fun handleWriteFailure(error: Throwable) {
        writeModeActive = false
        if (error is SmartRingApiException && error.statusCode == 401) {
            SessionStore.clear(this)
            Toast.makeText(this, R.string.auth_session_expired, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.w(TAG, "Unable to register blessing tag", error)
        writeStatus.setText(R.string.blessing_register_failed)
    }

    private fun setWriteBusy(busy: Boolean) {
        writeButton.isEnabled = !busy
        writeButton.alpha = if (busy) 0.55f else 1f
    }

    private fun loadHistory() {
        val session = SessionStore.get(this) ?: return
        historyStatus.visibility = View.VISIBLE
        historyStatus.setText(R.string.blessing_history_loading)
        historyList.removeAllViews()
        executor.execute {
            val result = runCatching { BlessingApi.history(session.token) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    history = it
                    renderHistory()
                }.onFailure { error ->
                    if (error is SmartRingApiException && error.statusCode == 401) {
                        SessionStore.clear(this)
                        Toast.makeText(this, R.string.auth_session_expired, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        historyStatus.visibility = View.VISIBLE
                        historyStatus.setText(R.string.blessing_history_failed)
                    }
                }
            }
        }
    }

    private fun renderHistory() {
        sentTab.isSelected = showingSent
        receivedTab.isSelected = !showingSent
        sentTab.setBackgroundResource(
            if (showingSent) R.drawable.bg_blessing_tab_selected else R.drawable.bg_blessing_tab
        )
        receivedTab.setBackgroundResource(
            if (showingSent) R.drawable.bg_blessing_tab else R.drawable.bg_blessing_tab_selected
        )
        sentTab.setTextColor(ContextCompat.getColor(this, if (showingSent) R.color.white else R.color.deep_teal))
        receivedTab.setTextColor(ContextCompat.getColor(this, if (showingSent) R.color.deep_teal else R.color.white))
        historyList.removeAllViews()
        val items = if (showingSent) history.sent else history.received
        historyStatus.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) historyStatus.setText(R.string.blessing_history_empty)
        items.forEach { item -> historyList.addView(historyCard(item)) }
    }

    private fun historyCard(item: BlessingHistoryItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackgroundResource(R.drawable.bg_card)
        elevation = dp(2).toFloat()
        addView(TextView(this@BlessingActivity).apply {
            text = if (showingSent) {
                getString(R.string.blessing_sent_to, item.recipientName)
            } else {
                getString(R.string.blessing_received_from, item.nickname)
            }
            setTextColor(ContextCompat.getColor(this@BlessingActivity, R.color.deep_teal))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(this@BlessingActivity).apply {
            text = item.message
            setTextColor(ContextCompat.getColor(this@BlessingActivity, R.color.text_primary))
            textSize = 16f
            setPadding(0, dp(7), 0, dp(5))
        })
        addView(TextView(this@BlessingActivity).apply {
            val date = formatTime(item.createdAt)
            text = if (item.isSelf) getString(R.string.blessing_self_history, date) else date
            setTextColor(ContextCompat.getColor(this@BlessingActivity, R.color.text_secondary))
            textSize = 12f
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun formatTime(value: String): String = runCatching {
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", resources.configuration.locales[0])
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault(value)

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        )
        if (nfcAdapter != null && nfcAdapter?.isEnabled == false) {
            writeStatus.setText(R.string.blessing_enable_nfc)
        }
        loadHistory()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "BlessingActivity"
        const val PREFERENCES_NAME = "smart_ring_preferences"
        const val PREFERENCE_LANGUAGE = "app_language"
    }
}
