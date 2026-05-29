// Phase 1 Layer 3 — `:core:services`
//
// Per doc/BUILD_ORDER.md Layer 3 ("Minimum Viable Route War"):
//   "The first layer that physically produces audio on the Nokia C22.
//    Foreground service + the VoIP-mode dominance machinery only;
//    MediaProjection capture lands in Layer 4."
//
// Depends on every prior layer: :core:common (utilities + DeviceQuirkProfile),
// :core:data (state persistence), :core:audioengine (JNI bridge for the silent
// VoIP anchor — actual capture pipe lands in Layer 4).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vyzorix.audiorouter.services"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
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

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// Production code is explicit-API strict to keep public surfaces auditable
// across the daemon. Tests stay free of the constraint so kotlin.test idioms
// compile without per-member visibility annotations.
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
