package app.framealt.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "framealt_settings")

/** User preferences. Nothing here is secret. */
class Settings(private val context: Context) {

    /** Shown on the frame next to photos this client sends. */
    val senderName: Flow<String> = context.settingsDataStore.data.map {
        it[SENDER_NAME] ?: defaultSenderName()
    }

    suspend fun currentSenderName(): String = senderName.first()

    suspend fun setSenderName(value: String) {
        context.settingsDataStore.edit { it[SENDER_NAME] = value.trim().take(MAX_SENDER_NAME) }
    }

    /** Default for "Show the whole photo": fit rather than crop. */
    val fitByDefault: Flow<Boolean> = context.settingsDataStore.data.map { it[FIT_BY_DEFAULT] ?: true }

    suspend fun setFitByDefault(value: Boolean) {
        context.settingsDataStore.edit { it[FIT_BY_DEFAULT] = value }
    }

    val webpQuality: Flow<Int> = context.settingsDataStore.data.map { it[WEBP_QUALITY] ?: DEFAULT_QUALITY }

    suspend fun setWebpQuality(value: Int) {
        context.settingsDataStore.edit { it[WEBP_QUALITY] = value.coerceIn(60, 100) }
    }

    /**
     * Accepts a certificate issuer outside the built-in list.
     *
     * Off by default and buried in Advanced. It exists so a frame newer than the issuer list
     * is a decision the user can make rather than a brick, and it is never set automatically.
     */
    val allowUnknownIssuer: Flow<Boolean> =
        context.settingsDataStore.data.map { it[ALLOW_UNKNOWN_ISSUER] ?: false }

    suspend fun currentAllowUnknownIssuer(): Boolean = allowUnknownIssuer.first()

    suspend fun setAllowUnknownIssuer(value: Boolean) {
        context.settingsDataStore.edit { it[ALLOW_UNKNOWN_ISSUER] = value }
    }

    private fun defaultSenderName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "My phone"

    private companion object {
        val SENDER_NAME = stringPreferencesKey("sender.name")
        val FIT_BY_DEFAULT = booleanPreferencesKey("send.fitByDefault")
        val WEBP_QUALITY = intPreferencesKey("send.webpQuality")
        val ALLOW_UNKNOWN_ISSUER = booleanPreferencesKey("advanced.allowUnknownIssuer")
        const val MAX_SENDER_NAME = 100
        const val DEFAULT_QUALITY = 85
    }
}
