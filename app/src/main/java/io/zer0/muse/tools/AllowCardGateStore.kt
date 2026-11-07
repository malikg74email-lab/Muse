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

// Top-level DataStore delegate (same rule as ToolConfigStore): the store
// is reached from several places (approval coordinator, tool controller,
// settings page, Koin), and a per-instance delegate would crash with
// "multiple DataStores active for the same file".
private val Context.allowCardDataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "shaheen_allow_cards")

/**
 * Shaheen 2 — Allow Cards, Part 1 (Malik's order, 2026-11-07).
 *
 * Remembered Allow Card decisions, keyed by the gate's scope keys
 * (see [SensitiveToolGate]):
 *  - "Always allow this kind" decisions and known first-use scopes
 *    (MCP servers, message recipients) are PERSISTED here.
 *  - "Allow for this session" and plain Deny decisions are held
 *    in-memory only (process lifetime), following the established
 *    [SessionPermissionStore] session-cache pattern: a deny is
 *    remembered for the session at minimum, never forever.
 *
 * Scope keys embed owner-approved identifiers (an MCP server name, a
 * message recipient) because that is precisely what the owner approved;
 * they never leave the device (local DataStore, no sync, no login).
 */
class AllowCardGateStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val MEMORY_KEY = stringPreferencesKey("allow_card_memory")
    }

    @Serializable
    data class StoredMemory(
        val alwaysAllowedScopes: Set<String> = emptySet(),
        val knownScopes: Set<String> = emptySet(),
    )

    // ── Session-only memory (in-memory, process lifetime) ──────────────

    @Volatile
    private var sessionAllowedScopes: Set<String> = emptySet()

    @Volatile
    private var sessionDeniedScopes: Set<String> = emptySet()

    // ── Persisted memory ───────────────────────────────────────────────

    val memoryFlow: Flow<StoredMemory> =
        context.allowCardDataStore.data.map { prefs -> decode(prefs) }

    suspend fun getPersistedMemory(): StoredMemory = memoryFlow.first()

    /** Snapshot for the gatekeeper: persisted memory + this process's session memory. */
    suspend fun memoryView(): AllowCardMemoryView {
        val persisted = getPersistedMemory()
        return AllowCardMemoryView(
            alwaysAllowedScopes = persisted.alwaysAllowedScopes,
            sessionAllowedScopes = sessionAllowedScopes,
            sessionDeniedScopes = sessionDeniedScopes,
            knownScopes = persisted.knownScopes,
        )
    }

    /** "Always allow this kind" — persisted. */
    suspend fun rememberAlwaysAllow(scopeKey: String) {
        context.allowCardDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[MEMORY_KEY] = json.encodeToString(
                current.copy(alwaysAllowedScopes = current.alwaysAllowedScopes + scopeKey),
            )
        }
    }

    /**
     * Mark a first-use scope as known (an MCP server that was approved
     * once, a recipient the owner approved contacting). Persisted: the
     * whole point of a first-use gate is that it fires once per scope.
     */
    suspend fun markKnown(scopeKey: String) {
        context.allowCardDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[MEMORY_KEY] = json.encodeToString(
                current.copy(knownScopes = current.knownScopes + scopeKey),
            )
        }
    }

    /** "Allow for this session" — in-memory only. */
    @Synchronized
    fun rememberSessionAllow(scopeKey: String) {
        sessionAllowedScopes = sessionAllowedScopes + scopeKey
        sessionDeniedScopes = sessionDeniedScopes - scopeKey
    }

    /** Plain Deny — remembered for this session (process lifetime) at minimum. */
    @Synchronized
    fun rememberSessionDeny(scopeKey: String) {
        sessionDeniedScopes = sessionDeniedScopes + scopeKey
        sessionAllowedScopes = sessionAllowedScopes - scopeKey
    }

    // ── Review / reset (Settings → Tools → Allow Card memory) ──────────

    suspend fun forgetAlwaysAllow(scopeKey: String) {
        context.allowCardDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[MEMORY_KEY] = json.encodeToString(
                current.copy(alwaysAllowedScopes = current.alwaysAllowedScopes - scopeKey),
            )
        }
    }

    suspend fun forgetKnown(scopeKey: String) {
        context.allowCardDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[MEMORY_KEY] = json.encodeToString(
                current.copy(knownScopes = current.knownScopes - scopeKey),
            )
        }
    }

    /** Forget everything the gate remembers, persisted and session alike. */
    suspend fun resetAll() {
        context.allowCardDataStore.edit { prefs ->
            prefs[MEMORY_KEY] = json.encodeToString(StoredMemory())
        }
        synchronized(this) {
            sessionAllowedScopes = emptySet()
            sessionDeniedScopes = emptySet()
        }
    }

    private fun decode(prefs: Preferences): StoredMemory {
        val raw = prefs[MEMORY_KEY] ?: return StoredMemory()
        return try {
            json.decodeFromString<StoredMemory>(raw)
        } catch (_: Exception) {
            StoredMemory()
        }
    }
}
