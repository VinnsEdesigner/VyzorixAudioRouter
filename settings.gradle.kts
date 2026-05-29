// Phase 1:
//   Layer 0 -> :core:common      (now `com.android.library`; was pure-JVM in PR #5).
//   Layer 1 -> :core:data        (Room + SQLCipher + DataStore).
//   Layer 2 -> :core:audioengine (native C++ ring buffer + JNI bridge).
//   Layer 3 -> :core:services    (foreground service + route-war machinery)
//              :app              (Android application module — produces the APK).
// Layers 4+ continue to extend `:core:services` and `:app` in place rather than
// adding new modules.

@Suppress("UnstableApiUsage")
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "VyzorixAudioRouter"

include(":core:common")
project(":core:common").projectDir = file("core/common")

include(":core:data")
project(":core:data").projectDir = file("core/data")

include(":core:audioengine")
project(":core:audioengine").projectDir = file("core/audioengine")

include(":core:services")
project(":core:services").projectDir = file("core/services")

include(":app")
project(":app").projectDir = file("app")
