// Phase 1 Layer 0 — `core/common`
//
// Pure-Kotlin / JVM module per doc/BUILD_ORDER.md:
//   "Pure Kotlin utilities, constants, enums, models, extensions, dispatchers,
//    logging primitives. No Android runtime calls. No Room. No JNI. No services."
//
// When Layer 1 (`core/data`) lands and starts depending on Android APIs, this
// module will be migrated to `com.android.library` (or split into a pure-Kotlin
// core and an Android-typed sibling). The public API surface of Layer 0 stays
// compatible across that migration.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
