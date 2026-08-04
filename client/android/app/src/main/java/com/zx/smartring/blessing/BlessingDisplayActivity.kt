package com.zx.smartring.blessing

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zx.smartring.R
import com.zx.smartring.auth.LoginActivity
import com.zx.smartring.auth.SessionStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BlessingDisplayActivity : Activity() {
    private lateinit var nicknameView: TextView
    private lateinit var messageView: TextView
    private lateinit var syncStatusView: TextView
    private lateinit var retryView: TextView
    private lateinit var card: View
    private lateinit var halo: View
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pulseAnimator: ObjectAnimator? = null
    private var currentEventId: String? = null
    private var currentBlessingId: String? = null
    private var legacyFallback: BlessingPayload? = null

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
        setContentView(R.layout.activity_blessing_display)
        applyInsets()
        nicknameView = findViewById(R.id.blessing_display_nickname)
        messageView = findViewById(R.id.blessing_display_message)
        syncStatusView = findViewById(R.id.blessing_display_sync_status)
        retryView = findViewById(R.id.blessing_display_retry)
        card = findViewById(R.id.blessing_display_card)
        halo = findViewById(R.id.blessing_display_halo)
        findViewById<View>(R.id.blessing_display_close).setOnClickListener { finish() }
        findViewById<View>(R.id.blessing_display_history).setOnClickListener { openHistory() }
        retryView.setOnClickListener {
            val blessingId = currentBlessingId ?: return@setOnClickListener
            val eventId = currentEventId ?: return@setOnClickListener
            showLoading()
            loadBlessing(blessingId, eventId)
        }

        currentEventId = savedInstanceState?.getString(STATE_EVENT_ID)
        currentBlessingId = savedInstanceState?.getString(STATE_BLESSING_ID)
        handleIntent(intent, currentEventId)
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.blessing_display_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun handleIntent(intent: Intent?, restoredEventId: String? = null) {
        val reference = BlessingNfc.fromIntent(intent)
            ?: intent?.getStringExtra(EXTRA_BLESSING_ID)
                ?.let(BlessingTagReference::fromNdefPayload)
        if (reference == null) {
            Toast.makeText(this, R.string.blessing_invalid_tag, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val eventId = if (currentBlessingId == reference.blessingId) {
            restoredEventId ?: UUID.randomUUID().toString()
        } else {
            UUID.randomUUID().toString()
        }
        currentEventId = eventId
        currentBlessingId = reference.blessingId
        legacyFallback = reference.legacyPayload
        showLoading()
        loadBlessing(reference.blessingId, eventId)
    }

    private fun showLoading() {
        retryView.visibility = View.GONE
        nicknameView.setText(R.string.blessing_cloud_loading_title)
        messageView.setText(R.string.blessing_cloud_loading_message)
        syncStatusView.setText(R.string.blessing_cloud_connecting)
    }

    private fun loadBlessing(blessingId: String, eventId: String) {
        executor.execute {
            val result = runCatching { BlessingApi.tag(blessingId) }
            runOnUiThread {
                if (
                    isDestroyed || currentBlessingId != blessingId ||
                    currentEventId != eventId
                ) {
                    return@runOnUiThread
                }
                val payload = result.getOrNull()
                    ?: legacyFallback?.takeIf { it.blessingId == blessingId }
                if (payload == null) {
                    Log.w(TAG, "Unable to load blessing details", result.exceptionOrNull())
                    nicknameView.setText(R.string.blessing_cloud_load_failed_title)
                    messageView.setText(R.string.blessing_cloud_load_failed)
                    syncStatusView.text = ""
                    retryView.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                if (payload.packageName != packageName) {
                    Toast.makeText(this, R.string.blessing_invalid_tag, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                renderAndRecord(payload, eventId)
            }
        }
    }

    private fun renderAndRecord(payload: BlessingPayload, eventId: String) {
        retryView.visibility = View.GONE
        nicknameView.text = getString(R.string.blessing_from_nickname, payload.nickname)
        messageView.text = payload.message
        animateBlessing()
        val session = SessionStore.get(this)
        BlessingPendingStore.enqueue(
            this,
            PendingBlessingScan(eventId, payload.blessingId, session?.userId)
        )
        // Intentionally do not filter blessings sent by the current account. A future
        // product rule may treat self-blessings differently, so keep this log nearby.
        if (session?.userId == payload.senderUserId) {
            Log.i(TAG, "Received own blessing from NFC; eventId=$eventId")
        }
        if (session == null) {
            syncStatusView.setText(R.string.blessing_saved_until_login)
            return
        }
        syncStatusView.setText(R.string.blessing_record_syncing)
        executor.execute {
            val summary = BlessingSync.syncPending(applicationContext)
            runOnUiThread {
                if (isDestroyed || currentEventId != eventId) return@runOnUiThread
                if (eventId in summary.selfEventIds) {
                    Log.i(TAG, "Server confirmed own blessing; eventId=$eventId")
                }
                syncStatusView.setText(
                    if (eventId in summary.syncedEventIds) {
                        R.string.blessing_recorded
                    } else {
                        R.string.blessing_record_pending
                    }
                )
            }
        }
    }

    private fun animateBlessing() {
        card.alpha = 0f
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.translationY = resources.displayMetrics.density * 24f
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(650L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.18f, 0.75f, 0.18f).apply {
            duration = 2400L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun openHistory() {
        val destination = if (SessionStore.get(this) == null) {
            LoginActivity::class.java
        } else {
            BlessingActivity::class.java
        }
        startActivity(Intent(this, destination))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_EVENT_ID, currentEventId)
        outState.putString(STATE_BLESSING_ID, currentBlessingId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BLESSING_ID = "com.zx.smartring.extra.BLESSING_ID"
        private const val STATE_EVENT_ID = "blessing_event_id"
        private const val STATE_BLESSING_ID = "blessing_id"
        private const val TAG = "BlessingNfc"
        private const val PREFERENCES_NAME = "smart_ring_preferences"
        private const val PREFERENCE_LANGUAGE = "app_language"
    }
}
