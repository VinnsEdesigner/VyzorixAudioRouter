package com.vyzorix.audiorouter.services.diagnostics.system

public data class PackageStateChange(public val packageName: String, public val installed: Boolean, public val epochMs: Long = System.currentTimeMillis())
public class PackageStateObserver { private val changes = mutableListOf<PackageStateChange>(); public fun onPackageChanged(change: PackageStateChange): Unit { synchronized(changes) { changes += change } }; public fun recent(): List<PackageStateChange> = synchronized(changes) { changes.toList() } }
