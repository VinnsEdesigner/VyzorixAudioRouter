# VyzorixAudioRouter —  Repo Tree

```
VyzorixAudioRouter/
│
├── README.md                                              # Project overview, Nokia C22 notes, setup and service lifecycle
├── LICENSE                                                # Repository license
├── .gitignore                                             # Ignore local SDK/build/cache artifacts
├── .editorconfig                                          # Shared formatting conventions
├── .clang-format                                          # Native C++ formatting rules
├── .dockerignore                                          # Ignore Docker upload junk
├── .prettierignore                                        # Ignore formatting-sensitive/generated files
├── build.gradle.kts                                       # Root Gradle plugin/repository configuration
├── settings.gradle.kts                                    # Registers all project modules
├── gradle.properties                                      # JVM/Gradle tuning parameters
├── gradlew                                                # Unix Gradle wrapper
├── gradlew.bat                                            # Windows Gradle wrapper
│
├── gradle/
│   ├── libs.versions.toml                                 # Central dependency version catalog (Room, WorkManager, Coroutines, Retrofit, OkHttp, etc.)
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── app/                                                   # Final bootstrap APK module
│   ├── build.gradle.kts                                   # APK packaging, signing configs, dependency aggregation (Retrofit, OkHttp, etc.)
│   ├── proguard-rules.pro                                 # Keep rules for Accessibility + MediaProjection + services + Network
│   │                                                      # - Includes: -keepclasseswithmembernames class * { native <methods>; }
│   │                                                      # - Includes Retrofit/OkHttp model serialization rules
│   └── src/main/
│       ├── AndroidManifest.xml                            # Minimal launcher manifest
│       │                                                  # - Declares foregroundServiceType="mediaPlayback"
│       │                                                  # - Declares foregroundServiceType="dataSync"
│       │                                                  # - Declares BIND_ACCESSIBILITY_SERVICE permission
│       │                                                  # - Declares POST_NOTIFICATIONS permission
│       │                                                  # - Registers BootReceiver, PackageChangeReceiver
│       │                                                  # - Registers DiagnosticContentProvider
│       │                                                  # - Declares FileProvider (res/xml/file_paths)
│       │                                                  # - Declares REQUEST_INSTALL_PACKAGES
│       │                                                  # - Declares INTERNET, ACCESS_NETWORK_STATE
│       │                                                  # - BootstrapActivity initially enabled, disabled after Accessibility grant
│       ├── res/
│       │   ├── drawable/
│       │   │   ├── ic_service.xml                         # Persistent foreground notification icon (monochrome)
│       │   │   ├── ic_launcher_foreground.xml             # Lightweight launcher foreground icon
│       │   │   └── ic_notification_small.xml              # Monochrome status bar icon (A13 mandatory)
│       │   ├── mipmap-anydpi-v26/
│       │   │   ├── ic_launcher.xml                        # Adaptive launcher icon (foreground)
│       │   │   └── ic_launcher_background.xml             # Adaptive launcher icon background (A13 mandatory)
│       │   ├── values/
│       │   │   ├── strings.xml                            # Minimal user-facing text (app name, notifications, update prompts)
│       │   │   ├── colors.xml                             # Minimal UI colors for themes
│       │   │   ├── themes.xml                             # Lightweight no-animation themes (transparent)
│       │   │   ├── arrays.xml                             # String arrays for settings and dynamic options
│       │   │   ├── attrs.xml                              # Custom view attributes (if used in notification/overlay layouts)
│       │   │   └── notification_channels.xml              # Notification Channel definitions (IDs, names, importance)
│       │   └── xml/
│       │       ├── accessibility_service_config.xml       # Static Accessibility metadata (description, flags)
│       │       ├── accessibility_service_config_dynamic.xml # Runtime-modifiable accessibility configuration
│       │       ├── network_security_config.xml            # Network security policy (Render backend URL trust rules)
│       │       │                                          # - Defines <domain includeSubdomains="true">your-render-domain.com</domain>
│       │       │                                          # - Blocks cleartext traffic except localhost (debug only)
│       │       └── file_paths.xml                         # FileProvider paths for exporting crash bundles and APK installs
│       │                                                  # - <files-path name="diagnostics" path="diagnostics/" />
│       │                                                  # - <cache-path name="updates" path="updates/" />
│       │
│       ├── res/layout/                                    # RemoteViews Layouts for Notification Dashboard + Overlay
│       │   ├── notification_dashboard_collapsed.xml       # Compact view shown in status bar (Icon + Title + "Active" state)
│       │   ├── notification_dashboard_expanded.xml        # Full expanded view with ScrollView for detailed diagnostics
│       │   ├── notification_section_route.xml             # Tier 1 layout: Route status (Mode, Speaker, Headset)
│       │   ├── notification_section_capture.xml           # Tier 2 layout: Capture engine state (Buffer, Sample Rate)
│       │   ├── notification_section_health.xml            # Tier 3 layout: System health (Risk Score, Uptime)
│       │   ├── notification_section_diagnostics.xml       # Tier 3 layout: Crash signatures and last known state
│       │   ├── overlay_shortcut.xml                       # Layout for OverlayShortcutController (enable/disable toggle)
│       │   └── update_progress.xml                        # Layout for UpdateNotificationHandler (download progress bar)
│       │
│       └── kotlin/com/vyzorix/audiorouter/
│           ├── VyzorixApplication.kt                      # Application entry point
│           │                                              # - Registers GlobalExceptionHandler
│           │                                              # - Triggers VyzorixAppInitializer
│           │                                              # - Sets up strict mode (debug builds only)
│           │                                              # - Initializes Retrofit/OkHttp client for update server
│           ├── VyzorixAppInitializer.kt                   # Early-stage component initialization
│           │                                              # - Creates Notification Channels
│           │                                              # - Runs Room Database Migrations
│           │                                              # - Initializes Android Keystore
│           │                                              # - Loads AppConfig from SharedPreferences
│           │                                              # - Requests all runtime permissions via PermissionAutoGranter
│           ├── BootstrapActivity.kt                       # First-install only trampoline activity
│           │                                              # - Initially enabled in manifest
│           │                                              # - Intent: Settings.ACTION_ACCESSIBILITY_SETTINGS
│           │                                              # - Calls LauncherIconHider.nukeLauncherIcon() after grant
│           │                                              # - Disables itself via PackageManager after first run
│           ├── ProjectionPermissionActivity.kt            # One-shot MediaProjection grant trampoline
│           │                                              # - Starts projection intent
│           │                                              # - Waits for user grant
│           │                                              # - Passes token to ProjectionTokenManager
│           │                                              # - Activity.finish() (immediate)
│           └── AppExitDispatcher.kt                       # Immediate UI teardown utility
│                                                          # - Finishes all active activities
│                                                          # - Ensures process doesn't linger with UI surfaces
│                                                          # - Called after Accessibility grant and projection grant
│
├── core/
│   ├── common/                                            # Shared utility infrastructure
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── kotlin/com/vyzorix/audiorouter/common/
│   │           ├── constants/
│   │           │   ├── NotificationConstants.kt           # IDs for notification channels and dashboard updates
│   │           │   ├── PermissionConstants.kt             # Permission strings and request codes
│   │           │   ├── PrefKeys.kt                        # SharedPreferences key definitions
│   │           │   ├── BroadcastActions.kt                # Custom broadcast action strings
│   │           │   ├── FilePaths.kt                       # Storage paths for logs, exports, temp files, update cache
│   │           │   └── UpdateApiConstants.kt              # Server base URLs, API endpoints, version check intervals
│   │           ├── enums/
│   │           │   ├── DaemonState.kt                     # INSTALLED, BOOTSTRAP, INITIALIZING, PENDING, RUNNING, SAFE_MODE, RECOVERING, CRASHED, STOPPED
│   │           │   ├── CrashType.kt                       # SYSTEM_DIED, APP_BUG, NATIVE_FAILURE, TIMEOUT
│   │           │   ├── RouteState.kt                      # SPEAKER_FORCED, HEADSET_LOCKED, DRIFTING, UNKNOWN
│   │           │   ├── CaptureState.kt                    # ACTIVE, STARVED, BLOCKED, REVOKED, IDLE
│   │           │   ├── RiskLevel.kt                       # STABLE, ELEVATED, HIGH, CRITICAL
│   │           │   ├── FocusLossType.kt                   # TRANSIENT, TRANSIENT_CAN_DUCK, PERMANENT
│   │           │   └── UpdateState.kt                     # NOT_CHECKED, AVAILABLE, DOWNLOADING, DOWNLOADED, INSTALLING, SUCCESS, FAILED
│   │           ├── extensions/
│   │           │   ├── AudioManagerExtensions.kt          # Helpers: isSpeakerActive(), getCurrentModeName()
│   │           │   ├── ContextExtensions.kt               # Helpers: safeStartForeground(), safeGetSystemService()
│   │           │   ├── NotificationExtensions.kt          # Helpers: toRemoteViews(), applyTextStyle()
│   │           │   ├── AudioTrackExtensions.kt            # Helpers: isPlayingSafely(), writeWithRetry()
│   │           │   ├── AccessibilityExtensions.kt         # Helpers: extractDialogText(), getWindowPackageName()
│   │           │   ├── CursorExtensions.kt                # Helpers: toCrashEventList(), toRouteHistoryList()
│   │           │   └── NetworkExtensions.kt               # Helpers: isConnected(), isMetered(), getActiveNetworkType()
│   │           ├── model/
│   │           │   ├── DaemonStatus.kt                    # Unified status object for dashboard updates
│   │           │   ├── AudioRouteState.kt                 # Current routing state snapshot (mode, devices)
│   │           │   ├── CrashSignature.kt                  # Structured crash pattern data for analysis
│   │           │   ├── PermissionState.kt                 # Current grant/deny state for all permissions
│   │           │   ├── SessionMetadata.kt                 # Diagnostic session metadata (timestamps, counts)
│   │           │   ├── ThermalState.kt                    # Device thermal status and throttling level
│   │           │   └── UpdateInfo.kt                      # Server version info, release notes, download URL
│   │           ├── logging/
│   │           │   ├── Logger.kt                          # Unified Kotlin logging facade
│   │           │   ├── FileLogger.kt                      # Persistent disk logging (thread-safe)
│   │           │   └── LogcatBridge.kt                    # Lightweight logcat forwarding helper
│   │           ├── concurrency/
│   │           │   ├── AppDispatchers.kt                  # Coroutine dispatcher definitions (IO, Default, Main)
│   │           │   └── ServiceScope.kt                    # Long-lived service coroutine scope
│   │           ├── audio/
│   │           │   ├── AudioConstants.kt                  # Shared PCM/audio constants (Sample rates, buffer sizes)
│   │           │   ├── AudioBufferPool.kt                 # Shared reusable PCM buffers to reduce GC
│   │           │   └── AudioDeviceUtils.kt                # Audio route/device helper methods
│   │           ├── device/
│   │           │   ├── NokiaC22DeviceProfile.kt           # Nokia C22 heuristics and compatibility flags
│   │           │   ├── ZygoteCrashMitigator.kt            # Delays risky operations during startup
│   │           │   └── RuntimeHealthMonitor.kt            # Tracks process/runtime instability
│   │           └── utils/
│   │               ├── PermissionHelper.kt                # Runtime permission utility methods
│   │               ├── NotificationHelper.kt              # Foreground notification helpers
│   │               ├── IntentUtils.kt                     # Intent helper methods
│   │               ├── SafeHandler.kt                     # Exception-safe handler posting
│   │               ├── DelayedInitializer.kt              # Defers heavy startup tasks safely
│   │               ├── AppConfig.kt                       # Centralized configuration (feature flags, thresholds)
│   │               ├── NotificationChannelManager.kt      # Creates and configures notification channels (A13 mandatory)
│   │               ├── PermissionIntentHelper.kt          # Centralized PendingIntent creation
│   │               │                                      # - Handles FLAG_IMMUTABLE / FLAG_MUTABLE correctly
│   │               │                                      # - Prevents A12+ SecurityExceptions
│   │               └── UpdateDownloadClient.kt            # Shared HTTP download utility (used by services/updates/)
│   │                                                      # - Handles large file downloads with resume support
│   │                                                      # - Verifies SHA-256 checksum from server
│   │
│   ├── data/                                              # Persistent storage layer
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── kotlin/com/vyzorix/audiorouter/data/
│   │           ├── converters/
│   │           │   ├── AudioRouteTypeConverters.kt        # Converts AudioDeviceInfo, route enums to/from SQLite
│   │           │   ├── CrashEventTypeConverters.kt        # Converts crash signatures, timestamps, lists
│   │           │   ├── DaemonStateTypeConverters.kt       # Converts daemon state enums, complex objects
│   │           │   ├── DateTimeTypeConverters.kt          # Converts Instant/Long timestamps for all entities
│   │           │   └── UpdateStateTypeConverters.kt       # Converts UpdateState enum, download URLs, timestamps
│   │           ├── database/
│   │           │   ├── DaemonDatabase.kt                  # Room database definition
│   │           │   │                                      # - Stores crash bundles index
│   │           │   │                                      # - Route history transitions
│   │           │   │                                      # - Permission grant timestamps
│   │           │   │                                      # - Update state and download metadata
│   │           │   └── DaemonDatabaseMigrations.kt        # Schema version management
│   │           ├── dao/
│   │           │   ├── DaemonStateDao.kt                  # Room DAO for runtime state persistence
│   │           │   ├── CrashEventDao.kt                   # DAO for crash log entries
│   │           │   ├── RouteHistoryDao.kt                 # DAO for audio route transitions
│   │           │   └── UpdateStateDao.kt                  # DAO for update download/install history
│   │           ├── entity/
│   │           │   ├── CrashEvent.kt                      # @Entity for crash log entries
│   │           │   ├── RouteHistoryEntry.kt               # @Entity for audio route transitions
│   │           │   ├── DaemonStateSnapshot.kt             # @Entity for full daemon state
│   │           │   ├── PermissionGrantRecord.kt           # @Entity for permission history
│   │           │   └── UpdateRecord.kt                    # @Entity for update download/install tracking
│   │           └── repository/
│   │               ├── StateRepository.kt                 # Unified data access layer
│   │               ├── CrashEventRepository.kt            # CRUD operations for crash logs
│   │               ├── RouteHistoryRepository.kt          # CRUD operations for route history
│   │               └── UpdateRepository.kt                # CRUD operations for update state and history
│   │
│   ├── services/                                          # Main headless orchestration layer
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml                        # Module manifest
│   │       │                                              # - Declares <receiver> for BootReceiver (exported=true)
│   │       │                                              # - Declares <receiver> for PackageChangeReceiver
│   │       │                                              # - Declares <service> for PersistentAudioService (mediaPlayback)
│   │       │                                              # - Declares <service> for UpdateDownloadService (dataSync)
│   │       │                                              # - Declares <service> for TrampolineService
│   │       │                                              # - Declares <provider> for DiagnosticContentProvider
│   │       ├── aidl/
│   │       │   └── com/vyzorix/audiorouter/
│   │       │       └── IAudioRouterService.aidl           # IPC bridge definitions
│   │       ├── res/
│   │       │   └── xml/
│   │       │       └── accessibility_service_config.xml   # Accessibility service event subscriptions
│   │       └── kotlin/com/vyzorix/audiorouter/services/
│   │           │
│   │           ├── accessibility/
│   │           │   ├── RouterAccessibilityService.kt      # Primary daemon orchestrator entrypoint
│   │           │   │                                      # - Listens for UI events
│   │           │   │                                      # - Triggers boot sequence on enable
│   │           │   │                                      # - Calls LauncherIconHider.nukeLauncherIcon() on first grant
│   │           │   │                                      # - Starts BootStateRestorer if reboot detected
│   │           │   ├── AccessibilityEventRouter.kt        # Dispatches accessibility event handling to subsystems
│   │           │   ├── PermissionScreenWatcher.kt         # Detects system permission dialogs
│   │           │   ├── SettingsAutomation.kt              # Automates Accessibility/system setting taps
│   │           │   ├── OverlayPermissionAutomator.kt      # Watches overlay permission screens
│   │           │   ├── ProjectionPermissionAutomator.kt   # Detects MediaProjection grant prompts
│   │           │   ├── AudioRouteWatcher.kt               # Detects speaker/headset route changes
│   │           │   ├── UiRecoveryDaemon.kt                # Reopens crashed permission screens
│   │           │   ├── AccessibilityStateTracker.kt       # Tracks enabled/disabled service states
│   │           │   ├── AccessibilityConfigManager.kt      # Manages runtime accessibility capabilities
│   │           │   │                                      # - Toggles serviceInfo.flags dynamically
│   │           │   │                                      # - Disables UI watching during thermal throttle
│   │           │   ├── AccessibilityRecoveryHandler.kt    # Handles Accessibility permission stripped on reboot
│   │           │   │                                      # - Detects if service disabled after boot
│   │           │   │                                      # - Triggers UiRecoveryDaemon to reopen settings
│   │           │   │                                      # - Preserves diagnostic data across recovery
│   │           │   │                                      # - Resumes from LastKnownStateDumper snapshot
│   │           │   └── OverlayShortcutController.kt       # Manages system overlay shortcut for enable/disable
│   │           │                                          # - Draws TYPE_APPLICATION_OVERLAY window
│   │           │                                          # - Contains enable/disable toggle button
│   │           │                                          # - Responds to tap by toggling RouterAccessibilityService
│   │           │                                          # - Uses SYSTEM_ALERT_WINDOW permission
│   │           │                                          # - Hides automatically if Accessibility disabled
│   │           │
│   │           ├── audio/
│   │           │   ├── AudioFocusHandler.kt               # Manages audio focus requests, losses, gains
│   │           │   │                                      # - Handles AUDIOFOCUS_LOSS_TRANSIENT
│   │           │   │                                      # - Handles AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
│   │           │   │                                      # - Reclaims focus after interruptions end
│   │           │   ├── InterruptionPolicy.kt              # Defines reaction to different focus loss types
│   │           │   │                                      # - Call: Pause capture, maintain VoIP mode
│   │           │   │                                      # - Alarm: Duck volume, resume after
│   │           │   └── session/
│   │           │       ├── AudioSessionRegistry.kt        # Tracks active playback sessions
│   │           │       ├── SessionPriorityManager.kt      # Chooses dominant playback stream
│   │           │       ├── PlaybackUidTracker.kt          # Maps active app audio UIDs
│   │           │       └── CaptureEligibilityChecker.kt   # Checks if playback can be captured
│   │           │
│   │           ├── bootstrap/
│   │           │   ├── TrampolineService.kt               # Lightweight bootstrap foreground service
│   │           │   ├── BootstrapCoordinator.kt            # Waits for Accessibility + projection readiness
│   │           │   ├── PermissionStateMachine.kt          # Tracks initialization state transitions
│   │           │   ├── ServiceTrampoline.kt               # Hands execution to persistent daemon
│   │           │   ├── SelfDestructController.kt          # Stops temporary bootstrap services cleanly
│   │           │   ├── LauncherIconHider.kt               # Permanently disables launcher activity after Accessibility grant
│   │           │   │                                      # - Calls PackageManager.setComponentEnabledSetting(DISABLED)
│   │           │   │                                      # - Prevents user from tapping icon (avoids soft reboot)
│   │           │   │                                      # - Verifies icon hidden via queryIntentActivities()
│   │           │   └── BootStateRestorer.kt               # Restores daemon state after device reboot
│   │           │                                          # - Reads LastKnownStateDumper context from storage
│   │           │                                          # - Restores DaemonState to PENDING (not BOOTSTRAP)
│   │           │                                          # - Checks MediaProjection token validity
│   │           │                                          # - Resumes SpeakerForceEngine loop at previous state
│   │           │                                          # - Skips initialization steps already complete
│   │           │                                          # - Preserves all diagnostic data and crash bundles
│   │           │
│   │           ├── capture/
│   │           │   ├── MediaProjectionCaptureSession.kt   # Manages the actual capture session
│   │           │   ├── PlaybackCaptureEngine.kt           # Captures global media playback streams
│   │           │   ├── AudioCaptureConfig.kt              # Capture configuration models
│   │           │   ├── CapturePermissionStore.kt          # Persists MediaProjection consent state
│   │           │   ├── PlaybackCaptureFactory.kt          # Creates AudioPlaybackCaptureConfiguration
│   │           │   ├── CaptureLifecycleController.kt      # Handles capture start/stop lifecycle
│   │           │   ├── CaptureRecoveryEngine.kt           # Recovers from projection/audio failures
│   │           │   ├── ProjectionTokenManager.kt          # Manages token lifecycle, revocation, renewal
│   │           │   │                                      # - Handles onStop callbacks from system
│   │           │   │                                      # - Re-requests token on revocation
│   │           │   └── TokenPersistence.kt                # Encrypts and stores projection token metadata
│   │           │                                          # - Stores grant time, package, callback state
│   │           │
│   │           ├── compat/
│   │           │   ├── Android13Behavior.kt               # Android 13-specific API workarounds
│   │           │   ├── LegacyAudioFallback.kt             # Android 10/11 compatibility helpers
│   │           │   ├── ForegroundServiceCompat.kt         # Handles FG service API differences
│   │           │   ├── NotificationCompatBridge.kt        # Cross-version notification handling
│   │           │   └── AppInfoConfig.kt                   # Configures app to hide "Open" button in Settings > Apps
│   │           │                                          # - Removes CATEGORY_LAUNCHER intent filter after init
│   │           │                                          # - Sets android:exported="false" on MainActivity
│   │           │                                          # - Configures android:enabled="false" on launcher activity
│   │           │                                          # - Ensures only [Uninstall] [Disable] appear in App Info
│   │           │
│   │           ├── crash/
│   │           │   ├── GlobalExceptionHandler.kt          # Captures uncaught Kotlin exceptions
│   │           │   │                                      # Differentiates CAUSE: SYSTEM_DIED vs CAUSE: APP_BUG
│   │           │   ├── NativeCrashMarker.kt               # Heuristic marker for JNI/native failures
│   │           │   │                                      # Detects sigsegv/signal 11 in own app logs
│   │           │   ├── SoftRebootTracker.kt               # Aggregates timestamps to find instability patterns
│   │           │   │                                      # Maintains rolling buffer of last 5 reboots
│   │           │   └── LastKnownStateDumper.kt            # "Flight Data Recorder" for the daemon
│   │           │                                          # Continuously overwrites last_state.json
│   │           │                                          # Records audio mode, route, foreground package, uptime
│   │           │
│   │           ├── diagnostics/
│   │           │   ├── RoutingLogCollector.kt             # Captures route transition diagnostics
│   │           │   ├── AudioPolicySnapshot.kt             # Dumps AudioManager route states
│   │           │   ├── NokiaC22Compatibility.kt           # Nokia-specific fallback logic
│   │           │   ├── CrashTraceStore.kt                 # Persists crash traces between restarts
│   │           │   ├── SoftRebootDetector.kt              # Detects zygote/service collapse patterns
│   │           │   ├── RuntimeEventTimeline.kt            # Chronological daemon event tracking
│   │           │   └── system/
│   │           │       ├── AppLaunchObserver.kt           # Monitors correlation between new app launches
│   │           │       │                                  # Uses UsageStatsManager MOVE_TO_FOREGROUND
│   │           │       │                                  # Starts 10-second "survival timer" on launch
│   │           │       ├── WindowTransitionTracker.kt     # Watches for abnormal UI window behavior
│   │           │       │                                  # Detects "Flash Crash" (<500ms window life)
│   │           │       ├── PackageStateObserver.kt        # Differentiates fresh vs. established app crashes
│   │           │       │                                  # Logs "Fresh Instability" vs "Stability Failure"
│   │           │       ├── SoftRebootPredictor.kt         # Detects Zygote-style restarts via uptime anomalies
│   │           │       └── RendererFailureDetector.kt     # Detects GPU/SurfaceFlinger failures via "Visual Stasis"
│   │           │                                          # If foreground active but no CONTENT_CHANGED for >5s
│   │           │
│   │           ├── fallback/
│   │           │   ├── PlaybackCaptureFallback.kt         # Retries capture with alternate configs
│   │           │   ├── CommunicationModeFallback.kt       # VoIP-only speaker force mode
│   │           │   ├── SpeakerBypassFallback.kt           # Direct AudioTrack test playback
│   │           │   └── SilentRecoveryMode.kt              # Minimal mode after repeated failures
│   │           │
│   │           ├── foreground/
│   │           │   ├── PersistentAudioService.kt          # Main persistent foreground daemon
│   │           │   │                                      # - foregroundServiceType="mediaPlayback"
│   │           │   │                                      # - Starts after Accessibility grant
│   │           │   │                                      # - Coordinates all audio routing and capture
│   │           │   ├── ServiceNotification.kt             # Basic foreground notification management
│   │           │   ├── ServiceNotificationDashboard.kt    # Builds and updates RemoteViews with live status
│   │           │   │                                      # - Gathers data from DaemonStatusProvider
│   │           │   │                                      # - Pushes updates to NotificationManager every 10s
│   │           │   ├── SilentKeepAliveService.kt          # Hidden low-priority keepalive service
│   │           │   ├── ServiceHeartbeat.kt                # Watches daemon liveness
│   │           │   ├── ServiceRecoveryManager.kt          # Restarts crashed workers/services
│   │           │   ├── BootReceiver.kt                    # Optional reboot auto-restart receiver
│   │           │   └── actions/
│   │           │       ├── NotificationActionReceiver.kt  # Handles notification buttons
│   │           │       ├── QuickToggleAction.kt           # Enable/disable reroute instantly
│   │           │       ├── RestartPipelineAction.kt       # Restarts capture/playback safely
│   │           │       └── EmergencyStopAction.kt         # Kills daemon if instability occurs
│   │           │
│   │           ├── headless/
│   │           │   ├── HeadlessDaemonController.kt        # Main no-UI operational coordinator
│   │           │   ├── HeadlessBootSequence.kt            # Starts services without activity usage
│   │           │   ├── SilentPermissionFlow.kt            # Accessibility-driven permission handling
│   │           │   └── InvisibleRecoveryCoordinator.kt    # Restores pipeline after silent failures
│   │           │
│   │           ├── ipc/
│   │           │   ├── AudioRouterBinder.kt               # Binder service implementation
│   │           │   ├── ServiceConnectionManager.kt        # Internal service connection helper
│   │           │   └── DaemonCommandDispatcher.kt         # Dispatches IPC commands internally
│   │           │
│   │           ├── managers/
│   │           │   ├── AudioRouteManager.kt               # Centralized route authority layer
│   │           │   ├── ProjectionSessionManager.kt        # Owns MediaProjection lifecycle (token validity)
│   │           │   ├── DaemonLifecycleManager.kt          # Coordinates all subsystems (start/stop order)
│   │           │   ├── SpeakerForceManager.kt             # Single source of routing truth
│   │           │   └── RecoveryOrchestrator.kt            # Global recovery coordinator
│   │           │
│   │           ├── metrics/
│   │           │   ├── AudioLatencyMetrics.kt             # Measures capture->speaker latency
│   │           │   ├── RouteSwitchMetrics.kt              # Tracks reroute success/failure timing
│   │           │   ├── CrashMetrics.kt                    # Counts daemon/service crashes
│   │           │   ├── CapturePerformanceTracker.kt       # Detects capture starvation/dropouts
│   │           │   └── BatteryImpactMonitor.kt            # Estimates battery drain impact
│   │           │
│   │           ├── monitoring/
│   │           │   ├── HeadsetStateMonitor.kt             # Watches wired headset state
│   │           │   ├── BluetoothRouteMonitor.kt           # Watches Bluetooth route transitions
│   │           │   ├── AudioFocusMonitor.kt               # Detects focus changes/interruption
│   │           │   ├── PlaybackStateMonitor.kt            # Tracks active playback sessions
│   │           │   ├── DeviceThermalMonitor.kt            # Prevents overheating
│   │           │   ├── RuntimeMemoryMonitor.kt            # Watches memory pressure
│   │           │   ├── ProcessHealthMonitor.kt            # Detects service instability/restarts
│   │           │   └── NetworkStateMonitor.kt             # Monitors WiFi/cellular connectivity for updates
│   │           │                                          # - Detects internet availability
│   │           │                                          # - Triggers update checks when connection restored
│   │           │                                          # - Pauses downloads on metered networks (configurable)
│   │           │
│   │           ├── oem/
│   │           │   ├── NokiaAudioWorkarounds.kt           # Nokia-specific AudioManager retries
│   │           │   ├── UnisocPlatformTweaks.kt            # C22 chipset-specific timing hacks
│   │           │   ├── VendorRouteResetter.kt             # Reasserts route after OEM overrides
│   │           │   └── DeviceQuirkRegistry.kt             # Maps ROM-specific weirdness
│   │           │
│   │           ├── permissions/
│   │           │   ├── PermissionStateRepository.kt       # Persists granted/denied states
│   │           │   ├── PermissionRecoveryDaemon.kt        # Reopens missing permission flows
│   │           │   ├── OverlayPermissionManager.kt        # SYSTEM_ALERT_WINDOW helper
│   │           │   ├── NotificationPermissionManager.kt   # Android 13 POST_NOTIFICATIONS helper
│   │           │   ├── ProjectionGrantCache.kt            # Stores MediaProjection token state
│   │           │   └── PermissionAutoGranter.kt           # Requests and auto-grants all runtime permissions
│   │           │                                          # - POST_NOTIFICATIONS (A13 mandatory)
│   │           │                                          # - SYSTEM_ALERT_WINDOW (for overlay shortcut)
│   │           │                                          # - REQUEST_INSTALL_PACKAGES (for updates)
│   │           │                                          # - Verifies manifest-granted permissions
│   │           │                                          # - Uses ActivityResultContracts for non-UI requests
│   │           │
│   │           ├── playback/
│   │           │   ├── SpeakerPlaybackEngine.kt           # Main PCM replay engine to speaker
│   │           │   ├── AudioTrackController.kt            # Low-level AudioTrack management
│   │           │   ├── AudioTrackFactory.kt               # Creates optimized AudioTrack instances
│   │           │   ├── LatencyOptimizer.kt                # Dynamically tunes playback buffers
│   │           │   ├── RouteRecoveryEngine.kt             # Detects headset rerouting and retries speaker
│   │           │   ├── PlaybackGainController.kt          # Gain/volume normalization
│   │           │   ├── SpeakerOutputVerifier.kt           # Verifies speaker output state
│   │           │   ├── PlaybackThread.kt                  # Dedicated playback worker thread
│   │           │   └── UnderrunRecovery.kt                # Repairs AudioTrack underruns
│   │           │
│   │           ├── provider/
│   │           │   ├── DiagnosticContentProvider.kt       # Shares diagnostic data with other apps
│   │           │   │                                      # - Secure data export via Share Intent
│   │           │   └── AuthorityDefinitions.kt            # Defines content provider authorities
│   │           │
│   │           ├── receivers/                             # Broadcast Receivers
│   │           │   ├── NoOpReceiver.kt                    # Null-action receiver for non-clickable notification
│   │           │   ├── StatusRefreshReceiver.kt           # Forces immediate dashboard content refresh
│   │           │   ├── PackageChangeReceiver.kt           # Detects app installs/uninstalls (triggers observer)
│   │           │   ├── MediaButtonReceiver.kt             # Intercepts media button events (prevents route hijack)
│   │           │   └── ScreenStateReceiver.kt             # Monitors screen on/off (triggers thermal adjustments)
│   │           │
│   │           ├── resilience/
│   │           │   ├── AudioServerReconnectHandler.kt     # Detects/rebuilds after audioserver restart
│   │           │   ├── BinderRecoveryLoop.kt              # Rebinds failed service connections
│   │           │   ├── ThreadIsolationExecutor.kt         # Separates crash-prone workers
│   │           │   ├── DeadObjectRecovery.kt              # Handles binder DeadObjectException safely
│   │           │   └── WatchdogEscalationPolicy.kt        # Escalates recovery stages progressively
│   │           │
│   │           ├── scheduler/
│   │           │   ├── TaskScheduler.kt                   # Central delayed/repeating task coordinator
│   │           │   ├── TaskSchedulerFactory.kt            # Factory for creating constrained WorkManager workers
│   │           │   ├── WakeupAlarmCoordinator.kt          # AlarmManager fallback wake triggers
│   │           │   ├── DeferredStartupQueue.kt            # Delays risky startup operations safely
│   │           │   ├── IdleStateCoordinator.kt            # Handles Doze/App Standby transitions
│   │           │   ├── DeferredTaskWorker.kt              # WorkManager worker for safe delayed operations
│   │           │   ├── WorkerFactory.kt                   # Custom WorkManager factory for dependency injection
│   │           │   │                                      # - Provides DaemonDatabase and Repositories to Workers
│   │           │   └── WorkerConstraints.kt               # Defines battery, network, storage constraints
│   │           │
│   │           ├── security/
│   │           │   ├── ServicePermissionVerifier.kt       # Verifies runtime permissions before execution
│   │           │   ├── ProjectionTokenValidator.kt        # Checks projection token validity
│   │           │   ├── AccessibilityIntegrityChecker.kt   # Verifies service still enabled
│   │           │   ├── SafeIntentSanitizer.kt             # Prevents malformed intent crashes
│   │           │   ├── KeystoreManager.kt                 # Android Keystore for sensitive data encryption
│   │           │   │                                      # - Encrypts projection metadata
│   │           │   └── TokenEncryptor.kt                  # Encrypts/decrypts projection token metadata
│   │           │
│   │           ├── stability/
│   │           │   ├── CrashLoopProtector.kt              # Detects repeated startup crashes
│   │           │   ├── SafeModeController.kt              # Disables risky modules after failures
│   │           │   ├── StartupBackoffScheduler.kt         # Delays retries exponentially
│   │           │   └── ProcessRestartLimiter.kt           # Prevents service restart storms
│   │           │
│   │           ├── state/
│   │           │   ├── RuntimeStateStore.kt               # Persists active daemon state
│   │           │   ├── AudioRouteSnapshot.kt              # Last successful route config
│   │           │   ├── ProjectionStateStore.kt            # Projection lifecycle persistence
│   │           │   └── AccessibilityStateStore.kt         # Tracks daemon readiness state
│   │           │
│   │           ├── storage/
│   │           │   └── logs/
│   │           │       ├── LogFileRotator.kt              # Manages "Black Box" output files
│   │           │       │                                  # Rotates current_log.txt at 2MB limit
│   │           │       ├── CrashSnapshotExporter.kt       # Exports crash bundles for analysis
│   │           │       ├── TimestampedLogFormatter.kt     # Consistent structured log formatting
│   │           │       └── RuntimeSessionIndexer.kt       # Tracks daemon sessions chronologically
│   │           │
│   │           ├── testing/
│   │           │   ├── AudioRouteSimulation.kt            # Simulates headset/speaker transitions
│   │           │   ├── ProjectionStressTester.kt          # Tests projection recovery loops
│   │           │   ├── AccessibilityFlowTester.kt         # Tests permission automation logic
│   │           │   ├── SoftRebootRecoveryTester.kt        # Simulates process collapse recovery
│   │           │   ├── DiagnosticTestRunner.kt            # Runs diagnostic tests on device
│   │           │   ├── MockAccessibilityEvents.kt         # Simulates accessibility events for testing
│   │           │   └── SimulatedCrashTrigger.kt           # Triggers controlled crashes for testing
│   │           │
│   │           ├── updates/                               # Cloud Update System
│   │           │   ├── UpdateChecker.kt                   # Polls server API for version info
│   │           │   │                                      # - GET /api/v1/version
│   │           │   │                                      # - Compares remote version vs local BuildConfig.VERSION
│   │           │   │                                      # - Respects check interval from AppConfig
│   │           │   │                                      # - Triggers update flow if newer version available
│   │           │   ├── UpdateDownloader.kt                # Downloads APK via foreground service
│   │           │   │                                      # - Uses UpdateDownloadService (FOREGROUND_SERVICE_DATA_SYNC)
│   │           │   │                                      # - Downloads to context.cacheDir/updates/
│   │           │   │                                      # - Verifies SHA-256 checksum from server response
│   │           │   │                                      # - Reports progress to UpdateNotificationHandler
│   │           │   │                                      # - Supports resume on network interruption
│   │           │   ├── UpdateInstaller.kt                 # Triggers system install intent
│   │           │   │                                      # - Creates Intent.ACTION_INSTALL_PACKAGE
│   │           │   │                                      # - Uses FileProvider to share APK URI
│   │           │   │                                      # - System shows "Install this update?" dialog
│   │           │   │                                      # - Cannot bypass user confirmation (A13 security)
│   │           │   ├── UpdateConfig.kt                    # Server URLs, API endpoints, version comparison logic
│   │           │   │                                      # - Defines base URL (Render backend)
│   │           │   │                                      # - Defines API endpoints (/version, /changelog, /bin/)
│   │           │   │                                      # - Defines version comparison rules (semver)
│   │           │   │                                      # - Defines check intervals and retry policies
│   │           │   ├── UpdateStateMonitor.kt              # Monitors WiFi/cellular connectivity for update checks
│   │           │   │                                      # - Uses ConnectivityManager.NetworkCallback
│   │           │   │                                      # - Detects internet reachability (DNS ping)
│   │           │   │                                      # - Detects network type (WiFi vs Cellular)
│   │           │   │                                      # - Triggers update checks when connection restored
│   │           │   ├── UpdateStateStore.kt                # Persists update state across reboots
│   │           │   │                                      # - Stores: current check result, download progress
│   │           │   │                                      # - Stores: last check timestamp, install status
│   │           │   │                                      # - Uses Room database (UpdateStateDao)
│   │           │   │                                      # - Survives daemon restarts and soft reboots
│   │           │   └── UpdateNotificationHandler.kt       # Shows update progress/status in notification bar
│   │           │                                          # - "Update available" notification (tappable)
│   │           │                                          # - "Downloading... X%" progress notification
│   │           │                                          # - "Install ready" notification (triggers installer)
│   │           │                                          # - "Update failed" notification with retry option
│   │           │                                          # - Uses same notification channel as dashboard
│   │           │
│   │           ├── voip/
│   │               ├── SilentVoipSession.kt               # Maintains MODE_IN_COMMUNICATION
│   │               ├── CommunicationRouter.kt             # Aggressively reasserts speaker routing
│   │               ├── VoipAudioAnchor.kt                 # Silent looping AudioTrack anchor
│   │               ├── AudioModeKeeper.kt                 # Reapplies communication mode repeatedly
│   │               ├── SpeakerForceEngine.kt              # Keeps speaker route preferred
│   │               ├── CommunicationDeviceSelector.kt     # Selects built-in speaker output device
│   │               └── RoutePersistenceDaemon.kt          # Detects and repairs route fallback
│   │
│   ├── audioengine/                                       # Audio DSP/capture pipeline layer
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── cpp/
│   │       │   ├── CMakeLists.txt                         # Native audio build definitions
│   │       │   ├── capture_ring_buffer.cpp                # Lock-free PCM buffering
│   │       │   ├── playback_resampler.cpp                 # PCM resampling/alignment
│   │       │   ├── latency_tracker.cpp                    # Timing metrics collection
│   │       │   ├── pcm_mixer.cpp                          # PCM mixing/volume shaping
│   │       │   ├── underrun_guard.cpp                     # Playback underrun detection
│   │       │   ├── audio_clock_sync.cpp                   # Capture/playback clock synchronization
│   │       │   ├── logger_engine.cpp                      # Native logging support
│   │       │   ├── crash_guard.cpp                        # Wraps native processing safely
│   │       │   ├── safe_jni_bridge.cpp                    # Defensive JNI boundary handling
│   │       │   ├── watchdog_ping.cpp                      # Native heartbeat callbacks
│   │       │   └── include/
│   │       │       ├── ring_buffer.h
│   │       │       ├── audio_defs.h
│   │       │       ├── latency_tracker.h
│   │       │       ├── pcm_mixer.h
│   │       │       ├── clock_sync.h
│   │       │       ├── crash_guard.h                      # Native crash protection interfaces
│   │       │       ├── watchdog_ping.h                    # Native watchdog callbacks
│   │       │       ├── safe_jni_bridge.h                  # Safe JNI wrappers
│   │       │       └── audio_latency_profiler.h           # Native latency timing helpers
│   │       └── kotlin/com/vyzorix/audiorouter/audioengine/
│   │           ├── NativeAudioBridge.kt                   # JNI bridge wrapper
│   │           ├── NativeLoader.kt                        # Safe wrapper for System.loadLibrary("audioengine")
│   │           │                                          # - Catches UnsatisfiedLinkError gracefully
│   │           │                                          # - Logs failure and disables native pipeline
│   │           ├── AudioPipeline.kt                       # Capture -> processing -> playback pipeline
│   │           ├── PcmFrame.kt                            # Shared PCM frame container
│   │           ├── AudioPipelineController.kt             # Coordinates native + Kotlin audio stages
│   │           ├── PipelineStateTracker.kt                # Tracks pipeline operational state
│   │           └── NativeSafetyController.kt              # Guards JNI/native runtime stability
│   │
│   └── ui/                                                # Ultra-minimal trampoline UI layer
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/
│           │   ├── layout/
│           │   │   ├── activity_bootstrap.xml             # Tiny bootstrap permission layout
│           │   │   └── activity_projection.xml            # MediaProjection grant layout
│           │   ├── drawable/
│           │   │   ├── ic_speaker.xml
│           │   │   └── ic_permission.xml
│           │   └── values/
│           │       ├── strings.xml
│           │       ├── themes.xml
│           │       └── colors.xml
│           └── kotlin/com/vyzorix/audiorouter/ui/
│               ├── BootstrapActivity.kt                   # Opens Accessibility settings then exits
│               ├── ProjectionPermissionActivity.kt        # Requests MediaProjection permission then exits
│               ├── UiExitController.kt                    # Immediately destroys transient UI
│               ├── HeadlessModeLauncher.kt                # Hands execution to daemon layer
│               └── CrashSafeActivity.kt                   # Minimal fallback activity with hardware acceleration disabled
│
├── docs/
│   ├── ARCHITECTURE.md                                    # Complete daemon/service/audio architecture
│   ├── NOKIA_C22_NOTES.md                                 # Nokia C22-specific routing observations
│   ├── ACCESSIBILITY_FLOW.md                              # Accessibility-first daemon startup lifecycle
│   ├── MEDIA_PROJECTION_FLOW.md                           # Capture -> replay pipeline explanation
│   ├── VOIP_ROUTE_FORCE.md                                # MODE_IN_COMMUNICATION routing strategy
│   ├── SOFT_REBOOT_ANALYSIS.md                            # Notes about zygote/UI collapse behavior
│   ├── LATENCY_TUNING.md                                  # Audio latency/buffer optimization guide
│   ├── NOKIA_C22_ROUTE_BEHAVIOR.md                        # Device-specific audio quirks
│   ├── SERVICE_LIFECYCLE.md                               # Full daemon startup/shutdown lifecycle
│   ├── RECOVERY_MATRIX.md                                 # Failure -> recovery strategy mappings
│   ├── ACCESSIBILITY_AUTOMATION_RULES.md                  # UI automation trigger mappings
│   ├── PROJECTION_EDGE_CASES.md                           # Projection failure/recovery notes
│   ├── NOTIFICATION_DASHBOARD.md                          # Read-only status interface design
│   └── UPDATE_MECHANISM.md                                # Cloud update system, API contract, Render deployment
│
├── scripts/
│   ├── build_debug.sh                                     # Debug APK build helper
│   ├── build_release.sh                                   # Release build helper
│   ├── run_lint.sh                                        # Runs lint/detekt/static analysis
│   ├── profile_audio_latency.sh                           # Audio timing profiler
│   └── monitor_logcat.sh                                  # Watches runtime crashes/restarts
│
├── config/
│   └── lint/
│       ├── lint.xml                                       # Android lint configuration
│       └── detekt.yml                                     # Kotlin static analysis rules
│
└── .github/
    └── workflows/
        ├── android_build.yml                              # CI APK compilation workflow
        ├── lint.yml                                       # Static analysis CI checks
        ├── release.yml                                    # Tagged release packaging workflow
        └── push_update_bin.yml                            # Pushes APK binary to server repo bin/ folder
```

---
