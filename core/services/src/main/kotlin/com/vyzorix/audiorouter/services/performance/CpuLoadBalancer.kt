package com.vyzorix.audiorouter.services.performance

public data class ThreadPriorityPlan(public val audioPriority: Int, public val diagnosticsPriority: Int)
public class CpuLoadBalancer { public fun plan(cpuLoad: Double): ThreadPriorityPlan = if (cpuLoad >= 0.85) ThreadPriorityPlan(-16, 10) else ThreadPriorityPlan(-8, 0) }
