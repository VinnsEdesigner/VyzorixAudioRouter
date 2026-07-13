package com.vyzorix.audiorouter.services.performance

public enum class ThermalAction { NONE, SLOW_SAMPLING, PAUSE_DIAGNOSTICS }
public class ThermalMitigationPolicy { public fun action(status: Int): ThermalAction = when { status >= 4 -> ThermalAction.PAUSE_DIAGNOSTICS; status >= 2 -> ThermalAction.SLOW_SAMPLING; else -> ThermalAction.NONE } }
