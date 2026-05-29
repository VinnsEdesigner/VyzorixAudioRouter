// ProjectionTokenManager — owns the in-process MediaProjection lifecycle
// bookkeeping.
//
// Per doc/MEDIA_PROJECTION_FLOW.md §Mitigation 3 the manager is INTENTIONALLY
// distinct from [ProjectionDeathHandler]. This class tracks the general
// lifecycle (grant / refresh / explicit revoke / persist); the death
// handler is a single-purpose listener for the involuntary
// `MediaProjection.Callback.onStop()` callback.
//
// Threading: single AtomicReference holds the active token snapshot. All
// public methods are non-suspending — persistence I/O is fanned out to
// a coroutine on the supplied scope, so callers can update bookkeeping
// from anywhere (binder thread, accessibility callback, etc.) without
// blocking.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.8.

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.common.audio.AudioConstants
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Snapshot of the manager's current token state. */
public data class ProjectionTokenSnapshot(
    public val resultCode: Int?,
    public val grantedAtEpochMs: Long?,
    public val triggerOrigin: String?,
    public val isActive: Boolean,
)

/** Lifecycle event stream emitted by [ProjectionTokenManager]. */
public sealed interface ProjectionTokenEvent {
    public data class Granted(
        public val resultCode: Int,
        public val grantedAtEpochMs: Long,
        public val triggerOrigin: String,
    ) : ProjectionTokenEvent

    public data class Revoked(
        public val revokedAtEpochMs: Long,
        public val reason: String,
    ) : ProjectionTokenEvent

    public object Cleared : ProjectionTokenEvent
}

/**
 * In-process bookkeeping for the MediaProjection token. Persistence is
 * fanned out via [TokenPersistence] + [CapturePermissionStore].
 */
public class ProjectionTokenManager(
    private val scope: CoroutineScope,
    private val permissionStore: CapturePermissionStore,
    private val tokenPersistence: TokenPersistence,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val tokenRef: AtomicReference<ProjectionTokenSnapshot> = AtomicReference(
        ProjectionTokenSnapshot(
            resultCode = null,
            grantedAtEpochMs = null,
            triggerOrigin = null,
            isActive = false,
        ),
    )

    private val _events: MutableSharedFlow<ProjectionTokenEvent> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 16)

    /** Lifecycle event stream — `replay=1` so late subscribers see the latest. */
    public val events: SharedFlow<ProjectionTokenEvent> = _events.asSharedFlow()

    /** Read the current token state. */
    public fun currentSnapshot(): ProjectionTokenSnapshot = tokenRef.get()

    /**
     * Record that the trampoline activity captured a fresh token from the
     * system dialog. Updates the in-process snapshot, persists descriptive
     * metadata async, and fans out a Granted event.
     */
    public fun recordGrant(
        resultCode: Int,
        triggerOrigin: String,
        config: AudioCaptureConfig,
    ) {
        val now = clock()
        tokenRef.set(
            ProjectionTokenSnapshot(
                resultCode = resultCode,
                grantedAtEpochMs = now,
                triggerOrigin = triggerOrigin,
                isActive = true,
            ),
        )
        DaemonLogger.get().info(
            TAG,
            "token.grant origin=$triggerOrigin resultCode=$resultCode " +
                "rateHz=${config.sampleRateHz} ch=${config.channelCount}",
        )
        scope.launch {
            permissionStore.recordGrant(
                grantedAtEpochMs = now,
                sampleRateHz = config.sampleRateHz,
                channelCount = config.channelCount,
                triggerOrigin = triggerOrigin,
            )
            tokenPersistence.persist(
                PersistedProjectionMetadata(
                    grantedAtEpochMs = now,
                    sampleRateHz = config.sampleRateHz,
                    channelCount = config.channelCount,
                    triggerOrigin = triggerOrigin,
                ),
            )
        }
        _events.tryEmit(
            ProjectionTokenEvent.Granted(
                resultCode = resultCode,
                grantedAtEpochMs = now,
                triggerOrigin = triggerOrigin,
            ),
        )
    }

    /**
     * Record an involuntary or voluntary revoke. Called either by
     * [ProjectionDeathHandler] (system-initiated tear-down) or by an
     * explicit caller (e.g. user-driven stop).
     */
    public fun recordRevoke(reason: String) {
        val now = clock()
        val previous = tokenRef.getAndSet(
            ProjectionTokenSnapshot(
                resultCode = null,
                grantedAtEpochMs = null,
                triggerOrigin = null,
                isActive = false,
            ),
        )
        DaemonLogger.get().warn(
            TAG,
            "token.revoke reason=$reason wasActive=${previous.isActive}",
        )
        scope.launch {
            permissionStore.recordRevoke(revokedAtEpochMs = now)
        }
        _events.tryEmit(
            ProjectionTokenEvent.Revoked(
                revokedAtEpochMs = now,
                reason = reason,
            ),
        )
    }

    /** Wipe in-memory and persisted state. */
    public fun clear() {
        tokenRef.set(
            ProjectionTokenSnapshot(
                resultCode = null,
                grantedAtEpochMs = null,
                triggerOrigin = null,
                isActive = false,
            ),
        )
        scope.launch {
            permissionStore.clear()
            tokenPersistence.clear()
        }
        _events.tryEmit(ProjectionTokenEvent.Cleared)
    }

    /**
     * Quick check for `BootStateRestorer` — returns the persisted capture
     * config if any. Always returns the [AudioCaptureConfig.DEFAULT] on
     * decrypt / parse failure (callers will re-acquire the token
     * via the trampoline anyway, so the config choice doesn't strictly
     * matter — but defaulting to the well-known config is the safe play).
     */
    public suspend fun readPersistedConfig(): AudioCaptureConfig {
        val persisted = tokenPersistence.read() ?: return AudioCaptureConfig.DEFAULT
        val sampleRateHz = persisted.sampleRateHz
            .takeIf { it in AudioCaptureConfig.SUPPORTED_SAMPLE_RATES_HZ }
            ?: AudioConstants.SAMPLE_RATE_HZ
        val channelCount = persisted.channelCount
            .takeIf { it == AudioConstants.CHANNEL_COUNT_MONO || it == AudioConstants.CHANNEL_COUNT_STEREO }
            ?: AudioConstants.CHANNEL_COUNT_MONO
        return AudioCaptureConfig.Builder()
            .sampleRateHz(sampleRateHz)
            .channelCount(channelCount)
            .build()
    }

    private companion object {
        const val TAG: String = "ProjectionTokenManager"
    }
}
