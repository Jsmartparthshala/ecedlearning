import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "np.com.jagdamba.eced.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "np.com.jagdamba.eced"
        minSdk        = libs.versions.minSdk.get().toInt()
        targetSdk     = libs.versions.targetSdk.get().toInt()
        // Bumped off 1 for the first time since the first build. Two builds
        // sharing a versionCode are the same build as far as Android is
        // concerned: nothing can offer one as an update to the other, and a
        // television cannot tell an operator which of them it is running. The
        // pairing screen prints versionName, so it wants to move too.
        versionCode   = 2
        versionName   = "0.2.0"
    }

    // PairingFragment reads BuildConfig.VERSION_NAME
    buildFeatures { buildConfig = true }

    /**
     * The release key, read from secrets.properties the same way the Supabase
     * values are - the file is gitignored and the password never enters the
     * repository.
     *
     * This matters more here than the usual "you need it for the Play Store".
     * An in-place update, whether over the air or by hand, only installs when
     * the new APK carries the same signature as the one already on the box. So
     * the key a television is first given is the key it is married to: change it
     * later and every set in every school has to be uninstalled, reinstalled and
     * paired again by somebody standing in front of it. The debug key that
     * `assembleDebug` uses is generated per machine and cannot go to Play, which
     * makes shipping debug builds to schools a decision to do that walk later.
     *
     * With no keystore configured this block does nothing and a release build
     * comes out unsigned, exactly as it did before - so a checkout without
     * secrets.properties still builds.
     */
    val signing  = secrets()
    val storePath = signing.getProperty("RELEASE_STORE_FILE", "").trim()
    val keystore  = if (storePath.isEmpty()) null else rootProject.file(storePath)
    val hasKeystore = keystore != null && keystore.exists()

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile     = keystore
                storePassword = signing.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias      = signing.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword   = signing.getProperty("RELEASE_KEY_PASSWORD", "")

                // v1 as well as v2/v3: some of these boxes are Android 7, which
                // predates APK Signature Scheme v2 and will not install without
                // the old JAR signature present too.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // turn on once the app stops changing daily
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

fun secrets(): Properties {
    val p = Properties()
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
    return p
}

kotlin {
    compilerOptions {
        // kotlinOptions{} was removed in Kotlin 2.4 — this is the replacement DSL.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.rt)
    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.coroutines)
    // LegalFragment caches the remote policy documents to preferences, so the TV
    // module encodes a :core model itself. :core keeps this as implementation, so
    // it does not come through the project dependency.
    implementation(libs.kotlinx.serialization)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.leanback)
    implementation(libs.media3.datasource)
}
