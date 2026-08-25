import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace  = "np.com.jagdamba.eced.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Filled from secrets.properties at build time — see core/README.md.
        // Never commit real values.
        buildConfigField("String", "SUPABASE_URL",      "\"${supabaseUrl()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey()}\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        // kotlinOptions{} was removed in Kotlin 2.4 — this is the replacement DSL.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


fun secrets(): Properties {
    val p = Properties()
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
    return p
}
fun supabaseUrl()     = secrets().getProperty("SUPABASE_URL", "")
fun supabaseAnonKey() = secrets().getProperty("SUPABASE_ANON_KEY", "")

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
    implementation(libs.androidx.security)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
}
