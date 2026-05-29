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
// Lifecycle:
//   onCreate — instantiate all sub-systems but DON'T touch AudioManager.
//   onStartCommand — promote to foreground, then call DaemonLifecycleManager.start().
//   onDestroy — DaemonLifecycleManager.shutdown() (releases AudioManager).

package com.vyzorix.audiorouter.services.foreground

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vyzorix.audiorouter.services.audio.AudioFocusHandler
import com.vyzorix.audiorouter.services.audio.AudioRouteWatcher
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.DaemonLifecycleManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceManager
import com.vyzorix.audiorouter.services.managers.WakeLockGuard
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import com.vyzorix.audiorouter.services.oem.VendorRouteResetter
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        lifecycle.start()
        // START_STICKY so that the OS re-creates the daemon if it kills us
        // for memory pressure — the Nokia C22 LMK strategy is aggressive.
        return START_STICKY
    }

    override fun onDestroy() {
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
            startForeground(
                ServiceNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(ServiceNotification.NOTIFICATION_ID, notification)
        }
    }
}
