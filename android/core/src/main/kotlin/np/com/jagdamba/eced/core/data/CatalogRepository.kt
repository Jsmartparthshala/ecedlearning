package np.com.jagdamba.eced.core.data

import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.com.jagdamba.eced.core.model.Lesson
import np.com.jagdamba.eced.core.model.Subject
import np.com.jagdamba.eced.core.model.Unit as CatalogUnit

/**
 * Reads the catalog. Deliberately three flat queries rather than one nested select:
 * with 968 lessons a nested fetch is a large payload on a rural connection, and the
 * TV only ever needs one subject's units at a time.
 */
class CatalogRepository(private val client: io.github.jan.supabase.SupabaseClient = Supa.client) {

    suspend fun subjects(): List<Subject> = withContext(Dispatchers.IO) {
        quietly("catalog.subjects") { client.from("subjects")
            .select { order("sort_order", io.github.jan.supabase.postgrest.query.Order.ASCENDING) }
            .decodeList<Subject>() } ?: emptyList()
    }

    suspend fun units(subjectId: String): List<CatalogUnit> = withContext(Dispatchers.IO) {
        quietly("catalog.units") { client.from("units")
            .select {
                filter { eq("subject_id", subjectId) }
                order("sort_order", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }
            .decodeList<CatalogUnit>() } ?: emptyList()
    }

    suspend fun lessons(unitId: String): List<Lesson> = withContext(Dispatchers.IO) {
        quietly("catalog.lessons") { client.from("lessons")
            .select {
                filter { eq("unit_id", unitId) }
                order("sort_order", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }
            .decodeList<Lesson>() } ?: emptyList()
    }

    /**
     * The handful of lessons that actually have a video attached — used to build a
     * "Playable now" row so the demo never lands on a dead placeholder.
     *
     * Reads the `playable_lessons` view rather than filtering client-side: only ~5
     * of 968 rows qualify, and pulling all 968 on every launch is a real cost on a
     * rural connection against a 5 GB/month egress budget. The view exists because
     * supabase-kt's IS NOT NULL filter syntax moves between versions.
     */
    suspend fun playableSample(limit: Int = 5): List<Lesson> = withContext(Dispatchers.IO) {
        quietly("catalog.playable") {
            client.from("playable_lessons")
                .select { limit(limit.toLong()) }
                .decodeList<Lesson>()
        } ?: emptyList()
    }
}
