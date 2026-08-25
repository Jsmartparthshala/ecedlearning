package np.com.jagdamba.eced.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import np.com.jagdamba.eced.core.BuildConfig

/**
 * Single Supabase client for the whole app.
 *
 * The anon key ships inside the APK. That is unavoidable for a device with no login,
 * and it is safe *provided RLS is on* — see supabase/002_rls.sql. The anon role can
 * read the catalog and self-register a device. Nothing else.
 */
object Supa {
    val client: SupabaseClient by lazy {
        require(BuildConfig.SUPABASE_URL.isNotBlank()) {
            "SUPABASE_URL is empty — copy android/secrets.properties.example to " +
            "android/secrets.properties and fill it in, then re-sync Gradle."
        }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Postgrest)
            install(Realtime)
            install(Auth)
        }
    }
}
