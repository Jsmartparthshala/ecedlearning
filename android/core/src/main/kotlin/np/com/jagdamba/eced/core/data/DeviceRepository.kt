package np.com.jagdamba.eced.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.com.jagdamba.eced.core.model.DeviceRow
import np.com.jagdamba.eced.core.model.DeviceWithSchool
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

    /**
     * Unpair this television: revoke the session on the server, then forget it
     * locally.
     *
     * The order matters and the local half is deliberately conditional. Clearing
     * the cache first — which is what [factoryReset] alone used to do — produces
     * a television that looks unpaired for about two seconds and then pairs
     * itself straight back in, because [PairingFragment] re-registers under the
     * same hardware UUID and finds the session nobody revoked. That is not a
     * cosmetic bug: it makes the device impossible to log out.
     *
     * Three-valued, for the same reason [sessionLive] is:
     *
     *  - true : the set is released. Either this call revoked the session, or
     *           there was no live session to revoke because the office had
     *           already removed it. Both end with this television free to be
     *           activated again, so both clear the cache.
     *  - null : the office could not be reached, or release_device is not
     *           installed yet. The cache is left alone.
     *
     * The cache is only cleared on a definite answer. A television that has
     * forgotten its token while the server still shows it claimed cannot be
     * reactivated by the office — the code it displays is already spoken for —
     * so staying paired is the recoverable half of those two wrong states.
     */
    suspend fun release(): Boolean? = withContext(Dispatchers.IO) {
        val token = cachedToken() ?: return@withContext true
        quietly("devices.release") {
            client.postgrest
                .rpc("release_device", buildJsonObject {
                    put("p_hardware_uuid", hardwareUuid())
                    put("p_token", token)
                })
                .decodeAs<Boolean>()
        } ?: return@withContext null

        // A false from the server means no live session matched this token,
        // which is exactly what it says when the office revoked the set first.
        // There is nothing left to release, so forgetting it locally is the
        // correct outcome rather than an error to report to the teacher.
        factoryReset()
        true
    }

    /**
     * Is this television still activated, according to the server?
     *
     * Three-valued on purpose, and the third value is the whole point:
     *
     *  - true  : the session is live.
     *  - false : the server was reached and says this session is revoked or
     *            expired. The caller should return the set to the pairing screen.
     *  - null  : the server could not be reached, so nothing is known.
     *
     * Collapsing null into false would log every television in the district out
     * the first time the link dropped, which is a far worse failure than a
     * revoked set staying up until it next has signal. [quietly] returns null
     * for a network error and the RPC itself never returns null, so the two
     * cases stay distinguishable.
     */
    suspend fun sessionLive(): Boolean? = withContext(Dispatchers.IO) {
        // Null, not false: no token means nothing was ever asked of the server,
        // and false is documented as "the server said this session is gone".
        val token = cachedToken() ?: return@withContext null
        quietly("devices.sessionStatus") {
            client.postgrest
                .rpc("session_status", buildJsonObject {
                    put("p_hardware_uuid", hardwareUuid())
                    put("p_token", token)
                })
                .decodeAs<Boolean>()
        }
    }

    // ------------------------------------------------------------ local cache

    fun cacheSession(token: String, expiresAt: String, deviceId: String, schoolName: String? = null) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EXPIRES, expiresAt)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
        if (schoolName != null) prefs.edit().putString(KEY_SCHOOL, schoolName).apply()
    }

    fun cachedSchoolName(): String? = prefs.getString(KEY_SCHOOL, null)
    fun cachedExpiry(): String?     = prefs.getString(KEY_EXPIRES, null)

    /**
     * Resolve and cache the school this device belongs to, for the header chip,
     * and the teacher it was assigned to, for the profile screen.
     *
     * One query for both: they come off the same device row, and the profile
     * screen should never have to wait on a second round trip to say who the
     * television belongs to.
     *
     * A teacher is optional. When the ops console has not assigned one - or has
     * removed one, which nulls the column rather than deleting the device - the
     * cached values are cleared so the profile screen falls back to the school.
     */
    suspend fun refreshSchoolName(): String? = withContext(Dispatchers.IO) {
        val deviceId = cachedDeviceId() ?: return@withContext null

        // The class columns are asked for optimistically and dropped if the
        // database has not had 0007_levels_and_classes.sql applied yet. The
        // migration is run by hand and the app is installed separately, so the
        // two are never simultaneous - and without the fallback a television on
        // the older schema would lose its school name too, because one missing
        // column fails the whole select.
        val withClass = quietly("devices.school") {
            client.from("devices")
                .select(Columns.raw(DEVICE_COLUMNS_WITH_CLASS)) {
                    filter { eq("id", deviceId) }
                    limit(1)
                }
                .decodeSingleOrNull<DeviceWithSchool>()
        }
        val row = withClass ?: quietly("devices.school.noClass") {
            client.from("devices")
                .select(Columns.raw(DEVICE_COLUMNS)) {
                    filter { eq("id", deviceId) }
                    limit(1)
                }
                .decodeSingleOrNull<DeviceWithSchool>()
        }
        // A failed query leaves the cache alone. Wiping the teacher because the
        // network blinked would blank the profile screen on a flaky connection.
        if (row != null) {
            val edit = prefs.edit()
                .putString(KEY_TEACHER, row.teachers?.name)
                .putString(KEY_TEACHER_ROLE, row.teachers?.role)

            // Only the class-aware select can say anything about the class. The
            // fallback ran because the first select failed, and a failure is not
            // evidence that this television has no class - it comes back with
            // classId null because the column was never asked for. Writing that
            // null here would clear a real assignment, and the home screen reads
            // a cleared assignment as the office having moved the set, so it
            // would drop the teacher's remembered grade and silently open on a
            // different one. Stale beats wrong: leave the last known class alone.
            if (withClass != null) {
                edit.putString(KEY_CLASS_ID, row.classId)
                    .putString(KEY_CLASS_LABEL, row.classes?.label)
                    .putString(KEY_CLASS_LEVEL, row.classes?.levels?.slug)
            }
            edit.apply()
        }
        val name = row?.schools?.name
        if (name != null) prefs.edit().putString(KEY_SCHOOL, name).apply()
        name
    }

    fun cachedTeacherName(): String? = prefs.getString(KEY_TEACHER, null)
    fun cachedTeacherRole(): String? = prefs.getString(KEY_TEACHER_ROLE, null)

    /**
     * The class this television was assigned to by the office, if any.
     *
     * [cachedClassId] is what the home screen watches for change: a reassignment
     * is an administrative act and should override whatever grade somebody last
     * picked with the remote. [cachedClassLevel] is the grade slug that class
     * sits in, which is the grade the television opens on.
     */
    fun cachedClassId(): String?    = prefs.getString(KEY_CLASS_ID, null)
    fun cachedClassLabel(): String? = prefs.getString(KEY_CLASS_LABEL, null)
    fun cachedClassLevel(): String? = prefs.getString(KEY_CLASS_LEVEL, null)

    fun cachedToken(): String?    = prefs.getString(KEY_TOKEN, null)
    fun cachedDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    val isPaired: Boolean get()   = !cachedToken().isNullOrBlank()

    /** Presenter shortcut 'R' in the HTML prototype — back to unpaired. */
    fun factoryReset() {
        prefs.edit()
            .remove(KEY_TOKEN).remove(KEY_EXPIRES)
            .remove(KEY_DEVICE_ID).remove(KEY_SCHOOL)
            .remove(KEY_TEACHER).remove(KEY_TEACHER_ROLE)
            .remove(KEY_CLASS_ID).remove(KEY_CLASS_LABEL).remove(KEY_CLASS_LEVEL)
            .apply()
    }

    private companion object {
        const val KEY_UUID      = "hardware_uuid"
        const val KEY_TOKEN     = "session_token"
        const val KEY_EXPIRES   = "session_expires"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SCHOOL    = "school_name"
        const val KEY_TEACHER      = "teacher_name"
        const val KEY_TEACHER_ROLE = "teacher_role"
        const val KEY_CLASS_ID     = "class_id"
        const val KEY_CLASS_LABEL  = "class_label"
        const val KEY_CLASS_LEVEL  = "class_level_slug"

        const val DEVICE_COLUMNS =
            "school_id, schools(name), teacher_id, teachers(name, role)"

        const val DEVICE_COLUMNS_WITH_CLASS =
            DEVICE_COLUMNS + ", class_id, classes(label, level_id, levels(slug, name_en))"
    }
}
