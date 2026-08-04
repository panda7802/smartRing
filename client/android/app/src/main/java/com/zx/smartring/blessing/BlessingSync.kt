package com.zx.smartring.blessing

import android.content.Context
import com.zx.smartring.auth.SessionStore
import com.zx.smartring.network.SmartRingApiException
import org.json.JSONArray
import org.json.JSONObject

data class PendingBlessingScan(
    val eventId: String,
    val blessingId: String,
    val recipientUserId: Long? = null
)
data class BlessingSyncSummary(val syncedEventIds: Set<String>, val selfEventIds: Set<String>)

object BlessingPendingStore {
    private const val PREFERENCES = "smart_ring_blessing_pending"
    private const val KEY_SCANS = "scans"

    @Synchronized
    fun enqueue(context: Context, scan: PendingBlessingScan) {
        val current = all(context).toMutableList()
        if (current.none { it.eventId == scan.eventId }) current += scan
        save(context, current)
    }

    @Synchronized
    fun all(context: Context): List<PendingBlessingScan> {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_SCANS, null)
            .orEmpty()
        if (encoded.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val eventId = value.optString("eventId")
                    val blessingId = value.optString("blessingId")
                    if (eventId.isNotBlank() && blessingId.isNotBlank()) {
                        add(
                            PendingBlessingScan(
                                eventId,
                                blessingId,
                                value.optLong("recipientUserId").takeIf { it > 0L }
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun remove(context: Context, eventId: String) {
        save(context, all(context).filterNot { it.eventId == eventId })
    }

    private fun save(context: Context, scans: List<PendingBlessingScan>) {
        val array = JSONArray()
        scans.forEach { scan ->
            array.put(
                JSONObject()
                    .put("eventId", scan.eventId)
                    .put("blessingId", scan.blessingId)
                    .apply {
                        scan.recipientUserId?.let { put("recipientUserId", it) }
                    }
            )
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCANS, array.toString())
            .apply()
    }
}

object BlessingSync {
    fun syncPending(context: Context): BlessingSyncSummary {
        var session = SessionStore.get(context) ?: return BlessingSyncSummary(emptySet(), emptySet())
        if (session.userId == null) {
            try {
                val account = BlessingApi.account(session.token)
                session = session.copy(name = account.name, userId = account.userId)
                SessionStore.save(context, session)
            } catch (error: SmartRingApiException) {
                if (error.statusCode == 401) SessionStore.clear(context)
                return BlessingSyncSummary(emptySet(), emptySet())
            } catch (_: Exception) {
                return BlessingSyncSummary(emptySet(), emptySet())
            }
        }

        val synced = linkedSetOf<String>()
        val selfEvents = linkedSetOf<String>()
        val currentUserId = session.userId ?: return BlessingSyncSummary(emptySet(), emptySet())
        for (scan in BlessingPendingStore.all(context)) {
            if (scan.recipientUserId != null && scan.recipientUserId != currentUserId) continue
            try {
                val result = BlessingApi.receive(session.token, scan.blessingId, scan.eventId)
                BlessingPendingStore.remove(context, scan.eventId)
                synced += scan.eventId
                if (result.isSelf) selfEvents += scan.eventId
            } catch (error: SmartRingApiException) {
                if (error.statusCode == 401) SessionStore.clear(context)
                break
            } catch (_: Exception) {
                break
            }
        }
        return BlessingSyncSummary(synced, selfEvents)
    }
}
