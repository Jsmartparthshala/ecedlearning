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

    buildTypes {
        release {
            isMinifyEnabled = false   // turn on once the app stops changing daily
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
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
