// RouteRecoveryEngine — re-initialises the playback AudioTrack when the
// route changes underneath the daemon.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 638:
//     core/services/playback/RouteRecoveryEngine.kt
//       "Re-initializes output tracks on routing failure".
//
// Trigger inputs:
//   1. `AudioRouteWatcher.observe()` — physical headphone-jack /
//      AudioDeviceCallback events. Headphone PLUG = brief pause + flush
//      + rebuild on the speaker output. UNPLUG = same dance with the
//      route reassertion loop kicked first.
//   2. `SpeakerOutputVerifier.verify()` returning `NotOnSpeaker` for
//      [thresholdConsecutiveBadVerifications] consecutive ticks — the
//      track is still alive but landed on a phantom output.
//   3. Manual trigger via [requestRecovery] (used by the dashboard's
//      "Restart pipeline" action).
//
// Recovery sequence:
//   1. Pause + flush the controller (preserves no PCM — Sample-rate is
//      48 kHz so we'd hear ~50 ms of stale audio if we didn't drop).
//   2. Release-and-unmount the existing track.
//   3. Build a fresh track via [AudioTrackFactory.create].
//   4. Mount the new track on the controller, set play().
//   5. Notify the engine via [RecoveryObserver.onRouteRebuilt] so it can
//      reset its local frames-written counter.
//
// Per RoutePersistenceDaemon's HEADSET_HIJACK escalation policy
// (DOC_4 §2.4 + Layer 3.5 hardening), if 3 recoveries fail inside
// [escalateAfterFailuresWithinMs] we escalate to VendorRouteResetter
// (HAL reset) via the supplied callback.

package com.vyzorix.audiorouter.services.playback

import android.media.AudioDeviceInfo
import com.vyzorix.audiorouter.services.audio.AudioRouteWatcher
import com.vyzorix.audiorouter.services.audio.RouteEvent
import com.vyzorix.audiorouter.services.audio.WiredHeadsetState
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Callbacks the engine must implement for the recovery engine to drive it. */
public interface RecoveryObserver {
    /** Invoked AFTER the controller has been remounted with a fresh track. */
    public fun onRouteRebuilt(track: PlaybackTrackResult.Success)

    /**
     * Invoked when the rebuild attempt failed. The engine should remain
     * paused — [RouteRecoveryEngine] will retry on the next route event or
     * a manual [requestRecovery] call.
     */
    public fun onRouteRebuildFailed(reason: String)
}

/** Callback the engine fires when recovery thrashes — used by Layer 3.5. */
public interface RecoveryEscalator {
    /** Failed recovery count exceeded the threshold inside the rolling window. */
    public fun onEscalateToHalReset(reason: String)
}

/** Reason the recovery engine triggered (logged + surfaced to the dashboard). */
public enum class RecoveryReason {
    HEADSET_PLUG_TRANSITION,
    DEVICE_LIST_DELTA,
    VERIFIER_NOT_ON_SPEAKER,
    MANUAL_RESTART,
}

/** Diagnostic snapshot for the dashboard. */
public data class RouteRecoverySnapshot(
    public val active: Boolean,
    public val totalRecoveries: Long,
    public val successfulRecoveries: Long,
    public val failedRecoveries: Long,
    public val recentFailures: Int,
    public val lastRecoveryEpochMs: Long,
    public val lastReasonLabel: String,
    public val escalations: Long,
)

/**
 * Owns the policy that decides when to rebuild the playback AudioTrack
 * and executes the rebuild.
 *
 * Single-instance per playback engine. Wired into the engine via the
 * [RecoveryObserver] callback and into Layer 3.5's HEADSET_HIJACK
 * escalation policy via [RecoveryEscalator].
 */
public class RouteRecoveryEngine(
    private val scope: CoroutineScope,
    private val routeWatcher: AudioRouteWatcher,
    private val trackFactory: AudioTrackFactory,
    private val controller: AudioTrackController,
    private val gainController: PlaybackGainController? = null,
    private val observer: RecoveryObserver,
    private val escalator: RecoveryEscalator? = null,
    private val configProvider: () -> AudioTrackConfig = { AudioTrackConfig() },
    private val thresholdConsecutiveBadVerifications: Int = 3,
    private val escalateAfterFailuresWithinMs: Long = 60_000L,
    private val escalateFailureThreshold: Int = 3,
    private val rebuildBackoffMs: Long = 100L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val active: AtomicBoolean = AtomicBoolean(false)
    private val collectorJob: AtomicReference<Job?> = AtomicReference(null)
    private val totalRecoveries: AtomicLong = AtomicLong(0L)
    private val successfulRecoveries: AtomicLong = AtomicLong(0L)
    private val failedRecoveries: AtomicLong = AtomicLong(0L)
    private val recentFailureWindowEpochMs: AtomicLong = AtomicLong(0L)
    private val recentFailureCount: AtomicInteger = AtomicInteger(0)
    private val lastRecoveryEpochMs: AtomicLong = AtomicLong(0L)
    private val lastReason: AtomicReference<RecoveryReason?> = AtomicReference(null)
    private val escalations: AtomicLong = AtomicLong(0L)
    private val consecutiveBadVerifications: AtomicInteger = AtomicInteger(0)

    /** Start subscribing to the route watcher. Idempotent. */
    public fun start() {
        if (!active.compareAndSet(false, true)) return
        val job = scope.launch {
            routeWatcher.observe().collect { event -> handleRouteEvent(event) }
        }
        collectorJob.set(job)
        DaemonLogger.get().info(TAG, "recovery.start")
    }

    /** Stop subscribing. Idempotent. */
    public fun stop() {
        if (!active.compareAndSet(true, false)) return
        val job = collectorJob.getAndSet(null)
        job?.cancel()
        DaemonLogger.get().info(TAG, "recovery.stop")
    }

    /**
     * Manual trigger — used by the dashboard's "Restart pipeline" action.
     * Returns true if the rebuild succeeded.
     */
    public fun requestRecovery(reason: String = "manual"): Boolean {
        DaemonLogger.get().info(TAG, "recovery.request.manual reason=$reason")
        return executeRecovery(RecoveryReason.MANUAL_RESTART)
    }

    /**
     * Notify the engine that the verifier reported NotOnSpeaker. After
     * [thresholdConsecutiveBadVerifications] consecutive bad reads we
     * rebuild.
     */
    public fun notifyVerifierResult(verification: SpeakerOutputVerification) {
        when (verification) {
            is SpeakerOutputVerification.OnSpeaker -> {
                if (consecutiveBadVerifications.get() != 0) {
                    DaemonLogger.get().info(
                        TAG,
                        "recovery.verifier.recovered streak=${consecutiveBadVerifications.get()}",
                    )
                }
                consecutiveBadVerifications.set(0)
            }
            is SpeakerOutputVerification.NotOnSpeaker -> {
                val streak = consecutiveBadVerifications.incrementAndGet()
                if (streak >= thresholdConsecutiveBadVerifications) {
                    DaemonLogger.get().warn(
                        TAG,
                        "recovery.verifier.threshold_breached streak=$streak threshold=$thresholdConsecutiveBadVerifications",
                    )
                    consecutiveBadVerifications.set(0)
                    executeRecovery(RecoveryReason.VERIFIER_NOT_ON_SPEAKER)
                }
            }
            is SpeakerOutputVerification.Unavailable -> {
                // Don't escalate on transient AudioManager throws.
            }
        }
    }

    /** Diagnostic snapshot — safe to call from any thread. */
    public fun snapshot(): RouteRecoverySnapshot =
        RouteRecoverySnapshot(
            active = active.get(),
            totalRecoveries = totalRecoveries.get(),
            successfulRecoveries = successfulRecoveries.get(),
            failedRecoveries = failedRecoveries.get(),
            recentFailures = recentFailureCount.get(),
            lastRecoveryEpochMs = lastRecoveryEpochMs.get(),
            lastReasonLabel = lastReason.get()?.name ?: "init",
            escalations = escalations.get(),
        )

    private suspend fun handleRouteEvent(event: RouteEvent) {
        when (event) {
            is RouteEvent.WiredHeadsetPlug -> {
                DaemonLogger.get().info(
                    TAG,
                    "recovery.route_event headset_plug state=${event.state} mic=${event.hasMicrophone}",
                )
                if (event.state == WiredHeadsetState.PLUGGED ||
                    event.state == WiredHeadsetState.UNPLUGGED
                ) {
                    delay(rebuildBackoffMs)
                    executeRecovery(RecoveryReason.HEADSET_PLUG_TRANSITION)
                }
            }
            is RouteEvent.DevicesAdded -> {
                if (event.added.any { isSpeakerOrHeadset(it.type) }) {
                    DaemonLogger.get().info(
                        TAG,
                        "recovery.route_event devices_added types=${event.added.map { it.type }}",
                    )
                    delay(rebuildBackoffMs)
                    executeRecovery(RecoveryReason.DEVICE_LIST_DELTA)
                }
            }
            is RouteEvent.DevicesRemoved -> {
                if (event.removed.any { isSpeakerOrHeadset(it.type) }) {
                    DaemonLogger.get().info(
                        TAG,
                        "recovery.route_event devices_removed types=${event.removed.map { it.type }}",
                    )
                    delay(rebuildBackoffMs)
                    executeRecovery(RecoveryReason.DEVICE_LIST_DELTA)
                }
            }
            is RouteEvent.InitialDevices -> {
                // No rebuild — only used to seed the watcher.
            }
        }
    }

    private fun executeRecovery(reason: RecoveryReason): Boolean {
        totalRecoveries.incrementAndGet()
        lastRecoveryEpochMs.set(clock())
        lastReason.set(reason)
        DaemonLogger.get().info(TAG, "recovery.execute reason=$reason")
        // 1. Pause and flush the existing controller.
        controller.pause()
        controller.flush()
        controller.releaseAndUnmount()
        // 2. Build a new track.
        val config = configProvider()
        val build = trackFactory.create(config)
        if (build !is PlaybackTrackResult.Success) {
            val failed = build as PlaybackTrackResult.Failed
            recordFailure(reason, failed.reason)
            observer.onRouteRebuildFailed(failed.reason)
            return false
        }
        // 3. Mount the new track.
        val mounted = controller.mount(build.track)
        if (mounted is MountResult.Rejected) {
            recordFailure(reason, "mount_rejected_${mounted.reason}")
            observer.onRouteRebuildFailed("mount_rejected_${mounted.reason}")
            return false
        }
        // 4. Apply gain and play.
        gainController?.apply()
        val playOk = controller.play()
        if (!playOk) {
            recordFailure(reason, "play_failed")
            observer.onRouteRebuildFailed("play_failed")
            return false
        }
        successfulRecoveries.incrementAndGet()
        observer.onRouteRebuilt(build)
        DaemonLogger.get().info(
            TAG,
            "recovery.success reason=$reason total=${successfulRecoveries.get()}",
        )
        return true
    }

    private fun recordFailure(reason: RecoveryReason, label: String) {
        failedRecoveries.incrementAndGet()
        val now = clock()
        val windowStart = recentFailureWindowEpochMs.get()
        val withinWindow = windowStart != 0L && (now - windowStart) <= escalateAfterFailuresWithinMs
        val streak = if (withinWindow) {
            recentFailureCount.incrementAndGet()
        } else {
            recentFailureCount.set(1)
            recentFailureWindowEpochMs.set(now)
            1
        }
        DaemonLogger.get().warn(
            TAG,
            "recovery.failure reason=$reason label=$label streak=$streak threshold=$escalateFailureThreshold",
        )
        if (streak >= escalateFailureThreshold) {
            escalations.incrementAndGet()
            recentFailureCount.set(0)
            recentFailureWindowEpochMs.set(0L)
            DaemonLogger.get().warn(
                TAG,
                "recovery.escalate.hal_reset escalations=${escalations.get()}",
            )
            escalator?.onEscalateToHalReset("route_recovery_threshold_breached:$label")
        }
    }

    private fun isSpeakerOrHeadset(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> false
    }

    public companion object {
        private const val TAG: String = "RouteRecoveryEngine"
    }
}
