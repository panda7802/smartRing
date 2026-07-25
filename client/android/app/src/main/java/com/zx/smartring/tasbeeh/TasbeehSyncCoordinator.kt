package com.zx.smartring.tasbeeh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.zx.smartring.auth.SessionStore
import com.zx.smartring.auth.UserSession
import com.zx.smartring.network.SmartRingApiException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class TasbeehCloudOperation {
    RESET,
    SYNC
}

internal object TasbeehSyncPolicy {
    fun shouldUpload(count: Int, postResetSyncPending: Boolean): Boolean {
        require(count >= 0)
        return !postResetSyncPending || count > 0
    }
}

interface TasbeehSyncListener {
    fun onCountSynced(result: TasbeehSyncResult)
    fun onResetSynced()
    fun onLoginRequired()
    fun onSessionExpired()
    fun onCloudFailure(operation: TasbeehCloudOperation)
}

class TasbeehSyncCoordinator(
    context: Context,
    private val listener: TasbeehSyncListener
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Volatile
    private var suppressCountSync = false

    @Volatile
    private var deviceResetConfirmed = false

    @Volatile
    private var latestSuppressedCount: Int? = null

    @Volatile
    private var loginWarningSent = false

    fun onDeviceResetStarted() {
        suppressCountSync = true
        deviceResetConfirmed = false
        latestSuppressedCount = null
    }

    fun onDeviceResetFailed() {
        suppressCountSync = false
        deviceResetConfirmed = false
        latestSuppressedCount = null
    }

    fun onDeviceResetConfirmed() {
        val session = SessionStore.get(appContext)
        if (session == null) {
            onDeviceResetFailed()
            notifyLoginRequired()
            return
        }
        deviceResetConfirmed = true
        latestSuppressedCount = null
        prepareStateFor(session)
        preferences.edit {
            putBoolean(KEY_REMOTE_RESET_REQUIRED, true)
            putBoolean(KEY_POST_RESET_SYNC_PENDING, false)
        }
        executor.execute {
            if (ensureRemoteReset(session, TasbeehCloudOperation.RESET)) {
                suppressCountSync = false
                deviceResetConfirmed = false
                notifyMain { listener.onResetSynced() }
                latestSuppressedCount?.let(::syncDeviceCount)
                latestSuppressedCount = null
            } else {
                suppressCountSync = false
                deviceResetConfirmed = false
            }
        }
    }

    fun syncDeviceCount(count: Int) {
        require(count >= 0)
        if (suppressCountSync) {
            if (deviceResetConfirmed) latestSuppressedCount = count
            return
        }
        val session = SessionStore.get(appContext)
        if (session == null) {
            notifyLoginRequired()
            return
        }
        loginWarningSent = false
        executor.execute {
            prepareStateFor(session)
            if (!ensureRemoteReset(session, TasbeehCloudOperation.SYNC)) return@execute
            val postResetPending = preferences.getBoolean(KEY_POST_RESET_SYNC_PENDING, false)
            if (!TasbeehSyncPolicy.shouldUpload(count, postResetPending)) return@execute
            runCatching { TasbeehApi.syncCount(session.token, count) }
                .onSuccess { result ->
                    if (postResetPending) {
                        preferences.edit { putBoolean(KEY_POST_RESET_SYNC_PENDING, false) }
                    }
                    notifyMain { listener.onCountSynced(result) }
                }
                .onFailure { handleFailure(it, TasbeehCloudOperation.SYNC) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun ensureRemoteReset(
        session: UserSession,
        failureOperation: TasbeehCloudOperation
    ): Boolean {
        if (!preferences.getBoolean(KEY_REMOTE_RESET_REQUIRED, false)) return true
        return runCatching { TasbeehApi.markReset(session.token) }
            .fold(
                onSuccess = {
                    preferences.edit {
                        putBoolean(KEY_REMOTE_RESET_REQUIRED, false)
                        putBoolean(KEY_POST_RESET_SYNC_PENDING, true)
                    }
                    true
                },
                onFailure = {
                    handleFailure(it, failureOperation)
                    false
                }
            )
    }

    private fun prepareStateFor(session: UserSession) {
        val owner = preferences.getString(KEY_OWNER, null)
        if (owner == session.name) return
        preferences.edit {
            putString(KEY_OWNER, session.name)
            putBoolean(KEY_REMOTE_RESET_REQUIRED, false)
            putBoolean(KEY_POST_RESET_SYNC_PENDING, false)
        }
    }

    private fun handleFailure(error: Throwable, operation: TasbeehCloudOperation) {
        if (error is SmartRingApiException && error.statusCode == 401) {
            SessionStore.clear(appContext)
            notifyMain { listener.onSessionExpired() }
        } else {
            notifyMain { listener.onCloudFailure(operation) }
        }
    }

    private fun notifyLoginRequired() {
        if (loginWarningSent) return
        loginWarningSent = true
        notifyMain { listener.onLoginRequired() }
    }

    private fun notifyMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        const val PREFERENCES_NAME = "tasbeeh_sync_state"
        const val KEY_OWNER = "owner"
        const val KEY_REMOTE_RESET_REQUIRED = "remote_reset_required"
        const val KEY_POST_RESET_SYNC_PENDING = "post_reset_sync_pending"
    }
}
