package com.vyzorix.audiorouter.services.voip

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.AudioRouteSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Drift-classifier unit tests for [RoutePersistenceDaemon.computeDrift]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutePersistenceDaemonTest {

    private val daemon: RoutePersistenceDaemon by lazy {
        // `run()` is never called; computeDrift is pure.
        RoutePersistenceDaemon(
            routeManager = AudioRouteManager(ApplicationProvider.getApplicationContext()),
            silentVoipSession = SilentVoipSession(),
        )
    }

    @Test
    fun `mode lost classifies as MODE_LOST`() {
        val drift = daemon.computeDrift(
            snapshot = snapshot(mode = AudioManager.MODE_NORMAL),
            framesWritten = 100L,
            framesAtPreviousTick = 50L,
            ticksSinceLastFrameProgress = 0L,
        )
        assertEquals(RoutePersistenceDaemon.Drift.MODE_LOST, drift)
    }

    @Test
    fun `speakerphone flipped classifies as SPEAKERPHONE_FLIPPED`() {
        val drift = daemon.computeDrift(
            snapshot = snapshot(
                mode = AudioManager.MODE_IN_COMMUNICATION,
                isSpeakerphoneOn = false,
            ),
            framesWritten = 100L,
            framesAtPreviousTick = 50L,
            ticksSinceLastFrameProgress = 0L,
        )
        assertEquals(RoutePersistenceDaemon.Drift.SPEAKERPHONE_FLIPPED, drift)
    }

    @Test
    fun `stalled anchor classifies as ANCHOR_STALLED`() {
        val drift = daemon.computeDrift(
            snapshot = snapshot(),
            framesWritten = 100L,
            framesAtPreviousTick = 100L,
            ticksSinceLastFrameProgress = 12L,
        )
        assertEquals(RoutePersistenceDaemon.Drift.ANCHOR_STALLED, drift)
    }

    @Test
    fun `phantom headset hijack classifies as HEADSET_HIJACK`() {
        val drift = daemon.computeDrift(
            snapshot = snapshot(
                isWiredHeadsetPresent = true,
                builtInSpeakerPresent = false,
            ),
            framesWritten = 100L,
            framesAtPreviousTick = 100L,
            ticksSinceLastFrameProgress = 0L,
        )
        assertEquals(RoutePersistenceDaemon.Drift.HEADSET_HIJACK, drift)
    }

    @Test
    fun `healthy snapshot classifies as NONE`() {
        val drift = daemon.computeDrift(
            snapshot = snapshot(),
            framesWritten = 200L,
            framesAtPreviousTick = 100L,
            ticksSinceLastFrameProgress = 0L,
        )
        assertEquals(RoutePersistenceDaemon.Drift.NONE, drift)
    }

    @Test
    fun `zero-frame anchor in first three ticks does NOT classify as drift`() {
        val drift = daemon.computeDrift(
            snapshot = snapshot(),
            framesWritten = 0L,
            framesAtPreviousTick = 0L,
            ticksSinceLastFrameProgress = 2L,
        )
        assertEquals(RoutePersistenceDaemon.Drift.NONE, drift)
    }

    private fun snapshot(
        mode: Int = AudioManager.MODE_IN_COMMUNICATION,
        isSpeakerphoneOn: Boolean = true,
        isWiredHeadsetPresent: Boolean = false,
        builtInSpeakerPresent: Boolean = true,
    ): AudioRouteSnapshot = AudioRouteSnapshot(
        mode = mode,
        isSpeakerphoneOn = isSpeakerphoneOn,
        isBluetoothScoOn = false,
        isWiredHeadsetPresent = isWiredHeadsetPresent,
        builtInSpeakerPresent = builtInSpeakerPresent,
        activeOutputs = emptyList(),
    )
}
