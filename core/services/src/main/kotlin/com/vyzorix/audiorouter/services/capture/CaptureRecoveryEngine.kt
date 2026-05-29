// CaptureRecoveryEngine — handles "the capture loop stopped, but we still
// have a valid MediaProjection token" scenarios.
//
// Two distinct failure modes:
//   1. AudioRecord.read() throws or returns a negative code (typical:
//      ERROR_DEAD_OBJECT after the audio HAL reloads, ERROR_INVALID_OPERATION
//      after the system reclaims resources).
//   2. The MediaProjection itself dies. Handled by ProjectionDeathHandler;
//      we implement [ProjectionDeathListener] so the lifecycle can be
//      notified by the same code path.
//
// For (1): rebuild the AudioRecord from the existing projection and
// restart. Honour a rolling backoff so we don't tight-loop against a
// hostile HAL.
// For (2): delegate to the trampoline re-launch flow (UiRecoveryDaemon
// in higher layers — we just fire the listener).
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.7 +
// doc/MEDIA_PROJECTION_FLOW.md §Mitigation 3 (response sequence step 5).

package com.vyzorix.audiorouter.services.capture

import android.Manifest
import androidx.annotation.RequiresPermission
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Callback into higher layers when the recovery engine needs help. */
public interface TrampolineRecoveryCallback {
    /** Called when the engine wants the trampoline re-launched. */
    public fun requestTrampolineRelaunch(reason: String)

    /** Called when the engine has given up on this projection. */
    public fun fallbackToVoipOnly(reason: String)
}

/**
 * Owns the recovery loop. Stateless per-attempt; backoff state lives in
 * atomic counters so the engine is safe to call from multiple threads.
 */
public class CaptureRecoveryEngine(
    private val scope: CoroutineScope,
    private val session: MediaProjectionSession,
    private val captureFactory: PlaybackCaptureFactory,
    private val captureEngine: PlaybackCaptureEngine,
    private val deathHandler: ProjectionDeathHandler,
    private val callback: TrampolineRecoveryCallback,
    private val backoffSequenceMs: LongArray = DEFAULT_BACKOFF_SEQUENCE_MS,
) : ProjectionDeathListener {

    private val recovering: AtomicBoolean = AtomicBoolean(false)
    private val totalRecoveries: AtomicInteger = AtomicInteger(0)
    private val totalRebuilds: AtomicInteger = AtomicInteger(0)
    private var recoveryJob: Job? = null

    /** Total successful recovery cycles since process start. */
    public val totalSuccessfulRecoveries: Int get() = totalRecoveries.get()

    /** Total AudioRecord rebuilds attempted since process start. */
    public val totalRebuildAttempts: Int get() = totalRebuilds.get()

    /**
     * Kick off a recovery attempt for the "AudioRecord died but projection
     * is still alive" case. Idempotent — concurrent calls coalesce.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public fun requestRebuild(reason: String, config: AudioCaptureConfig) {
        if (!recovering.compareAndSet(false, true)) {
            DaemonLogger.get().info(TAG, "recovery.rebuild.dedup reason=$reason")
            return
        }
        recoveryJob = scope.launch {
            try {
                rebuildLoop(reason = reason, config = config)
            } finally {
                recovering.set(false)
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun rebuildLoop(reason: String, config: AudioCaptureConfig) {
        DaemonLogger.get().info(TAG, "recovery.rebuild.start reason=$reason")
        var attempt = 0
        while (scope.isActive) {
            attempt++
            totalRebuilds.incrementAndGet()
            val projection = session.activeProjection
            if (projection == null) {
                DaemonLogger.get().warn(TAG, "recovery.rebuild.no_projection attempt=$attempt")
                callback.requestTrampolineRelaunch(reason = "no_projection_during_rebuild")
                return
            }
            val buildResult = captureFactory.create(projection = projection, config = config)
            if (buildResult is CaptureBuildResult.Success) {
                val startResult = captureEngine.start(record = buildResult.record, config = config)
                if (startResult is CaptureStartResult.Started) {
                    totalRecoveries.incrementAndGet()
                    DaemonLogger.get().info(
                        TAG,
                        "recovery.rebuild.success attempt=$attempt totalSuccess=${totalRecoveries.get()}",
                    )
                    return
                } else {
                    DaemonLogger.get().warn(
                        TAG,
                        "recovery.rebuild.engine_failed attempt=$attempt " +
                            "reason=${(startResult as CaptureStartResult.Failed).reason}",
                    )
                    buildResult.record.release()
                }
            } else {
                DaemonLogger.get().warn(
                    TAG,
                    "recovery.rebuild.factory_failed attempt=$attempt " +
                        "reason=${(buildResult as CaptureBuildResult.Failed).reason}",
                )
            }
            if (attempt >= backoffSequenceMs.size) {
                DaemonLogger.get().warn(
                    TAG,
                    "recovery.rebuild.giving_up attempt=$attempt",
                )
                callback.fallbackToVoipOnly(reason = "rebuild_exhausted")
                return
            }
            val backoff = backoffSequenceMs[attempt - 1]
            DaemonLogger.get().info(TAG, "recovery.rebuild.backoff attempt=$attempt waitMs=$backoff")
            delay(backoff)
        }
    }

    // ProjectionDeathListener — when the projection dies, we don't try to
    // rebuild AudioRecord (token is dead). Instead, decide whether to
    // relaunch the trampoline or fall back.

    public override fun onProjectionDied() {
        val decision = deathHandler.recordRecoveryAttempt(success = false)
        DaemonLogger.get().info(
            TAG,
            "recovery.projection_died decision=$decision",
        )
        when (decision) {
            RecoveryDecision.RELAUNCH_TRAMPOLINE -> {
                callback.requestTrampolineRelaunch(reason = "projection_died")
            }
            RecoveryDecision.FALLBACK_VOIP_ONLY -> {
                callback.fallbackToVoipOnly(reason = "projection_died_exhausted")
            }
        }
    }

    public override fun onRecoveryGaveUp() {
        DaemonLogger.get().warn(TAG, "recovery.gave_up")
        callback.fallbackToVoipOnly(reason = "recovery_gave_up")
    }

    public companion object {
        /** 100 ms → 500 ms → 1 s → 2 s → 5 s. After this we fall back. */
        @JvmField
        public val DEFAULT_BACKOFF_SEQUENCE_MS: LongArray =
            longArrayOf(100L, 500L, 1_000L, 2_000L, 5_000L)
        private const val TAG: String = "CaptureRecoveryEngine"
    }
}
