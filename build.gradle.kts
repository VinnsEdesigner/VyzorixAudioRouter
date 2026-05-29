// Root project — no plugins applied here. Each module declares its own toolchain.
// Phase 1 Layer 0 keeps this intentionally bare.
plugins {
    base
}

tasks.register("checkAllModules") {
    group = "verification"
    description = "Runs check on every wired-in module (Layer 0: only :core:common)."
    dependsOn(subprojects.map { "${it.path}:check" })
}
