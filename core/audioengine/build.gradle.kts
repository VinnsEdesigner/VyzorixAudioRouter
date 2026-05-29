// Phase 1 Layer 2 — `core/audioengine`
//
// Per doc/BUILD_ORDER.md Layer 2:
//   "Native C++ ring buffer + JNI bridge. NO Kotlin audio pipeline yet —
//    just the native side and its bridge. No services."
//
// Depends only on :core:common (Layer 0). The Kotlin-side audio pipeline
// (`AudioPipelineController`, etc.) lives in Layer 3+ and consumes this
// module via the JNI bridge declared in `NativeAudioBridge.kt`.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vyzorix.audiorouter.audioengine"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // Match the audio path's expected ABI set for the Nokia C22
                // (32-bit arm64-v8a + armeabi-v7a; 64-bit primary). Adding
                // x86_64 keeps emulator builds working for CI sanity.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")

                // C++17 — required by std::atomic specialisations used in
                // capture_ring_buffer.cpp.
                cppFlags += listOf(
                    "-std=c++17",
                    "-Wall",
                    "-Werror",
                    "-Wno-unused-parameter",
                    "-Wno-missing-field-initializers",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                )
                arguments += listOf(
                    // Enable -O3 + -ffast-math only for release; debug builds
                    // keep -O0 + -g so logcat backtraces stay readable.
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-O0", "-g", "-DVYZORIX_DEBUG=1")
                }
            }
        }
        release {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    // -O3 + -ffast-math per RepoTree §core/audioengine/cpp
                    // CMakeLists.txt comment.
                    cppFlags += listOf("-O3", "-ffast-math", "-DVYZORIX_DEBUG=0")
                }
            }
            consumerProguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    explicitApi()
}

// ---- Host-side native unit tests ------------------------------------------
//
// The Android `externalNativeBuild` task only produces ABIs for the device.
// To verify the algorithms on the build host (so CI catches regressions
// without an emulator), we compile the same .cpp files against the host
// compiler and run a tiny assertion harness.
//
// The on-device sine-wave round-trip test from BUILD_ORDER §Layer 2 remains
// the canonical acceptance gate; this host harness is a *surrogate* that
// runs in CI / locally and exercises the same algorithm sources.

val nativeHostTestSources = listOf(
    "capture_ring_buffer.cpp",
    "pcm_mixer.cpp",
    "playback_resampler.cpp",
    "underrun_guard.cpp",
    "latency_tracker.cpp",
    "audio_clock_sync.cpp",
    "logger_engine.cpp",
    "memory_guard.cpp",
    "ringbuffer_pressure.cpp",
    "audio_fallback_bridge.cpp",
    "watchdog_ping.cpp",
)

tasks.register<Exec>("compileNativeHostTests") {
    group = "verification"
    description = "Compile the host-side native test runner with the system C++ compiler."
    val outBin = layout.buildDirectory.file("native-host-tests/native_test_runner")
    val sourceDir = file("src/main/cpp")
    val testSource = file("src/test/cpp/native_test_runner.cpp")
    val sources = nativeHostTestSources.map { file("src/main/cpp/$it") } + testSource
    inputs.files(sources)
    outputs.file(outBin)
    doFirst {
        outBin.get().asFile.parentFile.mkdirs()
    }
    executable = "g++"
    args = buildList {
        add("-std=c++17")
        add("-Wall")
        add("-O2")
        add("-pthread")
        add("-I${sourceDir.absolutePath}/include")
        addAll(sources.map { it.absolutePath })
        add("-o")
        add(outBin.get().asFile.absolutePath)
    }
}

tasks.register<Exec>("runNativeHostTests") {
    group = "verification"
    description = "Run the host-side native unit-test harness (surrogate for the on-device acceptance test)."
    dependsOn("compileNativeHostTests")
    val outBin = layout.buildDirectory.file("native-host-tests/native_test_runner")
    executable = outBin.get().asFile.absolutePath
}

tasks.named("check") {
    dependsOn("runNativeHostTests")
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
