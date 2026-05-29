// Root project — modules declare their own plugins.
//
// AGP is referenced (`apply false`) so that buildscript-level configuration is
// loaded once at the root and each module activates the plugin via its own
// `plugins { alias(libs.plugins.android.library) }` block.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    base
}

tasks.register("checkAllModules") {
    group = "verification"
    description = "Runs check on every wired-in module."
    dependsOn(subprojects.map { "${it.path}:check" })
}
