// Phase 1 Layer 0: only the pure-Kotlin :core:common module is wired in.
// Subsequent layers (data, audioengine, services, app) will join here as they are
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
