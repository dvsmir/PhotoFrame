package app.framealt.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.framealt.protocol.FrameEndpoint
import app.framealt.protocol.FramePairing
import app.framealt.protocol.client.FrameInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.frameDataStore by preferencesDataStore(name = "framealt_frames")

/**
 * The paired frame, and the last thing it told us about itself.
 *
 * Caching [FrameInfo] matters for more than speed: the panel size decides how photos are
 * downscaled, and the send queue prepares images while the frame may be unreachable.
 */
data class StoredFrame(
    val peerId: String,
    val issuer: String,
    val host: String,
    val port: Int,
    val name: String = "",
    val placement: String = "",
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val protocolVersion: Int = 0,
    val canView: Boolean = false,
    val canManage: Boolean = false,
    val lastSeenAtMillis: Long = 0,
    /**
     * What the user calls the frame, kept only on this phone. The frame's own [name] is set on
     * the frame itself and there is no known protocol message to change it, so renaming is
     * local. Blank means "use the frame's name".
     */
    val alias: String = "",
) {
    val pairing: FramePairing get() = FramePairing(peerId, issuer)
    val endpoint: FrameEndpoint get() = FrameEndpoint(host, port)

    /** A name to show before the frame has ever been reached. */
    val displayName: String get() = alias.ifBlank { name.ifBlank { "Your frame" } }

    companion object {
        /** The reference client's fallback panel size, used until a frame reports its own. */
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 800
    }
}

/**
 * Persistence for the paired frame.
 *
 * D6 says one frame, so the storage holds one record. It is keyed by peer ID and written as
 * a unit, so widening to a list later is additive rather than a rewrite.
 */
class FrameStore(private val context: Context) {

    val frame: Flow<StoredFrame?> = context.frameDataStore.data.map { it.toFrame() }

    suspend fun current(): StoredFrame? = context.frameDataStore.data.first().toFrame()

    /** Records a new pairing, discarding anything cached about a previous frame. */
    suspend fun savePairing(pairing: FramePairing, endpoint: FrameEndpoint) {
        context.frameDataStore.edit { prefs ->
            prefs.clear()
            prefs[PEER_ID] = pairing.peerId
            prefs[ISSUER] = pairing.issuer
            prefs[HOST] = endpoint.host
            prefs[PORT] = endpoint.port
        }
    }

    /** Updates what the frame reports about itself after a successful connection. */
    suspend fun saveInfo(info: FrameInfo, endpoint: FrameEndpoint) {
        context.frameDataStore.edit { prefs ->
            if (prefs[PEER_ID] == null) return@edit
            prefs[NAME] = info.name
            prefs[PLACEMENT] = info.placement
            prefs[WIDTH] = info.width
            prefs[HEIGHT] = info.height
            prefs[PROTOCOL_VERSION] = info.protocolVersion
            prefs[CAN_VIEW] = info.permissions.view
            prefs[CAN_MANAGE] = info.permissions.manage
            prefs[HOST] = endpoint.host
            prefs[PORT] = endpoint.port
            prefs[LAST_SEEN] = System.currentTimeMillis()
        }
    }

    /** Remembers a new address after rediscovery, without touching anything else. */
    suspend fun saveEndpoint(endpoint: FrameEndpoint) {
        context.frameDataStore.edit { prefs ->
            if (prefs[PEER_ID] == null) return@edit
            prefs[HOST] = endpoint.host
            prefs[PORT] = endpoint.port
        }
    }

    /** Sets the local name for the frame; blank goes back to the name the frame reports. */
    suspend fun setAlias(value: String) {
        context.frameDataStore.edit { prefs ->
            if (prefs[PEER_ID] == null) return@edit
            val trimmed = value.trim().take(MAX_ALIAS)
            if (trimmed.isEmpty()) prefs.remove(ALIAS) else prefs[ALIAS] = trimmed
        }
    }

    suspend fun forget() {
        context.frameDataStore.edit { it.clear() }
    }

    private fun Preferences.toFrame(): StoredFrame? {
        val peerId = this[PEER_ID] ?: return null
        val issuer = this[ISSUER] ?: return null
        return StoredFrame(
            peerId = peerId,
            issuer = issuer,
            host = this[HOST].orEmpty(),
            port = this[PORT] ?: 0,
            name = this[NAME].orEmpty(),
            placement = this[PLACEMENT].orEmpty(),
            width = this[WIDTH] ?: StoredFrame.DEFAULT_WIDTH,
            height = this[HEIGHT] ?: StoredFrame.DEFAULT_HEIGHT,
            protocolVersion = this[PROTOCOL_VERSION] ?: 0,
            canView = this[CAN_VIEW] ?: false,
            canManage = this[CAN_MANAGE] ?: false,
            lastSeenAtMillis = this[LAST_SEEN] ?: 0,
            alias = this[ALIAS].orEmpty(),
        )
    }

    private companion object {
        val PEER_ID = stringPreferencesKey("frame.peerId")
        val ISSUER = stringPreferencesKey("frame.issuer")
        val HOST = stringPreferencesKey("frame.host")
        val PORT = intPreferencesKey("frame.port")
        val NAME = stringPreferencesKey("frame.name")
        val PLACEMENT = stringPreferencesKey("frame.placement")
        val WIDTH = intPreferencesKey("frame.width")
        val HEIGHT = intPreferencesKey("frame.height")
        val PROTOCOL_VERSION = intPreferencesKey("frame.protocolVersion")
        val CAN_VIEW = booleanPreferencesKey("frame.canView")
        val CAN_MANAGE = booleanPreferencesKey("frame.canManage")
        val LAST_SEEN = longPreferencesKey("frame.lastSeenAt")
        val ALIAS = stringPreferencesKey("frame.alias")
        const val MAX_ALIAS = 60
    }
}
