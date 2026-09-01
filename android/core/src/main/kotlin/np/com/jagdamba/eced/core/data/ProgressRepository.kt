package np.com.jagdamba.eced.core.data

import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.com.jagdamba.eced.core.model.Progress

/**
 * Watch-progress, written debounced from the player.
 *
 * Identity is dual: the TV writes with deviceId (no login), mobile writes with
 * profileId (real account). The DB enforces exactly one of the two via a check
 * constraint, so passing both is a runtime error, not a silent bug.
 */
class ProgressRepository(
    private val client: io.github.jan.supabase.SupabaseClient = Supa.client,
) {

    suspend fun save(
        lessonId: String,
        positionSec: Int,
        completed: Boolean,
        deviceId: String? = null,
        profileId: String? = null,
    ) = withContext(Dispatchers.IO) {
        require((deviceId == null) != (profileId == null)) {
            "Pass exactly one of deviceId / profileId"
        }
        val conflict = if (deviceId != null) "device_id,lesson_id" else "profile_id,lesson_id"

        quietly("progress.upsert") {
            client.from("progress").upsert(
                Progress(
                    lessonId    = lessonId,
                    deviceId    = deviceId,
                    profileId   = profileId,
                    positionSec = positionSec,
                    completed   = completed,
                    updatedAt   = nowIso(),
                )
            ) {
                onConflict = conflict
            }
        }
        Unit
    }

    /**
     * Now, as Postgres will read it back.
     *
     * SimpleDateFormat rather than java.time because minSdk is 23 and core
     * library desugaring is off, so Instant is not available here.
     *
     * Only ever compared against other rows written by this same television,
     * so a set with a wrong clock still orders its own history correctly.
     */
    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    suspend fun forDevice(deviceId: String): Map<String, Progress> = withContext(Dispatchers.IO) {
        quietly("progress.forDevice") {
            client.from("progress")
                .select { filter { eq("device_id", deviceId) } }
                .decodeList<Progress>()
                .associateBy { it.lessonId }
        } ?: emptyMap()
    }

    /**
     * Lesson id -> fraction watched, for progress bars and the continue row.
     * Duration lives on `lessons`, not `progress`, so the caller supplies it.
     */
    fun fractions(progress: Map<String, Progress>, durationSec: Map<String, Int>): Map<String, Float> =
        progress.mapNotNull { (lessonId, p) ->
            val total = durationSec[lessonId] ?: return@mapNotNull null
            if (total <= 0) return@mapNotNull null
            val f = if (p.completed) 1f else (p.positionSec.toFloat() / total)
            lessonId to f.coerceIn(0f, 1f)
        }.toMap()

    suspend fun resumePosition(lessonId: String, deviceId: String): Int = withContext(Dispatchers.IO) {
        quietly("progress.resume") {
            client.from("progress")
                .select {
                    filter { eq("device_id", deviceId); eq("lesson_id", lessonId) }
                    limit(1)
                }
                .decodeSingleOrNull<Progress>()
                ?.positionSec
        } ?: 0
    }
}
