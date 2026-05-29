// PersistentAudioService — Layer-3 foreground service that owns the
// daemon's process lifetime.
//
// Per doc/BUILD_ORDER.md Layer 3:
//   "PersistentAudioService.kt — foregroundServiceType=mediaPlayback".
//
// (mediaProjection is the L4 type, not L3 — capture isn't wired yet.)
//
// The service wires the Layer 3 sub-systems in the order specified by
// doc/VOIP_ROUTE_FORCE.md §3 Phase 1:
//   1. AudioFocusHandler — request VOICE_COMMUNICATION focus
//   2. SpeakerForceManager.engage() — engage MODE_IN_COMMUNICATION + speaker
//   3. SilentVoipSession.start() — push silent frames as the anchor
//   4. RoutePersistenceDaemon — start the drift watchdog
//
// Layer 3.5 (this revision) adds:
//   - WakeLockGuard handed to SpeakerForceManager so PARTIAL_WAKE_LOCK is
//     held only while the engine is engaged.
//   - DaemonStorageProvider + DaemonStateRecorder so SpeakerForceManager
//     can persist state transitions for forensics.
//   - DaemonLogger installed as the process-wide FileLogger so all daemon
//     output lands in the rolling 2 MiB log files that the LogExportReceiver
//     can later bundle.
//   - VendorRouteResetter is injected into RoutePersistenceDaemon so the
//     HEADSET_HIJACK storm can escalate to HAL reset.
//
// Layer 4 (this revision) adds:
//   - CaptureLifecycleController coordinates MediaProjectionSession,
//     PlaybackCaptureFactory, PlaybackCaptureEngine, IdleCaptureController
//     and ProjectionDeathHandler.
//   - ProjectionResultReceiver listens for the broadcast emitted by
//     ProjectionPermissionActivity after the user grants a token.
//   - SpeakerPlaybackEngine is wired as the FrameSink so captured PCM is
//     piped back out through the route-forced AudioTrack.
//
// Lifecycle:
//   onCreate — instantiate all sub-systems but DON'T touch AudioManager.
//   onStartCommand — promote to foreground, then call DaemonLifecycleManager.start().
//   onDestroy — DaemonLifecycleManager.shutdown() (releases AudioManager).

package com.vyzorix.audiorouter.services.foreground

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import com.vyzorix.audiorouter.audioengine.AudioPipelineController
import com.vyzorix.audiorouter.services.audio.AudioFocusHandler
import com.vyzorix.audiorouter.services.audio.AudioRouteWatcher
import com.vyzorix.audiorouter.services.capture.AudioCaptureConfig
import com.vyzorix.audiorouter.services.capture.CaptureLifecycleController
import com.vyzorix.audiorouter.services.capture.CapturePermissionStore
import com.vyzorix.audiorouter.services.capture.CaptureRecoveryEngine
import com.vyzorix.audiorouter.services.capture.IdleCaptureController
import com.vyzorix.audiorouter.services.capture.MediaProjectionSession
import com.vyzorix.audiorouter.services.capture.PlaybackCaptureEngine
import com.vyzorix.audiorouter.services.capture.PlaybackCaptureFactory
import com.vyzorix.audiorouter.services.capture.ProjectionDeathHandler
import com.vyzorix.audiorouter.services.capture.ProjectionPermissionContract
import com.vyzorix.audiorouter.services.capture.ProjectionTokenManager
import com.vyzorix.audiorouter.services.capture.TokenPersistence
import com.vyzorix.audiorouter.services.capture.TrampolineRecoveryCallback
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.DaemonLifecycleManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceManager
import com.vyzorix.audiorouter.services.managers.WakeLockGuard
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import com.vyzorix.audiorouter.services.oem.VendorRouteResetter
import com.vyzorix.audiorouter.services.permissions.ProjectionGrantCache
import com.vyzorix.audiorouter.services.playback.AudioTrackFactory
import com.vyzorix.audiorouter.services.playback.LatencyOptimizer
import com.vyzorix.audiorouter.services.playback.SpeakerPlaybackEngine
import com.vyzorix.audiorouter.services.playback.UnderrunRecovery
import com.vyzorix.audiorouter.services.state.DaemonStateRecorder
import com.vyzorix.audiorouter.services.state.DaemonStorageProvider
import com.vyzorix.audiorouter.services.voip.CommunicationRouter
import com.vyzorix.audiorouter.services.voip.RoutePersistenceDaemon
import com.vyzorix.audiorouter.services.voip.SilentVoipSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Foreground service that holds the audio-routing daemon's process. */
public class PersistentAudioService : Service() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var profile: NokiaC22DeviceProfile
    private lateinit var routeManager: AudioRouteManager
    private lateinit var focusHandler: AudioFocusHandler
    private lateinit var routeWatcher: AudioRouteWatcher
    private lateinit var wakeLockGuard: WakeLockGuard
    private lateinit var storageProvider: DaemonStorageProvider
    private lateinit var stateRecorder: DaemonStateRecorder
    private lateinit var speakerForceManager: SpeakerForceManager
    private lateinit var silentVoipSession: SilentVoipSession
    private lateinit var vendorRouteResetter: VendorRouteResetter
    private lateinit var routePersistenceDaemon: RoutePersistenceDaemon
    private lateinit var lifecycle: DaemonLifecycleManager
    /** Exposed for the dashboard wiring in Layer 5+. */
    public lateinit var router: CommunicationRouter
        private set

    // Layer 4 — Capture pipeline.
    private lateinit var pipelineController: AudioPipelineController
    private lateinit var mediaProjectionSession: MediaProjectionSession
    private lateinit var captureFactory: PlaybackCaptureFactory
    private lateinit var underrunRecovery: UnderrunRecovery
    private lateinit var latencyOptimizer: LatencyOptimizer
    private lateinit var speakerPlaybackEngine: SpeakerPlaybackEngine
    private lateinit var playbackCaptureEngine: PlaybackCaptureEngine
    private lateinit var capturePermissionStore: CapturePermissionStore
    private lateinit var tokenPersistence: TokenPersistence
    private lateinit var tokenManager: ProjectionTokenManager
    private lateinit var projectionDeathHandler: ProjectionDeathHandler
    private lateinit var idleCaptureController: IdleCaptureController
    private lateinit var grantCache: ProjectionGrantCache
    private lateinit var captureRecoveryEngine: CaptureRecoveryEngine
    /** Public for tests; wired in onCreate. */
    public lateinit var captureLifecycleController: CaptureLifecycleController
        private set

    private val projectionResultReceiver: BroadcastReceiver = ProjectionResultReceiver()

    override fun onCreate() {
        super.onCreate()
        // Install the disk-backed logger first so every subsequent step lands
        // in the rolling log file the LogExportReceiver will zip.
        DaemonLogger.install(this)
        profile = NokiaC22DeviceProfile.current()
        routeManager = AudioRouteManager(this)
        focusHandler = AudioFocusHandler(this)
        routeWatcher = AudioRouteWatcher(this)
        wakeLockGuard = WakeLockGuard(this)
        storageProvider = DaemonStorageProvider(this, profile)
        stateRecorder = DaemonStateRecorder(scope, storageProvider.daemonStateRepository)
        speakerForceManager = SpeakerForceManager(
            scope = scope,
            routeManager = routeManager,
            profile = profile,
            wakeLockGuard = wakeLockGuard,
            stateRecorder = stateRecorder,
        )
        silentVoipSession = SilentVoipSession()
        vendorRouteResetter = VendorRouteResetter(routeManager, profile)
        routePersistenceDaemon = RoutePersistenceDaemon(
            routeManager = routeManager,
            silentVoipSession = silentVoipSession,
            vendorRouteResetter = vendorRouteResetter,
        )
        lifecycle = DaemonLifecycleManager(
            scope = scope,
            focusHandler = focusHandler,
            routeWatcher = routeWatcher,
            speakerForceManager = speakerForceManager,
            silentVoipSession = silentVoipSession,
            routePersistenceDaemon = routePersistenceDaemon,
        )
        router = CommunicationRouter(speakerForceManager, silentVoipSession)

        // Layer 4 — Capture pipeline. Construction order: the death
        // handler must exist before the session, so the session can wire
        // the framework callback through to it.
        pipelineController = AudioPipelineController()
        projectionDeathHandler = ProjectionDeathHandler()
        mediaProjectionSession = MediaProjectionSession(
            context = this,
            deathHandler = projectionDeathHandler,
        )
        captureFactory = PlaybackCaptureFactory()
        latencyOptimizer = LatencyOptimizer()
        underrunRecovery = UnderrunRecovery(latencyOptimizer = latencyOptimizer)
        speakerPlaybackEngine = SpeakerPlaybackEngine(
            scope = scope,
            trackFactory = AudioTrackFactory(),
            underrunRecovery = underrunRecovery,
            latencyOptimizer = latencyOptimizer,
        )
        playbackCaptureEngine = PlaybackCaptureEngine(
            scope = scope,
            frameSink = speakerPlaybackEngine,
        )
        capturePermissionStore = CapturePermissionStore(
            projectionMetadataStore = storageProvider.projectionMetadataStore,
        )
        tokenPersistence = TokenPersistence(
            tokenEncryptor = storageProvider.tokenEncryptor,
        )
        tokenManager = ProjectionTokenManager(
            scope = scope,
            permissionStore = capturePermissionStore,
            tokenPersistence = tokenPersistence,
        )
        idleCaptureController = IdleCaptureController()
        grantCache = ProjectionGrantCache()
        captureRecoveryEngine = CaptureRecoveryEngine(
            scope = scope,
            session = mediaProjectionSession,
            captureFactory = captureFactory,
            captureEngine = playbackCaptureEngine,
            deathHandler = projectionDeathHandler,
            callback = object : TrampolineRecoveryCallback {
                override fun requestTrampolineRelaunch(reason: String) {
                    DaemonLogger.get().info(SERVICE_TAG, "recovery.trampoline_request reason=$reason")
                }
                override fun fallbackToVoipOnly(reason: String) {
                    DaemonLogger.get().warn(SERVICE_TAG, "recovery.voip_only reason=$reason")
                }
            },
        )
        captureLifecycleController = CaptureLifecycleController(
            scope = scope,
            session = mediaProjectionSession,
            captureFactory = captureFactory,
            captureEngine = playbackCaptureEngine,
            tokenManager = tokenManager,
            deathHandler = projectionDeathHandler,
            idleController = idleCaptureController,
            pipelineController = pipelineController,
            recoveryEngine = captureRecoveryEngine,
        )
        captureLifecycleController.bootstrap()
        speakerPlaybackEngine.start()
        registerProjectionReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        lifecycle.start()
        // START_STICKY so that the OS re-creates the daemon if it kills us
        // for memory pressure — the Nokia C22 LMK strategy is aggressive.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { captureLifecycleController.stop() }
        runCatching { speakerPlaybackEngine.stop() }
        runCatching { pipelineController.stop() }
        runCatching { unregisterReceiver(projectionResultReceiver) }
        lifecycle.shutdown()
        // SupervisorJob.cancel() in shutdown() may have already cancelled
        // the scope, but call again defensively.
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Wrap startForeground with the right foregroundServiceType for A14+. */
    private fun promoteToForeground() {
        val notification = ServiceNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Layer 4: combine mediaPlayback + mediaProjection so the
            // foreground-service-type matches both subsystems.
            startForeground(
                ServiceNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(ServiceNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun registerProjectionReceiver() {
        val filter = IntentFilter(
            ProjectionPermissionContract.ACTION_PROJECTION_RESULT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(projectionResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(projectionResultReceiver, filter)
        }
    }

    /** Receives the projection grant from [ProjectionPermissionActivity]. */
    private inner class ProjectionResultReceiver : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val resultCode = intent.getIntExtra(
                ProjectionPermissionContract.EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED,
            )
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    ProjectionPermissionContract.EXTRA_RESULT_DATA,
                    Intent::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(
                    ProjectionPermissionContract.EXTRA_RESULT_DATA,
                )
            }
            val triggerOrigin = intent.getStringExtra(
                ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN,
            ) ?: ProjectionPermissionContract.ORIGIN_UNKNOWN
            DaemonLogger.get().info(
                SERVICE_TAG,
                "projection.result code=$resultCode origin=$triggerOrigin dataPresent=${data != null}",
            )
            if (resultCode != Activity.RESULT_OK || data == null) {
                DaemonLogger.get().warn(SERVICE_TAG, "projection.result.denied code=$resultCode")
                return
            }
            val config = AudioCaptureConfig.DEFAULT
            grantCache.recordGrant(
                triggerOrigin = triggerOrigin,
                sampleRateHz = config.sampleRateHz,
                channelCount = config.channelCount,
            )
            captureLifecycleController.onTokenAcquired(
                resultCode = resultCode,
                data = data,
                triggerOrigin = triggerOrigin,
                config = config,
            )
        }
    }

    private companion object {
        const val SERVICE_TAG: String = "PersistentAudioService"
    }
}
