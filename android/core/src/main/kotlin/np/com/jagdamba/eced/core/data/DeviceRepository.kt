package np.com.jagdamba.eced.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.com.jagdamba.eced.core.model.DeviceRow
import np.com.jagdamba.eced.core.model.SessionRow
import java.util.UUID

/**
 * Reverse provisioning, device side.
 *
 * Flow: boot -> stable hardware UUID -> upsert a devices row -> show the UUID on the
 * pairing screen -> listen on Realtime for a sessions row -> cache the token for ten
 * years. The school never types anything.
 */
class DeviceRepository(
    private val context: Context,
    private val client: io.github.jan.supabase.SupabaseClient = Supa.client,
) {

    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "eced_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Stable per-device identity. ANDROID_ID survives app updates and reboots but is
     * reset by a factory reset — which is exactly the semantics we want, since a
     * factory-reset TV should return to the unpaired state.
     */
    @SuppressLint("HardwareIds")
    fun hardwareUuid(): String {
        prefs.getString(KEY_UUID, null)?.let { return it }

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val generated = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
        } else {
            UUID.randomUUID().toString()          // emulator / broken ANDROID_ID
        }
        prefs.edit().putString(KEY_UUID, generated).apply()
        return generated
    }

    /** Short, readable form for the pairing screen — nobody reads 36 chars off a TV. */
    fun pairingCode(): String = hardwareUuid().replace("-", "").take(8).uppercase()

    /**
     * Announce this device so the central office can claim it, and heartbeat on
     * every launch.
     *
     * Goes through the `register_device` RPC rather than upserting the table
     * directly. The device deliberately has NO write access to `devices`: a direct
     * upsert is INSERT ... ON CONFLICT DO UPDATE under the hood, which needs UPDATE
     * rights on every column it touches, and granting that would also let a TV set
     * its own school_id and claimed_at — self-provisioning, which is exactly what
     * reverse provisioning exists to prevent.
     */
    suspend fun register(appVersion: String): DeviceRow? = withContext(Dispatchers.IO) {
        val uuid = hardwareUuid()
        quietly("devices.register") {
            client.postgrest
                .rpc("register_device", buildJsonObject {
                    put("p_hardware_uuid", uuid)
                    put("p_app_version", appVersion)
                })
                .decodeList<DeviceRow>()
                .firstOrNull()
        }
    }

    /** Poll fallback. Realtime is the primary path; this covers a dropped socket. */
    suspend fun fetchSession(deviceId: String): SessionRow? = withContext(Dispatchers.IO) {
        quietly("sessions.fetch") {
            client.from("sessions")
                .select {
                    filter {
                        eq("device_id", deviceId)
                        eq("revoked", false)
                    }
                    limit(1)
                }
                .decodeSingleOrNull<SessionRow>()
        }
    }

    // ------------------------------------------------------------ local cache

    fun cacheSession(token: String, expiresAt: String, deviceId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EXPIRES, expiresAt)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun cachedToken(): String?    = prefs.getString(KEY_TOKEN, null)
    fun cachedDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    val isPaired: Boolean get()   = !cachedToken().isNullOrBlank()

    /** Presenter shortcut 'R' in the HTML prototype — back to unpaired. */
    fun factoryReset() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).remove(KEY_DEVICE_ID).apply()
    }

    private companion object {
        const val KEY_UUID      = "hardware_uuid"
        const val KEY_TOKEN     = "session_token"
        const val KEY_EXPIRES   = "session_expires"
        const val KEY_DEVICE_ID = "device_id"
    }
}
