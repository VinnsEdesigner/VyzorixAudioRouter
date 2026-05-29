// CaptureLifecycleController — top-level orchestrator for the Layer 4
// capture pipeline.
//
// Wires together:
//   - MediaProjectionSession — owns the live token.
//   - PlaybackCaptureFactory — builds AudioRecord against the token.
//   - PlaybackCaptureEngine — runs the capture loop.
//   - IdleCaptureController — silence-detects and pauses the engine.
//   - ProjectionDeathHandler — handles involuntary onStop() events.
//
// Public surface:
//   - [bootstrap]: called once on service create.
//   - [onTokenAcquired]: called from the trampoline result callback.
//   - [stop]: tear down.
//   - [observePlaybackFrame]: optional fast-path callback for higher
//     layers (e.g. the route forensics recorder).
//
// State machine:
//   IDLE → PROVISIONING (have token, building AudioRecord) → ACTIVE → PAUSED → ACTIVE
//   ACTIVE → STOPPED on revoke / death
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.6.

package com.vyzorix.audiorouter.services.capture

import android.Manifest
import android.content.Intent
import androidx.annotation.RequiresPermission
import com.vyzorix.audiorouter.audioengine.AudioPipelineController
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope

/** Tracks the high-level capture lifecycle state. */
public enum class CaptureLifecycleState {
    IDLE,
    PROVISIONING,
    ACTIVE,
    PAUSED,
    STOPPED,
    DEGRADED,
}

/**
 * Top-level capture pipeline orchestrator. One instance per service.
 */
public class CaptureLifecycleController(
    private val scope: CoroutineScope,
    private val session: MediaProjectionSession,
    private val captureFactory: PlaybackCaptureFactory,
    private val captureEngine: PlaybackCaptureEngine,
    private val tokenManager: ProjectionTokenManager,
    private val deathHandler: ProjectionDeathHandler,
    private val idleController: IdleCaptureController,
    private val pipelineController: AudioPipelineController,
    private val recoveryEngine: CaptureRecoveryEngine,
) : IdleCaptureListener {

    private val stateRef: AtomicReference<CaptureLifecycleState> = AtomicReference(CaptureLifecycleState.IDLE)
    private val configRef: AtomicReference<AudioCaptureConfig> = AtomicReference(AudioCaptureConfig.DEFAULT)

    /** Current lifecycle state. */
    public val state: CaptureLifecycleState
        get() = stateRef.get()

    /** Currently-active capture configuration. */
    public val captureConfig: AudioCaptureConfig
        get() = configRef.get()

    /**
     * Bootstrap dependencies and start the underlying audio pipeline. Called
     * exactly once on service create.
     */
    public fun bootstrap() {
        DaemonLogger.get().info(TAG, "lifecycle.bootstrap")
        idleController.bind(listener = this)
        deathHandler.bind(
            tokenManager = tokenManager,
            idleController = idleController,
            listener = recoveryEngine,
        )
        pipelineController.start()
    }

    /**
     * Called by the service (typically from the trampoline result callback)
     * once we have a fresh MediaProjection token. Transitions IDLE →
     * PROVISIONING → ACTIVE.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public fun onTokenAcquired(
        resultCode: Int,
        data: Intent,
        triggerOrigin: String,
        config: AudioCaptureConfig = AudioCaptureConfig.DEFAULT,
    ) {
        DaemonLogger.get().info(
            TAG,
            "lifecycle.token_acquired origin=$triggerOrigin rateHz=${config.sampleRateHz}",
        )
        stateRef.set(CaptureLifecycleState.PROVISIONING)
        configRef.set(config)
        val acquireResult = session.acquire(resultCode, data)
        if (acquireResult !is ProjectionAcquireResult.Success) {
            DaemonLogger.get().error(
                TAG,
                "lifecycle.provision.session_failed " +
                    "reason=${(acquireResult as ProjectionAcquireResult.Failed).reason}",
            )
            stateRef.set(CaptureLifecycleState.IDLE)
            return
        }
        tokenManager.recordGrant(
            resultCode = resultCode,
            triggerOrigin = triggerOrigin,
            config = config,
        )
        val buildResult = captureFactory.create(
            projection = acquireResult.projection,
            config = config,
        )
        if (buildResult !is CaptureBuildResult.Success) {
            DaemonLogger.get().error(
                TAG,
                "lifecycle.provision.factory_failed " +
                    "reason=${(buildResult as CaptureBuildResult.Failed).reason}",
            )
            session.release()
            stateRef.set(CaptureLifecycleState.IDLE)
            return
        }
        val startResult = captureEngine.start(record = buildResult.record, config = config)
        if (startResult !is CaptureStartResult.Started) {
            DaemonLogger.get().error(
                TAG,
                "lifecycle.provision.engine_failed " +
                    "reason=${(startResult as CaptureStartResult.Failed).reason}",
            )
            buildResult.record.release()
            session.release()
            stateRef.set(CaptureLifecycleState.IDLE)
            return
        }
        idleController.resume(reason = "lifecycle_start")
        stateRef.set(CaptureLifecycleState.ACTIVE)
        DaemonLogger.get().info(TAG, "lifecycle.active")
    }

    /** Reconfigure the active session with a new sample rate / channel set. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public fun reconfigure(newConfig: AudioCaptureConfig, reason: String) {
        DaemonLogger.get().info(
            TAG,
            "lifecycle.reconfigure reason=$reason newRate=${newConfig.sampleRateHz}",
        )
        val current = configRef.getAndSet(newConfig)
        if (current == newConfig) return
        val projection = session.activeProjection
        if (projection == null) {
            DaemonLogger.get().warn(TAG, "lifecycle.reconfigure.no_active_projection")
            return
        }
        captureEngine.stop()
        val buildResult = captureFactory.create(projection = projection, config = newConfig)
        if (buildResult !is CaptureBuildResult.Success) {
            DaemonLogger.get().error(
                TAG,
                "lifecycle.reconfigure.factory_failed " +
                    "reason=${(buildResult as CaptureBuildResult.Failed).reason}",
            )
            stateRef.set(CaptureLifecycleState.DEGRADED)
            return
        }
        val startResult = captureEngine.start(record = buildResult.record, config = newConfig)
        if (startResult !is CaptureStartResult.Started) {
            DaemonLogger.get().error(
                TAG,
                "lifecycle.reconfigure.engine_failed " +
                    "reason=${(startResult as CaptureStartResult.Failed).reason}",
            )
            buildResult.record.release()
            stateRef.set(CaptureLifecycleState.DEGRADED)
            return
        }
        stateRef.set(CaptureLifecycleState.ACTIVE)
    }

    /** Stop the capture pipeline. Idempotent. */
    public fun stop() {
        DaemonLogger.get().info(TAG, "lifecycle.stop")
        captureEngine.stop()
        session.release()
        idleController.pause(reason = "lifecycle_stop")
        stateRef.set(CaptureLifecycleState.STOPPED)
    }

    // IdleCaptureListener — pause/resume the engine in lockstep with idle.

    public override fun onPause(reason: String) {
        DaemonLogger.get().info(TAG, "lifecycle.idle.pause reason=$reason")
        captureEngine.pause()
        stateRef.compareAndSet(CaptureLifecycleState.ACTIVE, CaptureLifecycleState.PAUSED)
    }

    public override fun onResume(reason: String) {
        DaemonLogger.get().info(TAG, "lifecycle.idle.resume reason=$reason")
        captureEngine.resume()
        stateRef.compareAndSet(CaptureLifecycleState.PAUSED, CaptureLifecycleState.ACTIVE)
    }

    private companion object {
        const val TAG: String = "CaptureLifecycleController"
    }
}
