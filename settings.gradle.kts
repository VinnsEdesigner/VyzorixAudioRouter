// Phase 1:
//   Layer 0 -> :core:common (now `com.android.library`; was pure-JVM in PR #5).
//   Layer 1 -> :core:data    (Room + SQLCipher + DataStore).
// Subsequent layers (audioengine, services, app) will join here as they are
// introduced per doc/BUILD_ORDER.md.

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
