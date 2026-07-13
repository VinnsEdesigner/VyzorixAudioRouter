package com.vyzorix.audiorouter.services.monitoring

public data class ThermalSample(public val celsius: Float, public val status: Int)
public class DeviceThermalMonitor(private val warnCelsius: Float = 42f) { public fun warning(sample: ThermalSample): Boolean = sample.status >= 2 || sample.celsius >= warnCelsius }
