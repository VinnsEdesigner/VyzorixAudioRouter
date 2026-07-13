package com.vyzorix.audiorouter.services.diagnostics.system

import com.vyzorix.audiorouter.services.diagnostics.RuntimeEventTimeline

public class AppLaunchObserver(private val timeline: RuntimeEventTimeline) { public fun onForeground(packageName: String, epochMs: Long = System.currentTimeMillis()): Unit { timeline.add("app_foreground", packageName, mapOf("package" to packageName, "epochMs" to epochMs.toString())) } }
