package com.vyzorix.audiorouter.services.crash

import java.io.File

/** Writes the lightweight last_state.json flight recorder atomically. */
public class LastKnownStateDumper(private val file: File) {
    public fun dump(state: LastKnownState): Unit {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
        temp.writeText(state.toJson())
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Failed to write ${file.absolutePath}" }
    }

    public fun readRaw(): String? = if (file.exists()) file.readText() else null
}

public data class LastKnownState(
    public val uptimeMs: Long,
    public val activePackage: String?,
    public val audioMode: String,
    public val speakerphoneOn: Boolean,
    public val routeState: String,
    public val captureState: String,
    public val epochMs: Long = System.currentTimeMillis(),
) {
    public fun toJson(): String = buildString {
        append('{')
        appendJson("uptimeMs", uptimeMs)
        append(',')
        appendJson("activePackage", activePackage.orEmpty())
        append(',')
        appendJson("audioMode", audioMode)
        append(',')
        appendJson("speakerphoneOn", speakerphoneOn)
        append(',')
        appendJson("routeState", routeState)
        append(',')
        appendJson("captureState", captureState)
        append(',')
        appendJson("epochMs", epochMs)
        append('}')
    }

    private fun StringBuilder.appendJson(name: String, value: String): Unit {
        append('"').append(escape(name)).append("\":\"").append(escape(value)).append('"')
    }

    private fun StringBuilder.appendJson(name: String, value: Long): Unit {
        append('"').append(escape(name)).append("\":").append(value)
    }

    private fun StringBuilder.appendJson(name: String, value: Boolean): Unit {
        append('"').append(escape(name)).append("\":").append(value)
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
