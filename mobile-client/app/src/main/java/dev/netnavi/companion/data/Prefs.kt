package dev.netnavi.companion.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "navi_prefs")

data class LinkSettings(val url: String, val token: String)

class Prefs(private val context: Context) {
    private val kUrl = stringPreferencesKey("host_url")
    private val kToken = stringPreferencesKey("auth_token")
    private val kDeviceId = stringPreferencesKey("device_id")
    private val kEnabled = booleanPreferencesKey("link_enabled")

    val settings: Flow<LinkSettings> = context.dataStore.data.map {
        LinkSettings(url = it[kUrl].orEmpty(), token = it[kToken].orEmpty())
    }

    suspend fun save(url: String, token: String) {
        context.dataStore.edit {
            it[kUrl] = url.trim()
            it[kToken] = token.trim()
        }
    }

    /** Stable per-install id, generated on first use. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[kDeviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { if (it[kDeviceId] == null) it[kDeviceId] = id }
        return context.dataStore.data.first()[kDeviceId] ?: id
    }

    /** Whether the user left the link switched on (lets the system restart the service). */
    suspend fun linkEnabled(): Boolean = context.dataStore.data.first()[kEnabled] ?: false

    suspend fun setLinkEnabled(enabled: Boolean) {
        context.dataStore.edit { it[kEnabled] = enabled }
    }
}
