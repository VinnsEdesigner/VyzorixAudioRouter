// Phase 1 Layer 1 — `core/data`
//
// Per doc/BUILD_ORDER.md Layer 1:
//   "Room + SQLCipher persistence, plus encrypted DataStore for the C2 secret."
//
// Depends only on :core:common (Layer 0). Subsequent layers (audioengine,
// services, app) will join the dependency graph in later PRs.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vyzorix.audiorouter.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        // KSP / Room schema export — pinned so schema drift is reviewable in PRs.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
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

    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore Preferences — per doc/DOC_7 §3.9, DeviceSecretStore writes
    // its sealed blob to a Preferences DataStore container.
    implementation(libs.androidx.datastore.preferences)

    // SQLCipher — full-DB encryption per ADR-0004.
    implementation(libs.sqlcipher.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val isTestTask = name.contains("UnitTest", ignoreCase = true) ||
        name.contains("AndroidTest", ignoreCase = true)
    if (!isTestTask) {
        kotlinOptions.freeCompilerArgs += listOf("-Xexplicit-api=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
