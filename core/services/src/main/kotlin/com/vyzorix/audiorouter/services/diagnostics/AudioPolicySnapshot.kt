package com.vyzorix.audiorouter.services.diagnostics

public data class AudioPolicySnapshot(public val mode: String, public val speakerphoneOn: Boolean, public val devices: List<String>, public val epochMs: Long = System.currentTimeMillis())
