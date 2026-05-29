// Phase 1 Layer 3 — `:app`
//
// The Android application module. Produces the user-installable APK.
// Depends transitively on every other module via :core:services.
//
// Per doc/BUILD_ORDER.md §Layer 3 the manifest permissions are trimmed
// to the bare minimum for the route-war machinery:
//   - FOREGROUND_SERVICE
//   - FOREGROUND_SERVICE_MEDIA_PLAYBACK
//   - MODIFY_AUDIO_SETTINGS
//   - RECEIVE_BOOT_COMPLETED
//   - POST_NOTIFICATIONS
//
// MediaProjection / FOREGROUND_SERVICE_MEDIA_PROJECTION lands in Layer 4.
// All later-layer permissions (RECORD_AUDIO, internet, etc.) land alongside
// the consumer feature.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vyzorix.audiorouter"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.vyzorix.audiorouter"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
        getByName("release") {
            // No signingConfig yet — release builds are unsigned at Phase 1
            // per BUILD_ORDER §Layer 3 ("No APK signing configuration yet").
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:audioengine"))
    implementation(project(":core:services"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
