// NotificationChannelManager — creates and manages the daemon's
// NotificationChannel set.
//
// A13 (`minSdk = 33`) mandates a notification channel for every foreground
// notification; missing channel → `IllegalArgumentException`. This object
// owns the canonical channel definitions matched against the constant IDs
// in `constants/NotificationConstants.kt`.

package com.vyzorix.audiorouter.common.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.vyzorix.audiorouter.common.constants.NotificationConstants

/** Idempotent registration of the daemon's NotificationChannel set. */
public object NotificationChannelManager {

    /**
     * Creates every required channel if it doesn't already exist. Safe to
     * call repeatedly — `NotificationManager.createNotificationChannel` is
     * documented to no-op when the channel already exists with the same
     * importance.
     *
     * Channels are created with their canonical names from
     * [NotificationConstants]; importance levels are picked per channel
     * purpose:
     *
     *   * `CHANNEL_DAEMON` — `IMPORTANCE_LOW` (foreground service ticker; no sound)
     *   * `CHANNEL_UPDATE` — `IMPORTANCE_DEFAULT` (user actionable; quiet sound OK)
     *   * `CHANNEL_ALERT`  — `IMPORTANCE_HIGH`    (crash signal; head-up)
     */
    public fun ensureChannels(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
        for (definition in canonicalChannels()) {
            manager.createNotificationChannel(definition.toChannel())
        }
    }

    /** Internal channel descriptor; exposed for testing. */
    @VisibleForTesting
    internal data class ChannelDefinition(
        val id: String,
        val name: String,
        val description: String,
        val importance: Int,
    ) {
        fun toChannel(): NotificationChannel =
            NotificationChannel(id, name, importance).also { it.description = description }
    }

    @VisibleForTesting
    internal fun canonicalChannels(): List<ChannelDefinition> = listOf(
        ChannelDefinition(
            id = NotificationConstants.CHANNEL_DAEMON,
            name = "Vyzorix daemon",
            description = "Persistent foreground notification for the audio-routing daemon.",
            importance = NotificationManager.IMPORTANCE_LOW,
        ),
        ChannelDefinition(
            id = NotificationConstants.CHANNEL_UPDATE,
            name = "Vyzorix updates",
            description = "OTA update status and progress.",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        ),
        ChannelDefinition(
            id = NotificationConstants.CHANNEL_ALERT,
            name = "Vyzorix alerts",
            description = "Crash signals and operator-actionable runtime alerts.",
            importance = NotificationManager.IMPORTANCE_HIGH,
        ),
    )
}
