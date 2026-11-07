package io.zer0.muse.tools

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Top-level DataStore delegate (same rule as ToolConfigStore /
// AllowCardGateStore): several instances over one file must share the
// singleton delegate or the app crashes on duplicate DataStores.
private val Context.packDeliveryDataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "shaheen_pack_delivery")

/**
 * Shaheen 2 — Auto-Training Pack (Part 2, 2026-11-07): delivery
 * bookkeeping for [WorkingInstructionsPack].
 *
 * Records, per provider+model key ([WorkingInstructionsPack.deliveryKey]),
 * which pack version was delivered, when, and whether the structured
 * acknowledgement came back ([AckStatus]). This is bookkeeping, not
 * proof of behaviour — see the pack's own honesty note.
 */
class PackDeliveryStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val RECORDS_KEY = stringPreferencesKey("pack_delivery_records")
    }

    @Serializable
    data class StoredRecords(
        val records: Map<String, PackDeliveryRecord> = emptyMap(),
    )

    val recordsFlow: Flow<Map<String, PackDeliveryRecord>> =
        context.packDeliveryDataStore.data.map { prefs -> decode(prefs).records }

    suspend fun getRecord(key: String): PackDeliveryRecord? = recordsFlow.first()[key]

    suspend fun recordDelivery(
        key: String,
        packVersion: Int,
        ackVerified: Boolean,
        deliveredAtMs: Long = System.currentTimeMillis(),
    ) {
        context.packDeliveryDataStore.edit { prefs ->
            val current = decode(prefs)
            val updated = current.records.toMutableMap()
            updated[key] = PackDeliveryRecord(
                packVersion = packVersion,
                deliveredAtMs = deliveredAtMs,
                ackStatus = if (ackVerified) AckStatus.VERIFIED else AckStatus.DELIVERED_UNVERIFIED,
            )
            prefs[RECORDS_KEY] = json.encodeToString(StoredRecords(updated))
        }
    }

    private fun decode(prefs: Preferences): StoredRecords {
        val raw = prefs[RECORDS_KEY] ?: return StoredRecords()
        return try {
            json.decodeFromString<StoredRecords>(raw)
        } catch (_: Exception) {
            StoredRecords()
        }
    }
}
