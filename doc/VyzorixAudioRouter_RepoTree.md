# VyzorixAudioRouter — 

```

VyzorixAudioRouter/

│
├── README.md                                              # Project overview + doc index (entry point on GitHub)
├── LICENSE                                                # Repository License
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
│   ├── libs.versions.toml                                 # Central dependency version catalog (Room, WorkManager, Coroutines, Retrofit, OkHttp, SQLCipher, Firebase, etc.)
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── app/
│   ├── build.gradle.kts                                   # APK packaging, signing configs, dependency aggregation (Retrofit, OkHttp, Firebase, google-services plugin, etc.)
│   ├── google-services.json                               # Firebase project config — required by com.google.gms.google-services Gradle plugin at build time; fatal build error without it; downloaded from Firebase Console after registering package com.vyzorix.audiorouter
│   ├── proguard-rules.pro                                 # Keep rules for Accessibility + MediaProjection + services + Network
│   │                                                      # - Includes: -keepclasseswithmembernames class * { native <methods>; }
│   │                                                      # - Includes Retrofit/OkHttp model serialization rules
│   └── src/main/
│       ├── AndroidManifest.xml                            # Master application manifest coordinating accessibility binding, system queries, package visibility, and required foregroundServiceType declarations (mediaPlayback, dataSync) to satisfy Android 13 background constraints
│       ├── res/
│       │   ├── drawable/
│       │   │   ├── ic_service.xml                         # Persistent foreground notification icon (monochrome)
│       │   │   ├── ic_launcher_foreground.xml             # Lightweight launcher foreground icon
│       │   │   └── ic_notification_small.xml              # Monochrome status bar icon (A13 mandatory)
│       │   ├── mipmap-anydpi-v26/
│       │   │   ├── ic_launcher.xml                        # Adaptive launcher icon foreground
│       │   │   └── ic_launcher_background.xml             # Adaptive launcher icon background (A13 mandatory)
│       │   ├── values/
│       │   │   ├── strings.xml                            # Minimal user-facing text (app name, notifications, update prompts)
│       │   │   ├── colors.xml                             # Minimal UI colors for themes
│       │   │   ├── themes.xml                             # Lightweight no-animation themes (transparent)
│       │   │   ├── arrays.xml                             # String arrays for settings and dynamic options
│       │   │   ├── attrs.xml                              # Custom view attributes for notification/overlay layouts
│       │   │   ├── notification_channels.xml              # Notification Channel definitions (IDs, names, importance)
│       │   │   ├── ids.xml                                # Stable IDs for RemoteViews
│       │   │   ├── bools.xml                              # Feature toggles by build type/device
│       │   │   ├── integers.xml                           # Timing defaults / polling intervals
│       │   │   └── config.xml                             # Runtime-safe XML defaults
│       │   └── xml/
│       │       ├── accessibility_service_config.xml       # Static Accessibility metadata (description, flags)
│       │       ├── accessibility_service_config_dynamic.xml # Runtime-modifiable accessibility configuration
│       │       ├── network_security_config.xml            # Network security policy (Render backend URL trust rules)
│       │       │                                          # - <domain includeSubdomains="true">vyzorix-update-server.onrender.com</domain>
│       │       │                                          # - Blocks cleartext traffic except localhost
│       │       ├── file_paths.xml                         # FileProvider paths for crash bundles and APK installs
│       │       │                                          # - <files-path name="diagnostics" path="diagnostics/" />
│       │       │                                          # - <cache-path name="updates" path="updates/" />
│       │       ├── backup_rules.xml                       # Android Auto Backup rules
│       │       ├── data_extraction_rules.xml              # Android 12+ data extraction policy
│       │       ├── provider_paths.xml                     # FileProvider export paths
│       │       ├── notification_permission_flow.xml       # Notification rationale flow metadata
│       │       └── accessibility_gesture_map.xml          # Accessibility automation action map
│       │
│       ├── res/layout/
│       │   ├── notification_dashboard_collapsed.xml       # Compact view shown in status bar (Icon + Title + "Active" state)
│       │   ├── notification_dashboard_expanded.xml        # Full expanded view with ScrollView for detailed diagnostics
│       │   ├── notification_section_route.xml             # Tier 1: Route status (Mode, Speaker, Headset)
│       │   ├── notification_section_capture.xml           # Tier 2: Capture engine state (Buffer, Sample Rate)
│       │   ├── notification_section_health.xml            # Tier 3: System health (Risk Score, Uptime)
│       │   ├── notification_section_diagnostics.xml       # Tier 3: Crash signatures and last known state
│       │   ├── overlay_shortcut.xml                       # Layout for OverlayShortcutController (enable/disable toggle)
│       │   └── update_progress.xml                        # Layout for UpdateNotificationHandler (download progress bar)
│       │
│       └── raw/
│       |    └── silent_anchor.wav                          # Silent VoIP anchor sample played by FocusPersistenceEngine via USAGE_VOICE_COMMUNICATION to maintain focus lock
│       |                                                   # - Accessed by core/services via RawResourceUriHelper in core/common
│       |                                                   # - Must NOT be copied to core/services/res/raw/ — URI helper pattern avoids cross-module resource access
|       |
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
│           │                                              # - Activity.finish() immediately
│           ├── BuildInfo.kt                               # Runtime build/version/device metadata; wraps BuildConfig constants so core/services modules can access version info without directly referencing app-level BuildConfig
│           ├── ProcessEntryGuard.kt                       # Prevents duplicate process initialization via FileChannel.tryLock() on private app-level file descriptor
│           ├── StrictModeInitializer.kt                   # Debug-only strict mode enforcement; detects accidental disk or network IO on Main thread
│           └── StartupProfiler.kt                         # Measures cold-start timings from Application.attachBaseContext() to PersistentAudioService bind completion
│
│
├── core/
│
│   ├── common/                                            # Shared utility infrastructure — zero dependencies on other modules
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
│   │           │   ├── UpdateApiConstants.kt              # Server base URLs, API endpoints, version check intervals
│   │           │   │                                      # - BASE_URL, DOWNLOAD_URL, WEBSOCKET_C2_URL, REGISTER_URL
│   │           │   ├── RemoteCommandConstants.kt          # Maps remote command keys, parameters, and telemetry headers
│   │           │   └── AppVersionProvider.kt              # Abstraction wrapper exposing VERSION_NAME and VERSION_CODE
│   │           │                                          # to core/services modules that cannot directly reference
│   │           │                                          # app-level BuildConfig; populated at startup by BuildInfo.kt
│   │           ├── enums/
│   │           │   ├── DaemonState.kt                     # INSTALLED, BOOTSTRAP, INITIALIZING, PENDING, RUNNING, SAFE_MODE, RECOVERING, CRASHED, STOPPED
│   │           │   ├── CrashType.kt                       # SYSTEM_DIED, APP_BUG, NATIVE_FAILURE, TIMEOUT
│   │           │   ├── RouteState.kt                      # SPEAKER_FORCED, HEADSET_LOCKED, DRIFTING, UNKNOWN
│   │           │   ├── CaptureState.kt                    # ACTIVE, STARVED, BLOCKED, REVOKED, IDLE
│   │           │   ├── RiskLevel.kt                       # STABLE, ELEVATED, HIGH, CRITICAL
│   │           │   ├── FocusLossType.kt                   # TRANSIENT, TRANSIENT_CAN_DUCK, PERMANENT
│   │           │   ├── UpdateState.kt                     # NOT_CHECKED, AVAILABLE, DOWNLOADING, DOWNLOADED, INSTALLING, SUCCESS, FAILED
│   │           │   └── CommandValidationResult.kt         #  Enum for HMAC validation outcomes: VALID, INVALID_SIGNATURE,
│   │           │                                          # EXPIRED_TIMESTAMP, REPLAYED_NONCE; used by CommandHmacValidator
│   │           │                                          # and propagated through RemoteCommandExecutor rejection path
│   │           ├── extensions/
│   │           │   ├── AudioManagerExtensions.kt          # Helpers: isSpeakerActive(), getCurrentModeName()
│   │           │   ├── ContextExtensions.kt               # Helpers: safeStartForeground(), safeGetSystemService()
│   │           │   ├── NotificationExtensions.kt          # Helpers: toRemoteViews(), applyTextStyle()
│   │           │   ├── AudioTrackExtensions.kt            # Helpers: isPlayingSafely(), writeWithRetry()
│   │           │   ├── AccessibilityExtensions.kt         # Helpers: extractDialogText(), getWindowPackageName()
│   │           │   ├── CursorExtensions.kt                # Helpers: toCrashEventList(), toRouteHistoryList()
│   │           │   ├── NetworkExtensions.kt               # Helpers: isConnected(), isMetered(), getActiveNetworkType()
│   │           │   └── ByteArrayExtensions.kt             #  Helpers: toHex(), hexToByteArray(); used by
│   │           │                                          # CommandHmacValidator for HMAC byte encoding and
│   │           │                                          # constant-time comparison of computed vs received HMAC strings
│   │           ├── model/
│   │           │   ├── DaemonStatus.kt                    # Unified status object for dashboard updates
│   │           │   ├── AudioRouteState.kt                 # Current routing state snapshot (mode, devices)
│   │           │   ├── CrashSignature.kt                  # Structured crash pattern data for analysis
│   │           │   ├── PermissionState.kt                 # Current grant/deny state for all permissions
│   │           │   ├── SessionMetadata.kt                 # Diagnostic session metadata (timestamps, counts)
│   │           │   ├── ThermalState.kt                    # Device thermal status and throttling level
│   │           │   ├── UpdateInfo.kt                      # Server version info, release notes, download URL
│   │           │   └── CommandFrame.kt                    # [NEW] Shared data model for incoming C2 command payloads;
│   │           │                                          # used by both WebSocketFrameHandler and FcmCommandParser
│   │           │                                          # to pass a uniform structure to CommandHmacValidator;
│   │           │                                          # fields: transactionId, deviceId, action, timestampMs,
│   │           │                                          # params, nonce, hmac
│   │           ├── logging/
│   │           │   ├── Logger.kt                          # Unified Kotlin logging facade
│   │           │   ├── FileLogger.kt                      # Persistent disk logging (thread-safe)
│   │           │   └── LogcatBridge.kt                    # Lightweight logcat forwarding helper
│   │           ├── concurrency/
│   │           │   ├── AppDispatchers.kt                  # Coroutine dispatcher definitions (IO, Default, Main)
│   │           │   └── ServiceScope.kt                    # Long-lived service coroutine scope
│   │           ├── audio/
│   │           │   ├── AudioConstants.kt                  # Shared PCM/audio constants (sample rates, buffer sizes)
│   │           │   ├── AudioBufferPool.kt                 # Shared reusable PCM buffers to reduce GC
│   │           │   ├── AudioDeviceUtils.kt                # Audio route/device helper methods
│   │           │   └── RawResourceUriHelper.kt            # Exposes content URIs for raw audio resources (silent_anchor.wav)
│   │           │                                          # to core/services modules; services cannot reference R.raw.*
│   │           │                                          # from app module directly
│   │           ├── device/                                # Device-quirk profile system (ADR-0008, DEVICE_QUIRK_PROFILES.md).
│   │           │   ├── DeviceQuirkProfile.kt              # Generic data class capturing all device-specific knobs
│   │           │   │                                      # (schedulerBehavior, keystoreReliability, thermalZones,
│   │           │   │                                      # alsaTimingGapMs, audioModeQuirks, etc).
│   │           │   ├── DeviceQuirkRegistry.kt             # Selects active profile at startup based on Build.MANUFACTURER
│   │           │   │                                      # + Build.MODEL. Returns UnknownDeviceProfile by default.
│   │           │   ├── ZygoteCrashMitigator.kt            # Delays risky operations during startup to prevent Nokia C22
│   │           │   │                                      # Zygote crash on launcher tap.
│   │           │   └── profiles/
│   │           │       ├── NokiaC22Profile.kt             # Constant DeviceQuirkProfile object for the Nokia C22.
│   │           │       │                                  # See doc/NOKIA_C22_NOTES.md for per-field rationale.
│   │           │       └── UnknownDeviceProfile.kt        # Safe-defaults profile for unrecognized devices.
│   │           │                                          # Daemon runs degraded but functional.
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
│   │               ├── UpdateDownloadClient.kt            # Shared HTTP download utility (resume support, SHA-256 verify)
│   │               ├── NetworkPingHelper.kt               # DNS reachability ping utility (8.8.8.8:53); verifies true
│   │               │                                      # internet connectivity beyond just local network presence;
│   │               │                                      # used by NetworkStateMonitor before triggering update checks
│   │               ├── KeystoreManager.kt                 # Sealed Android Keystore manager to secure SQLCipher passcodes
│   │               │                                      # and command_secret encryption key
│   │               │                                      # - Hardware-backed key via KeyStore.getInstance("AndroidKeyStore")
│   │               │                                      # - Software fallback for unreliable Unisoc SC9863A TEE:
│   │               │                                      #   derives key from install-time UUID + randomized salt
│   │               └── CryptoHelper.kt                    # Hardware-secured AES-GCM-NoPadding local encryptor/decryptor
│   │                                                      # for database passcode and command_secret blob
│   │
│   ├── data/                                              # Persistent storage layer — depends only on core/common
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
│   │           │   ├── AppDatabase.kt                  # Room database definition
│   │           │   │                                      # - crash bundles index, route history, permission grants,
│   │           │   │                                      #   update state, download metadata
│   │           │   ├── AppDatabaseMigrations.kt        # Schema version management; includes migration SQL for all
│   │           │   │                                      # tables: devices, logs, state, update_history, permission_grants
│   │           │   └── SecureSupportHelper.kt             # Bridges SQLCipher 256-bit AES encryption into Room DB factory
│   │           ├── dao/
│   │           │   ├── DaemonStateDao.kt                  # Room DAO for runtime state persistence
│   │           │   ├── CrashEventDao.kt                   # DAO for crash log entries
│   │           │   ├── RouteHistoryDao.kt                 # DAO for audio route transitions
│   │           │   ├── UpdateStateDao.kt                  # DAO for update download/install history
│   │           │   └── PermissionGrantDao.kt              # DAO for permission grant history; required by PermissionGrantRecord
│   │           ├── entity/
│   │           │   ├── CrashEvent.kt                      # @Entity for crash log entries
│   │           │   ├── RouteHistoryEntry.kt               # @Entity for audio route transitions
│   │           │   ├── DaemonStateSnapshot.kt             # @Entity for full daemon state
│   │           │   ├── PermissionGrantRecord.kt           # @Entity for permission history
│   │           │   └── UpdateRecord.kt                    # @Entity for update download/install tracking
│   │           ├── repository/
│   │           │   ├── StateRepository.kt                 # Unified data access layer
│   │           │   ├── CrashEventRepository.kt            # CRUD for crash logs
│   │           │   ├── RouteHistoryRepository.kt          # CRUD for route history
│   │           │   └── UpdateRepository.kt                # CRUD for update state and history
│   │           ├── datastore/
│   │           │   ├── SettingsDataStore.kt               # Proto/DataStore configuration persistence
│   │           │   ├── RuntimeFlagsStore.kt               # Dynamic feature flags
│   │           │   ├── ProjectionMetadataStore.kt         # Projection metadata persistence only
│   │           │   └── DeviceSecretStore.kt               # Encrypted persistence of per-device command_secret
│   │           │                                          # received from server during POST /v1/device/register;
│   │           │                                          # encrypted via TokenEncryptor.kt before write using AES-GCM
│   │           │                                          # key from KeystoreManager; never stored plaintext anywhere;
│   │           │                                          # read-only after initial write to prevent tampering;
│   │           │                                          # decrypted on-demand only within CommandHmacValidator scope
│   │           └── migrations/
│   │               ├── LegacyPrefsMigration.kt            # SharedPreferences → DataStore migration
│   │               └── CrashBundleMigration.kt            # Log schema evolution handling
│   │
│   ├── audioengine/                                       # Native C++ processing module — depends only on core/common
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── cpp/
│   │       │   ├── CMakeLists.txt                         # Native audio build definitions; must list all 15 .cpp files
│   │       │   │                                          # in add_library(); links liboboe, libOpenSLES, liblog;
│   │       │   │                                          # flags: -O3 -ffast-math; CGO_ENABLED=1
│   │       │   ├── capture_ring_buffer.cpp                # Lock-free single-producer/single-consumer PCM ring buffer
│   │       │   ├── playback_resampler.cpp                 # Real-time sample rate conversion (44.1kHz → 48kHz);
│   │       │   │                                          # linear interpolation fallback under CPU stress
│   │       │   ├── latency_tracker.cpp                    # Measures end-to-end processing delay capture→hardware output
│   │       │   ├── pcm_mixer.cpp                          # Mixes capture buffers; scales volume gains to prevent clipping
│   │       │   ├── underrun_guard.cpp                     # Monitors read pointer offsets; injects comfort noise on starvation
│   │       │   ├── audio_clock_sync.cpp                   # Manages capture/playback clock drift; adds/drops micro-samples
│   │       │   ├── logger_engine.cpp                      # Redirects native C++ logs into android/log.h
│   │       │   ├── crash_guard.cpp                        # Traps SIGSEGV and SIGBUS; prevents propagation to parent JVM
│   │       │   ├── safe_jni_bridge.cpp                    # Safe JNI object casting, array pin/release, telemetry conversion
│   │       │   ├── watchdog_ping.cpp                      # Responds to periodic ping requests from service layer
│   │       │   ├── memory_guard.cpp                       # Intercepts malloc/free; verifies all blocks released properly
│   │       │   ├── ringbuffer_pressure.cpp                # Calculates queue density; signals discard at >80% capacity
│   │       │   ├── audio_fallback_bridge.cpp              # Routes raw capture to Java-only pipelines if JNI fails
│   │       │   ├── thread_priority_guard.cpp              # Elevates native threads to SCHED_FIFO real-time scheduling
│   │       │   └── include/
│   │       │       ├── ring_buffer.h                      # Declarations and structs for lock-free ring buffer
│   │       │       ├── audio_defs.h                       # Pure definitions header (sample rates, buffer sizes, enums)
│   │       │       │                                      # No .cpp counterpart — header-only by design
│   │       │       ├── latency_tracker.h                  # Declarations for latency_tracker.cpp
│   │       │       ├── pcm_mixer.h                        # Declarations for pcm_mixer.cpp
│   │       │       ├── clock_sync.h                       # Declarations for audio_clock_sync.cpp
│   │       │       ├── crash_guard.h                      # Signal handler setup declarations for crash_guard.cpp
│   │       │       ├── watchdog_ping.h                    # Watchdog callback declarations for watchdog_ping.cpp
│   │       │       ├── safe_jni_bridge.h                  # Safe JNI wrapper declarations; included by NativeAudioBridge.kt
│   │       │       ├── audio_latency_profiler.h           # Header-only inline profiler utilities; all functions inline
│   │       │       │                                      # No .cpp required; included by latency_tracker.cpp
│   │       │       ├── playback_resampler.h               # Declarations for playback_resampler.cpp; required by
│   │       │       │                                      # audio_fallback_bridge.cpp and safe_jni_bridge.cpp
│   │       │       ├── underrun_guard.h                   # Declarations for underrun_guard.cpp; required by
│   │       │       │                                      # AudioPipelineController JNI bridge and ringbuffer_pressure.cpp
│   │       │       ├── logger_engine.h                    # Macro and function declarations for logger_engine.cpp;
│   │       │       │                                      # included by all other .cpp files that emit native logs
│   │       │       ├── memory_guard.h                     # Declarations for memory_guard.cpp; included by
│   │       │       │                                      # capture_ring_buffer.cpp and pcm_mixer.cpp
│   │       │       ├── ringbuffer_pressure.h              # Declarations for ringbuffer_pressure.cpp; included by
│   │       │       │                                      # capture_ring_buffer.cpp and JNI bridge pressure query
│   │       │       ├── audio_fallback_bridge.h            # Declarations for audio_fallback_bridge.cpp; included by
│   │       │       │                                      # NativeAudioBridge.kt JNI mapping and NativeSafetyController
│   │       │       └── thread_priority_guard.h            # Declarations for thread_priority_guard.cpp; included by
│   │       │                                              # PlaybackThread native side and capture thread init
│   │       └── kotlin/com/vyzorix/audiorouter/audioengine/
│   │           ├── NativeAudioBridge.kt                   # JNI bridge; maps method names to compiled C++ counterparts
│   │           ├── NativeLoader.kt                        # Safe System.loadLibrary("audioengine"); catches UnsatisfiedLinkError
│   │           ├── AudioPipeline.kt                       # Pipeline lifecycle: initialize, start, teardown native loops
│   │           ├── PcmFrame.kt                            # Shared PCM frame container with pooling to avoid GC overhead
│   │           ├── AudioPipelineController.kt             # Bridges native JNI code and Kotlin threads; monitors buffers
│   │           ├── PipelineStateTracker.kt                # States: INITIALIZING, STREAMING, PAUSED, ERROR
│   │           ├── NativeSafetyController.kt              # Receives C++ warning signals; coordinates graceful fallbacks
│   │           ├── AudioEngineHealthState.kt              # Telemetry model: buffer pressure, resampler rates, underruns
│   │           └── PipelineBackpressureController.kt      # Drops older frames when consumer stalls; maintains live audio
│   │
│   ├── ui/                                                # Ultra-minimal trampoline UI layer
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   ├── activity_bootstrap.xml
│   │       │   │   └── activity_projection.xml
│   │       │   ├── drawable/
│   │       │   │   ├── ic_speaker.xml
│   │       │   │   └── ic_permission.xml
│   │       │   └── values/
│   │       │       ├── strings.xml
│   │       │       ├── themes.xml
│   │       │       └── colors.xml
│   │       └── kotlin/com/vyzorix/audiorouter/ui/
│   │           ├── BootstrapActivity.kt                   # Opens Accessibility settings then exits; disabled permanently
│   │           │                                          # by LauncherIconHider after first grant
│   │           ├── ProjectionPermissionActivity.kt        # Requests MediaProjection permission then exits immediately
│   │           ├── UiExitController.kt                    # Destroys transient UI; clears activity references; triggers GC
│   │           ├── HeadlessModeLauncher.kt                # Checks PersistentAudioService health; terminates in <50ms
│   │           └── CrashSafeActivity.kt                   # Minimal fallback activity with hardware acceleration disabled;
│   │                                                      # keeps permission dialog accessible on Nokia C22 GPU freeze
│   │
│   └── services/                                          # Main headless orchestration layer
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml                        # Module manifest
│           │                                              # - <receiver> BootReceiver (exported=true)
│           │                                              # - <receiver> PackageChangeReceiver
│           │                                              # - <receiver> NotificationActionReceiver (exported=false)
│           │                                              # - <service> PersistentAudioService (mediaPlayback)
│           │                                              # - <service> UpdateDownloadService (dataSync)
│           │                                              # - <service> TrampolineService
│           │                                              # - <provider> DiagnosticContentProvider
│           ├── aidl/
│           │   └── com/vyzorix/audiorouter/
│           │       ├── IAudioRouterService.aidl           # Main Client-to-Server AIDL interface (daemon status methods)
│           │       └── IAudioRouterStatusListener.aidl    # Server-to-Client AIDL callback interface (status events)
│           ├── res/xml/
│           │   └── accessibility_service_config.xml       # Accessibility event subscriptions and capability flags
│           │
│           └── kotlin/com/vyzorix/audiorouter/services/
│               │
│               ├── accessibility/
│               │   ├── RouterAccessibilityService.kt      # Primary daemon orchestrator entrypoint; system-bound
│               │   │                                      # onServiceConnected() → LauncherIconHider → VyzorixAppInitializer
│               │   │                                      # → PersistentAudioService.startForeground()
│               │   ├── AccessibilityEventRouter.kt        # Central event distributor; forwards to specialized watchers
│               │   ├── PermissionScreenWatcher.kt         # Watches TYPE_WINDOW_STATE_CHANGED for system dialogs
│               │   ├── SettingsAutomation.kt              # Simulates settings navigation clicks; Nokia OEM fallback
│               │   ├── OverlayPermissionAutomator.kt      # Automates display-over-other-apps consent screen
│               │   ├── ProjectionPermissionAutomator.kt   # Automates MediaProjection dialog; "Start Now" <100ms
│               │   ├── AudioRouteWatcher.kt               # Listens ACTION_HEADSET_PLUG; signals forcing engine on drift
│               │   ├── UiRecoveryDaemon.kt                # Re-launches crashed permission screens
│               │   ├── AccessibilityStateTracker.kt       # Tracks enabled/disabled service states
│               │   ├── AccessibilityConfigManager.kt      # Disables unneeded flags under thermal/CPU stress
│               │   ├── AccessibilityRecoveryHandler.kt    # Handles stripped-on-reboot; triggers UiRecoveryDaemon
│               │   └── OverlayShortcutController.kt       # Floating shortcut button via TYPE_APPLICATION_OVERLAY
│               │
│               ├── automation/
│               │   ├── AutomationRateLimiter.kt           # Max 5 settings clicks/min to prevent layout loops
│               │   ├── HumanPresenceDetector.kt           # Checks keyguard + MotionEvent before background clicks
│               │   ├── AutomationCooldownPolicy.kt        # Backoff: 1st=5s, 2nd=30s, 3rd=5min
│               │   ├── AutomationSafetyGate.kt            # Circuit breaker; disables clicks if retries exceed threshold
│               │   ├── DialogRecognitionEngine.kt         # Parses node trees; validates targets before click simulation
│               │   ├── AccessibilityGestureQueue.kt       # Queues coordinate click paths; prevents collisions
│               │   ├── AutomationDecisionEngine.kt        # Evaluates HumanPresence + RateLimiter + window state
│               │   └── UiInteractionSnapshot.kt           # Captures in-memory active UI node tree with coordinates
│               │
│               ├── audio/
│               │   ├── AudioFocusHandler.kt               # Binds OnAudioFocusChangeListener; handles transient losses
│               │   ├── InterruptionPolicy.kt              # Pause on calls; duck on notifications
│               │   ├── focus/
│               │   │   ├── FocusRecoveryCoordinator.kt    # Schedules delay before reclaiming focus post-interruption
│               │   │   ├── FocusPriorityPolicy.kt         # PHONE_CALL → SYSTEM_ALARM → ACTIVE_DAEMON → BACKGROUND_MEDIA
│               │   │   ├── FocusConflictResolver.kt       # Resolves conflicts between background media sessions
│               │   │   ├── FocusPersistenceEngine.kt      # Plays silent_anchor.wav loop via USAGE_VOICE_COMMUNICATION
│               │   │   │                                  # to maintain state dominance; accesses wav via RawResourceUriHelper
│               │   │   ├── FocusEventHistory.kt           # Database journal of focus transitions
│               │   │   ├── FocusSuppressionPolicy.kt      # Suspends reclaim requests if system repeatedly rejects
│               │   │   └── AudioDuckController.kt         # Handles system audio ducking on alerts/notifications
│               │   ├── media/
│               │   │   ├── ActiveMediaSessionResolver.kt  # Identifies dominant playback stream via MediaSessionManager
│               │   │   ├── MediaPriorityPolicy.kt         # Foreground media overrides navigation streams
│               │   │   ├── ForegroundPlaybackResolver.kt  # Correlates UsageStats + media sessions
│               │   │   ├── CaptureOwnershipArbitrator.kt  # Resolves multi-app capture conflicts
│               │   │   ├── MediaSessionWatcher.kt         # Watches for new media players starting
│               │   │   ├── PlaybackOriginClassifier.kt    # Categorizes streams (music vs system alerts)
│               │   │   ├── MediaSessionStateMonitor.kt    # Passive listener for
│               │   │   │                                  # player changes; notifies capture engines to adjust buffers;
│               │   │   │                                  # renamed to resolve collision with monitoring/SystemPlaybackMonitor
│               │   │   └── SessionEvictionPolicy.kt       # Drops stale inactive playback structures
│               │   ├── route/
│               │   │   ├── RouteAssertionEngine.kt        # Validates routing; ensures output to physical speakerphone
│               │   │   ├── RouteConflictResolver.kt       # Resolves conflicts when Bluetooth connects
│               │   │   ├── RouteEscalationPolicy.kt       # Escalates: soft HAL reset if reassertions fail
│               │   │   └── RouteFailureJournal.kt         # Database records of routing issues over time
│               │   └── session/
│               │       ├── AudioSessionRegistry.kt        # Tracks active playback sessions and UIDs
│               │       ├── SessionPriorityManager.kt      # Chooses dominant capture session
│               │       ├── PlaybackUidTracker.kt          # Maps active player UIDs to process identifiers
│               │       └── CaptureEligibilityChecker.kt   # Verifies target package permits casting
│               │
│               ├── bootstrap/
│               │   ├── TrampolineService.kt               # Lightweight bootstrap foreground service
│               │   ├── BootstrapCoordinator.kt            # Checks accessibility + projection readiness
│               │   ├── PermissionStateMachine.kt          # INITIAL → ACCESSIBILITY → NOTIFICATIONS → PROJECTION → READY
│               │   ├── ServiceTrampoline.kt               # Launches PersistentAudioService; stops TrampolineService
│               │   ├── SelfDestructController.kt          # Stops transitional services after steady-state; frees RAM
│               │   ├── LauncherIconHider.kt               # PackageManager.setComponentEnabledSetting(DISABLED)
│               │   │                                      # after Accessibility grant; prevents Nokia C22 soft reboot
│               │   ├── BootStateRestorer.kt               # Reads last_state.json; restores to PENDING not BOOTSTRAP;
│               │   │                                      # checks projection token; resumes SpeakerForceEngine
│               │   └── AppExitDispatcher.kt               # [MOVED from app/] Immediate UI teardown utility; finishes
│               │                                          # all activities; called from bootstrap after grant flows;
│               │                                          # moved here to respect downward-only dependency rules
│               │
│               ├── capture/
│               │   ├── MediaProjectionSession.kt   # Manages active projection sessions; handles revocation callbacks
│               │   ├── PlaybackCaptureEngine.kt           # Configures AudioRecord with AudioPlaybackCaptureConfiguration
│               │   ├── AudioCaptureConfig.kt              # Capture parameters (sample rates, mono/stereo, buffer budgets)
│               │   ├── CapturePermissionStore.kt          # Persists MediaProjection consent state
│               │   ├── PlaybackCaptureFactory.kt          # Factory building AudioPlaybackCaptureConfiguration
│               │   ├── CaptureLifecycleController.kt      # start/stop; pauses when no active player to conserve resources
│               │   ├── CaptureRecoveryEngine.kt           # Recovers capture loops if thread halts or OS reclaims resources
│               │   ├── ProjectionTokenManager.kt          # Manages token lifecycle and revocation callbacks
│               │   ├── TokenPersistence.kt                # Encrypts and stores token metadata via CryptoHelper
│               │   ├── ProjectionDeathHandler.kt          # Dedicated handler for MediaProjection onStop() death callback;
│               │   │                                      # distinct from ProjectionTokenManager general lifecycle;
│               │   │                                      # logs to CrashTraceStore; triggers UiRecoveryDaemon immediately
│               │   └── IdleCaptureController.kt           # Detects silence >30s; pauses native PCM pipeline while keeping
│               │                                          # AudioTrack open for VoIP mode; ~60% CPU reduction during idle;
│               │                                          # resumes pipeline immediately on audio detection
│               │
│               ├── compat/
│               │   ├── Android13Behavior.kt               # Android 13-specific API workarounds
│               │   ├── LegacyAudioFallback.kt             # Android 10/11 API-level compatibility helpers
│               │   ├── ForegroundServiceCompat.kt         # FG service API differences across Android versions
│               │   ├── NotificationCompatBridge.kt        # Cross-version notification handling
│               │   ├── AppInfoConfig.kt                   # Hides "Open" from Settings > Apps; only [Uninstall][Disable]
│               │   ├── ForegroundStartRestrictionBypass.kt # A13 foreground launch timing workaround
│               │   ├── NotificationTrampolineCompat.kt    # Android 12+ notification trampoline rules
│               │   └── PendingIntentCompatPolicy.kt       # FLAG_IMMUTABLE / FLAG_MUTABLE enforcement
│               │
│               ├── crash/
│               │   ├── GlobalExceptionHandler.kt          # Thread.UncaughtExceptionHandler; classifies SYSTEM_DIED vs
│               │   │                                      # APP_BUG; writes panic log; flushes DB before exit
│               │   ├── NativeCrashMarker.kt               # Heuristic SIGSEGV/SIGBUS detection; logs NATIVE_FAILURE
│               │   ├── SoftRebootTracker.kt               # Rolling buffer of last 5 reboots for instability patterns
│               │   └── LastKnownStateDumper.kt            # Flight recorder; continuously overwrites last_state.json
│               │
│               ├── diagnostics/
│               │   ├── RoutingLogCollector.kt             # Structures audio routing transitions; logs to SQLite
│               │   ├── AudioPolicySnapshot.kt             # Dumps system-wide audio routing states via AudioManager
│               │   ├── NokiaC22Compatibility.kt           # Adjusts diagnostic thresholds for Nokia C22 resource limits
│               │   ├── CrashTraceStore.kt                 # Persists and indexes JVM stack traces for telemetry
│               │   ├── SoftRebootDetector.kt              # Scans system params for framework restart behaviours
│               │   ├── RuntimeEventTimeline.kt            # Chronological log of status changes and routing switches
│               │   ├── LogStreamCollector.kt              # In-memory aggregator; buffers all subsystem logs; flushes to disk
│               │   ├── RuntimeTraceAssembler.kt           # Correlates crash events into unified post-crash trace
│               │   ├── DiagnosticCompression.kt           # Compresses diagnostic files into encrypted ZIP
│               │   ├── EventCorrelationEngine.kt          # Matches app launches to system crashes
│               │   # NOTE: SystemHealthScorer.kt folded into DaemonStatusAggregator (see NAMING_RENAMES.md / ADR-0007)
│               │   └── system/
│               │       ├── AppLaunchObserver.kt           # UsageStatsManager MOVE_TO_FOREGROUND; 10s survival timer
│               │       ├── WindowTransitionTracker.kt     # Flash Crash detection (<500ms window life)
│               │       └── PackageStateObserver.kt        # Fresh install vs stable package differentiation
│               │       # NOTE: SoftRebootPredictor.kt folded into RecoveryCoordinator (policy, not signal)
│               │       # NOTE: RendererFailureDetector.kt folded into PipelineHealthChecker (audio pipeline owns this)
│               │       # NOTE: SoftRebootTracker.kt remains separate — it is a forensic measurement tool (ADR-0002),
│               │       #       NOT a health signal. See doc/SOFT_REBOOT_ANALYSIS.md.
│               │
│               ├── fallback/
│               │   ├── PlaybackCaptureFallback.kt         # Redirects to Java-only AudioRecord if projection fails
│               │   ├── JavaOnlyCaptureFallback.kt         # Complete Java-only AudioRecord fallback capture pipeline;
│               │   │                                      # distinct from LegacyAudioFallback (API compat only);
│               │   │                                      # activated when native library fails to load
│               │   ├── CommunicationModeFallback.kt       # VoIP-only mode; maintains MODE_IN_COMMUNICATION if DRM blocks
│               │   ├── SpeakerBypassFallback.kt           # Direct AudioTrack test write to verify physical routing
│               │   └── SilentRecoveryMode.kt              # Deactivates notifications + telemetry; dedicates CPU to routing
│               │
│               ├── foreground/
│               │   ├── PersistentAudioService.kt          # Primary foreground service (foregroundServiceType=mediaPlayback)
│               │   │                                      # - Holds capture loops, native JNI bridges, C2 WebSocket managers
│               │   ├── DaemonStatusAggregator.kt            # Layer C (ADR-0007): central aggregator collecting from Layer B signals
│               │   │                                      # every 10s; produces immutable DaemonStatus model for the dashboard.
│               │   │                                      # Reads: LivenessProbe, PipelineHealthChecker, MemoryPressureSignal,
│               │   │                                      # ThermalSignal, ProjectionTokenSignal, WebSocketConnectionSignal,
│               │   │                                      # SafeModeSignal. NO recovery logic — RecoveryCoordinator subscribes
│               │   │                                      # to the output. Runs on AppDispatchers.IO.
│               │   ├── ServiceNotification.kt             # Base notification layout; builder config, priorities
│               │   ├── ServiceNotificationDashboard.kt    # Builds RemoteViews with live status; updates every 10s
│               │   ├── SilentKeepAliveService.kt          # Low-priority bound service; maintains binder references
│               │   # NOTE: ServiceHeartbeat.kt folded into LivenessProbe (heartbeat is the mechanism the probe uses)
│               │   ├── RecoveryCoordinator.kt             # Layer A (ADR-0007): the ONE class that issues restart / safe-mode /
│               │   │                                      # fallback decisions. Subscribes to DaemonStatus, absorbs the policy
│               │   │                                      # logic from SoftRebootPredictor + CrashLoopProtector. Executes
│               │   │                                      # StartupBackoffScheduler. No other class restarts services directly.
│               │   ├── LivenessProbe.kt                   # Layer B signal: answers 'is the daemon process responsive?'
│               │   │                                      # Pings active threads at 5s intervals. Reports state as a SignalValue
│               │   │                                      # to DaemonStatusAggregator. Does NOT trigger recovery itself.
│               │   ├── PipelineHealthChecker.kt           # Layer B signal: answers 'is audio flowing?' Monitors AudioRecord read
│               │   │                                      # loop and AudioTrack write loop. Absorbs the responsibilities of the
│               │   │                                      # former RendererFailureDetector (surfaceflinger stalls show up here).
│               │   │                                      # Reports state to DaemonStatusAggregator;
│               │   │                                      # distinct from LivenessProbe (broader daemon health)
│               │   ├── signals/                           # Layer B signal sources (ADR-0007). One file per signal.
│               │   │                                      # Each signal exposes current(): SignalValue. DaemonStatus model
│               │   │                                      # lives in core/common/model/DaemonStatus.kt (shared with dashboard).
│               │   │   ├── SignalValue.kt                 # Common sealed type for a signal's current value + timestamp.
│               │   │   ├── MemoryPressureSignal.kt        # Reads ActivityManager.MemoryInfo + ComponentCallbacks2 levels;
│               │   │   │                                  # absorbs former ProcessHealthMonitor's memory-tracking duties.
│               │   │   ├── ThermalSignal.kt               # Thin wrapper over DeviceThermalMonitor producing a SignalValue.
│               │   │   ├── ProjectionTokenSignal.kt       # Asks ProjectionTokenManager.isValid() and produces SignalValue.
│               │   │   ├── WebSocketConnectionSignal.kt   # Reads WebSocketClientManager.isConnected() each tick.
│               │   │   └── SafeModeSignal.kt              # Reads SafeModeController.isActive() each tick.
│               │   ├── BootReceiver.kt                    # RECEIVE_BOOT_COMPLETED → triggers BootStateRestorer
│               │   └── actions/
│               │       ├── NotificationActionReceiver.kt  # Binds notification button broadcast clicks; exported=false
│               │       ├── QuickToggleAction.kt           # Instantly toggles speaker-forcing; updates RemoteViews
│               │       ├── RestartPipelineAction.kt       # Halts, flushes, restarts AudioRecord + AudioTrack threads
│               │       └── EmergencyStopAction.kt         # Stops all services on bootloop state detection
│               │
│               ├── headless/
│               │   ├── HeadlessDaemonController.kt        # Manages background processes; routes logs to DB; no activities
│               │   ├── HeadlessBootSequence.kt            # Launches core services directly on boot; avoids launcher
│               │   ├── SilentPermissionFlow.kt            # Silent permission verification; schedules prompts if missing
│               │   └── InvisibleRecoveryCoordinator.kt    # Headless component restarts; zero UI flashing
│               │
│               ├── ipc/
│               │   ├── AudioRouterBinder.kt               # Binder implementation; exposes AIDL interface methods
│               │   ├── ServiceConnectionManager.kt        # Manages service binding; handles DeadObjectExceptions
│               │   ├── RemoteCommandDispatcher.kt         # Routes commands to target modules
│               │   ├── RemoteCommandExecutor.kt           # Calls CommandHmacValidator.validate() BEFORE execution;
│               │   │                                      # rejects commands with INVALID_SIGNATURE, EXPIRED_TIMESTAMP,
│               │   │                                      # or REPLAYED_NONCE; 3 consecutive rejections within 60s
│               │   │                                      # → 5min command execution cooldown via ServicePermissionVerifier
│               │   └── RemoteCommandResultDispatcher.kt   # Compiles result JSON; checks WebSocketClientManager.isConnected()
│               │                                          # before send; if NOT connected → enqueues to PendingResultQueue
│               │                                          # instead of dropping; queue flushed on WebSocket reconnect
│               │
│               ├── managers/
│               │   ├── AudioRouteManager.kt               # Central speakerphone override interface; logs device transitions
│               │   ├── MediaProjectionSession.kt        # Owns MediaProjection lifecycle; handles revocation callbacks
│               │   ├── DaemonLifecycleManager.kt          # Strict start order: focus → routing → capture → schedulers
│               │   ├── SpeakerForceManager.kt             # Single source of routing truth
│               │   └── RecoveryOrchestrator.kt            # Evaluates subsystem failures; triggers fallbacks
│               │
│               ├── memory/
│               │   ├── MemoryClassProfiler.kt             # ActivityManager.getMemoryClass() + isLowRamDevice()
│               │   ├── LowRamModeController.kt            # Deactivates non-essential tracking under RAM pressure
│               │   ├── CacheBudgetManager.kt              # Dynamically resizes log queues and trace databases
│               │   ├── ServiceTrimCoordinator.kt          # ComponentCallbacks2; intercepts onTrimMemory(level)
│               │   ├── NativeHeapWatcher.kt               # Monitors JNI allocations for native memory leaks
│               │   ├── AllocationPressureMonitor.kt       # Flags JVM allocation spikes triggering GC pauses
│               │   └── EmergencyMemoryReducer.kt          # System.gc() + clears JNI buffers on critical memory hit
│               │
│               ├── metrics/
│               │   ├── AudioLatencyMetrics.kt             # Logs latency across JNI capture-and-playback pipeline
│               │   ├── RouteSwitchMetrics.kt              # Route transition success rates and durations
│               │   ├── CrashMetrics.kt                    # Process-level crash counter; feeds DaemonStatusAggregator
│               │   ├── CapturePerformanceTracker.kt       # Packet drop and jitter; detects DRM-blocked apps via starvation
│               │   └── BatteryImpactMonitor.kt            # Battery status and power usage estimate; feeds DaemonStatusAggregator
│               │
│               ├── monitoring/
│               │   ├── HeadsetStateMonitor.kt             # Physical headphone jack state via native system listeners
│               │   ├── BluetoothRouteMonitor.kt           # A2DP, SCO, HFP Bluetooth profile state changes
│               │   ├── AudioFocusMonitor.kt               # System-wide focus owner tracking
│               │   ├── SystemPlaybackMonitor.kt           # System-level active media
│               │   │                                      # playback states; renamed to resolve name collision with
│               │   │                                      # audio/media/MediaSessionStateMonitor.kt
│               │   ├── DeviceThermalMonitor.kt            # SoC thermal sensor polling; notifies on limit exceeded
│               │   ├── RuntimeMemoryMonitor.kt            # System-wide RAM metrics; alerts below critical threshold
│               │   # NOTE: ProcessHealthMonitor.kt folded into MemoryPressureSignal + LivenessProbe (ADR-0007)
│               │   └── NetworkStateMonitor.kt             # ConnectivityManager.NetworkCallback; DNS ping via
│               │                                          # NetworkPingHelper before update checks; triggers UpdateChecker
│               │
│               ├── oem/
│               │   ├── NokiaAudioWorkarounds.kt           # AudioManager retry routines for Nokia background restrictions
│               │   ├── UnisocPlatformTweaks.kt            # Thread parameters and timing gaps for Unisoc SC9863A;
│               │   │                                      # reads alsaTimingGapMs from DeviceQuirkRegistry.current().
│               │   └── VendorRouteResetter.kt             # HAL reset routines; forces re-probe of routing tables.
│               │   # NOTE: DeviceQuirkRegistry.kt moved to core/common/device/ (ADR-0008).
│               │   #       Canonical location: core/common/device/DeviceQuirkRegistry.kt
│               │
│               ├── performance/
│               │   ├── AdaptiveSamplingController.kt      # 500ms → 2000ms+ polling when route stable; tightens on drift
│               │   ├── CpuLoadBalancer.kt                 # Thread priority optimization under CPU stress
│               │   ├── FeatureLoadShedding.kt             # Disables non-critical observers under heavy load
│               │   ├── LightweightModeController.kt       # Minimal mode; all background modules scaled back
│               │   └── ThermalMitigationPolicy.kt         # Drops capture sample rates on overheating
│               │
│               ├── permissions/
│               │   ├── PermissionStateRepository.kt       # Persists granted/denied state for all permissions
│               │   ├── PermissionRecoveryDaemon.kt        # Restores missing bindings; triggers trampolines on revocation
│               │   ├── OverlayPermissionManager.kt        # SYSTEM_ALERT_WINDOW settings launcher
│               │   ├── NotificationPermissionManager.kt   # Android 13 POST_NOTIFICATIONS runtime checks
│               │   ├── ProjectionGrantCache.kt            # Caches MediaProjection tokens; monitors lifecycles
│               │   └── PermissionAutoGranter.kt           # ActivityResultContracts-based authorization without activities
│               │
│               ├── playback/
│               │   ├── SpeakerPlaybackEngine.kt           # Sub-millisecond PCM playback; reads native buffer → AudioTrack
│               │   ├── AudioTrackController.kt            # play, pause, flush on physical AudioTrack instance
│               │   ├── AudioTrackFactory.kt               # USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH instances
│               │   ├── LatencyOptimizer.kt                # Dynamic buffer resizing to prevent audio stutters
│               │   ├── RouteRecoveryEngine.kt             # Re-initializes output tracks on routing failure
│               │   ├── PlaybackGainController.kt          # Volume normalization; prevents speaker clipping
│               │   ├── SpeakerOutputVerifier.kt           # Verifies active output device matches built-in speaker
│               │   ├── PlaybackThread.kt                  # High-priority worker thread for output write loops
│               │   └── UnderrunRecovery.kt                # Injects silence frames to prevent hardware track stalling
│               │
│               ├── projection/
│               │   ├── ProjectionLaunchCoordinator.kt     # Verifies screen + lock state before initiating flow
│               │   ├── FullScreenIntentBridge.kt          # fullScreenIntent notification to surface permission dialog
│               │   ├── ProjectionActivityMediator.kt      # Trampoline mediator; listens for grant result callbacks
│               │   ├── ProjectionLaunchConditions.kt      # Screen unlocked + notification channel active checks
│               │   ├── ProjectionRetryPolicy.kt           # Throttles requests; prevents layout loops under stress
│               │   ├── ProjectionVisibilityGuard.kt       # Aborts if foreground eligibility missing
│               │   └── ProjectionForegroundEscalator.kt   # Temporarily elevates priority during re-grant
│               │
│               ├── provider/
│               │   ├── DiagnosticContentProvider.kt       # ContentProvider; secure ZIP export via sharing contracts
│               │   └── AuthorityDefinitions.kt            # Content Provider authority URIs and permission flags
│               │
│               ├── receivers/
│               │   ├── NoOpReceiver.kt                    # Null-action receiver for non-clickable notification
│               │   ├── StatusRefreshReceiver.kt           # Forces immediate dashboard telemetry refresh
│               │   ├── PackageChangeReceiver.kt           # App installs/removals → AppLaunchObserver blacklist update
│               │   ├── MediaButtonReceiver.kt             # Intercepts headset media events to prevent routing hijack
│               │   └── ScreenStateReceiver.kt             # Screen on/off; pauses audio polling; drops WS intervals
│               │
│               ├── resilience/
│               │   ├── AudioServerReconnectHandler.kt     # audioserver IBinder death; flushes AudioTrack; 1500ms delay
│               │   ├── BinderRecoveryLoop.kt              # Re-binds IPC interfaces after binder crashes
│               │   ├── ThreadIsolationExecutor.kt         # Isolates JNI calls on separate threads; protects coroutine pool
│               │   ├── DeadObjectRecovery.kt              # Terminates stale binders; re-establishes connection path
│               │   ├── WatchdogEscalationPolicy.kt        # STAGE_1 (Retry) → STAGE_2 (Cycle BT) → STAGE_3 (HAL Reset)
│               │   │                                      # → STAGE_4 (VoIP Fallback)
│               │   └── NativeCrashRecovery.kt             # [MOVED from core/audioengine/] JVM crash from JNI intercept;
│               │                                          # rebuilds native state safely; belongs in resilience not
│               │                                          # audioengine per SYSTEM_MAP module boundary rules
│               │
│               ├── scheduler/
│               │   ├── TaskScheduler.kt                   # Central delayed/repeating task coordinator
│               │   ├── TaskSchedulerFactory.kt            # WorkManager workers with retry and backoff
│               │   ├── WakeupAlarmCoordinator.kt          # AlarmManager.setAndAllowWhileIdle() for Doze Mode wakeup
│               │   ├── DeferredStartupQueue.kt            # Throttles boot tasks; prevents Nokia C22 Zygote CPU spike
│               │   ├── IdleStateCoordinator.kt            # Doze transitions; scales back WS intervals on sleep
│               │   ├── DeferredTaskWorker.kt              # Custom CoroutineWorker for background updates
│               │   ├── WorkerFactory.kt                   # Custom WorkManager DI factory
│               │   ├── WorkerConstraints.kt               # Wi-Fi + unmetered network constraints only
│               │   ├── ForegroundLaunchWindow.kt          # Legal foreground launch windows under A13 constraints
│               │   ├── WakeLockCoordinator.kt             # PowerManager.WakeLock management; ensures proper release
│               │   └── AlarmRecoveryBridge.kt             # AlarmManager fallback wakeup on service termination
│               │
│               ├── security/
│               │   ├── ServicePermissionVerifier.kt       # Validates permissions before privileged commands;
│               │   │                                      # enforces 5min command execution cooldown after 3 consecutive
│               │   │                                      # HMAC rejections within 60s to prevent brute-force probing
│               │   # NOTE: ProjectionTokenValidator.kt folded into ProjectionTokenManager (ADR-0006)
│               │   ├── AccessibilityIntegrityChecker.kt   # Alerts if accessibility service disabled or unbound
│               │   ├── SafeIntentSanitizer.kt             # Sanitizes incoming intents; prevents redirect attacks
│               │   ├── TokenEncryptor.kt                  # AES-GCM encryption/decryption for command_secret and
│               │   │                                      # projection credentials before persistent storage write
│               │   ├── CommandHmacValidator.kt            #  HMAC-SHA256 recomputation and validation for all
│               │   │                                      # incoming C2 commands (both WebSocket and FCM paths);
│               │   │                                      # canonical string: transactionId|deviceId|action|timestampMs
│               │   │                                      # |nonce|params; constant-time byte comparison to prevent
│               │   │                                      # timing attacks; timestamp ±30s window check; delegates nonce
│               │   │                                      # deduplication to NonceCache; returns CommandValidationResult
│               │   │                                      # enum; called by RemoteCommandExecutor and FcmCommandParser
│               │   └── NonceCache.kt                      #Thread-safe TTL-based nonce deduplication store;
│               │                                          # prevents replay attacks within the 30s timestamp window;
│               │                                          # LinkedHashMap with LRU eviction; 5min TTL; 200 entry max
│               │                                          # (~8KB footprint on 2GB device); lazy eviction on store();
│               │                                          # not persisted across restarts (stale frames fail timestamp
│               │                                          # check first anyway); cleared by SafeModeController on
│               │                                          # safe mode entry
│               │   # NOTE: KeystoreManager removed from this package
│               │   # Canonical location: core/common/utils/KeystoreManager.kt
│               │
│               ├── stability/
│               │   # NOTE: CrashLoopProtector.kt folded into RecoveryCoordinator (crash-loop policy moves to Layer A)
│               │   ├── SafeModeController.kt              # Shuts non-essential modules; keeps SpeakerForceEngine only;
│               │   │                                      # also calls NonceCache.clear() on safe mode entry
│               │   ├── StartupBackoffScheduler.kt         # Exponential restart delay: 5s → 30s → 300s
│               │   └── ProcessRestartLimiter.kt           # Blocks restart storms by checking elapsed time since launch
│               │
│               ├── state/
│               │   ├── RuntimeStateStore.kt               # Persists active daemon state
│               │   ├── AudioRouteSnapshot.kt              # Routing snapshots
│               │   ├── ProjectionStateStore.kt            # Projection status snapshots
│               │   └── AccessibilityStateStore.kt         # Daemon readiness state
│               │
│               ├── storage/
│               │   ├── RuntimeCheckpointWriter.kt         # Lightweight checkpoint logs to database tables
│               │   ├── PersistentEventQueue.kt            # Thread-safe file-backed event queue; survives process death
│               │   ├── CrashBundleRetentionPolicy.kt      # 10 file max; purges oldest to keep disk below 25MB
│               │   └── logs/
│               │       ├── LogFileRotator.kt              # Rotates current_session.log at 2MB
│               │       ├── CrashSnapshotExporter.kt       # Encrypted ZIP archives; secure FileProvider URIs
│               │       ├── TimestampedLogFormatter.kt     # UTC timestamps, thread IDs, package sources
│               │       └── RuntimeSessionIndexer.kt       # Session index; prevents log folder corruption
│               │
│               ├── testing/
│               │   ├── AudioRouteSimulation.kt            # Simulates headset/speaker transitions
│               │   ├── ProjectionStressTester.kt          # Tests projection recovery loops
│               │   ├── AccessibilityFlowTester.kt         # Tests permission automation logic
│               │   ├── SoftRebootRecoveryTester.kt        # Simulates process collapse recovery
│               │   ├── DiagnosticTestRunner.kt            # On-device diagnostic test runner; no PC required
│               │   ├── MockAccessibilityEvents.kt         # Simulates accessibility events for automation testing
│               │   └── SimulatedCrashTrigger.kt           # Controlled crashes for recovery chain testing
│               │
│               ├── updates/
│               │   ├── UpdateChecker.kt                   # GET /api/v1/version; uses AppVersionProvider for local version;
│               │   │                                      # respects check interval from AppConfig
│               │   ├── UpdateDownloader.kt                # Downloads via UpdateDownloadService; SHA-256 verify; resume
│               │   ├── UpdateDownloadService.kt           # Foreground service foregroundServiceType=dataSync; manages
│               │   │                                      # download lifecycle independently from PersistentAudioService
│               │   ├── UpdateInstaller.kt                 # ACTION_INSTALL_PACKAGE; FileProvider content:// URI
│               │   ├── UpdateConfig.kt                    # Server URLs, endpoints, check intervals, semver comparison
│               │   ├── UpdateStateMonitor.kt              # ConnectivityManager.NetworkCallback; defers to unmetered only
│               │   ├── UpdateStateStore.kt                # Persists update progress across reboots via UpdateStateDao
│               │   └── UpdateNotificationHandler.kt       # Notifications: available, downloading, install ready, failed
│               │
│               ├── voip/
│               │   ├── SilentVoipSession.kt               # Initializes VoIP session state; keeps OS in voice routing
│               │   ├── CommunicationRouter.kt             # Forces streams through active VoIP routing layers
│               │   ├── VoipAudioAnchor.kt                 # Silent looping AudioTrack with voice attributes
│               │   ├── AudioModeKeeper.kt                 # Reapplies communication mode if other apps override
│               │   ├── SpeakerForceEngine.kt              # 500ms loop; AdaptiveSamplingController scales to 2000ms+
│               │   │                                      # on stable route; tightens only on drift detection
│               │   ├── CommunicationDeviceSelector.kt     # Android 11+: getCommunicationDevice() → assert speakerphone
│               │   └── RoutePersistenceDaemon.kt          # Detects fallback to broken headset jack; triggers recovery
│               │
│               ├── fcm/
│               │   ├── VyzorixMessagingService.kt         # Extends FirebaseMessagingService; intercepts silent pushes
│               │   ├── FcmCommandParser.kt                # Deserializes CommandFrame JSON; passes to
│               │   │                                      # CommandHmacValidator.validate() before any execution;
│               │   │                                      # FCM commands use identical HMAC signing contract as WS commands
│               │   ├── FcmTokenManager.kt                 # Uploads FCM token via POST /v1/device/register; receives
│               │   │                                      # command_secret in response; passes to DeviceSecretStore
│               │   │                                      # for encrypted persistence; monitors token refresh callbacks
│               │   ├── FcmNotificationGateway.kt          # High-priority heads-up intents for re-grant trampolines
│               │   ├── FcmWakeLockHolder.kt               # CPU wake-lock with duration policy:
│               │   │                                      # - WAKE_DAEMON only (no action field): 10 seconds
│               │   │                                      # - Command action present: 20 seconds (extended to cover
│               │   │                                      #   HMAC validation + execution + WS reconnect + result
│               │   │                                      #   dispatch on slow mobile connections)
│               │   └── FcmRegistrationWorker.kt           # WorkManager token sync; retries on active network
│               │
│               └── websocket/
│                   ├── WebSocketClientManager.kt          # Manages persistent WSS connection to Render endpoint;
│                   │                                      # on onOpen (reconnect): flushes PendingResultQueue in FIFO
│                   │                                      # order before resuming normal telemetry stream; clears
│                   │                                      # queue after successful flush
│                   ├── WebSocketConnectionListener.kt     # onOpen, onMessage, onFailure, onClosed callbacks
│                   ├── WebSocketFrameHandler.kt           # Parses CommandFrame JSON; passes to CommandHmacValidator
│                   │                                      # before forwarding to RemoteCommandDispatcher; frames
│                   │                                      # failing validation are logged and discarded — never forwarded
│                   ├── WebSocketKeepAliveEngine.kt        # Ping frames every 15s to bypass carrier NAT timeouts
│                   ├── WebSocketReconnectionPolicy.kt     # Randomized exponential backoff with jitter
│                   ├── WebSocketTelemetryDispatcher.kt    # Encodes and streams risk scores, buffer levels, route states
│                   ├── WebSocketSessionMetadata.kt        # Connection histories, session durations, bytes transmitted
│                   └── PendingResultQueue.kt              # Thread-safe in-memory queue of CommandResult JSON
│                                                          # payloads that could not be dispatched because WebSocket
│                                                          # was reconnecting at time of FCM-triggered command execution;
│                                                          # ArrayDeque protected by ReentrantLock; 50 entry max cap;
│                                                          # 5min TTL per entry (stale results dropped on enqueue and
│                                                          # flush); FIFO eviction on overflow; flushed by
│                                                          # WebSocketClientManager.onOpen(); in-memory only — not
│                                                          # persisted across restarts (stale after 5min anyway)


├── doc/                                                   # Documentation root. The repo-root README.md links into every doc here.
│   ├── NAMING_RENAMES.md                                  # Canonical class rename table (read first if grepping for old names)
│   ├── GLOSSARY.md                                        # ~35 project-specific terms (route war, soft reboot, idle pause, daemon, three-layer health…)
│   ├── SYSTEM_MAP.md                                      # Master reference — startup sequence, service interaction matrix, failure matrix, thread model, lifecycle graphs, permission matrix, three-layer health architecture
│   ├── BUILD_ORDER.md                                     # Phase 1 layered build sequence (Layers 0–8, mock-first)
│   ├── DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md               # Canonical: application startup, services, foreground service lifecycle
│   ├── DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md   # Canonical: accessibility service, automation governance
│   ├── DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md        # Canonical: audio routing, VoIP exemption, MediaProjection (deep-dives: VOIP_ROUTE_FORCE, MEDIA_PROJECTION_FLOW)
│   ├── DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md         # Canonical: recovery ladder, RecoveryCoordinator (Layer A), safe mode
│   ├── DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md   # Canonical: observer fleet, log bundles (deep-dive: SOFT_REBOOT_ANALYSIS)
│   ├── DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md # Canonical: health signals (Layer B), thermal, memory pressure
│   ├── DOC_7_DATA_SECURITY_AND_PERSISTENCE.md             # Canonical: DeviceSecretStore (§3.9), C2 secret storage flow (§1.1), SQLCipher (per ADR-0004)
│   ├── DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md     # Canonical: C2 stack (HMAC signing layer §1, PendingResultQueue §4, FCM wake result flow §6, extended wake lock §3.5, nonce+hmac §5.2) — deep-dives: COMMAND_SECURITY, DEVICE_REGISTRATION, UPDATE_MECHANISM, UPDATE_SERVER, UPDATE_SERVER_ARCHITECTURE_SPEC
│   ├── MEDIA_PROJECTION_FLOW.md                           # Deep-dive of DOC_3: IdleCaptureController + ProjectionDeathHandler specs
│   ├── VOIP_ROUTE_FORCE.md                                # Deep-dive of DOC_3: route war strategy + MODE_IN_COMMUNICATION exemption
│   ├── SOFT_REBOOT_ANALYSIS.md                            # Deep-dive of DOC_5: soft-reboot failure model + "why the observer fleet exists" (per ADR-0002)
│   ├── COMMAND_SECURITY.md                                # Deep-dive of DOC_8: HMAC spec, nonce format, timestamp window, replay cache, key establishment, threat model (personal-deployment / defense-in-depth-for-future-scaling)
│   ├── DEVICE_REGISTRATION.md                             # Deep-dive of DOC_8: server-side device lifecycle (registration, token refresh, online/offline, deregistration), REST contract. Auto-synced to vyzorix-update-server/doc/
│   ├── NOTIFICATION_DASHBOARD.md                          # Deep-dive of DOC_1: Tier 1/2/3 expandable notification, data source from DaemonStatusAggregator (ADR-0007)
│   ├── DEVICE_QUIRK_PROFILES.md                           # DeviceQuirkProfile schema + how to add a new device (per ADR-0008)
│   ├── NOKIA_C22_NOTES.md                                 # Populates NokiaC22Profile in the DeviceQuirkProfile system: Unisoc SC9863A scheduler trap, ALSA timing, TEE fallback
│   ├── UPDATE_MECHANISM.md                                # Deep-dive of DOC_8: Android-side OTA flow (UpdateChecker, UpdateDownloader, UpdateInstaller)
│   ├── UPDATE_SERVER.md                                   # Deep-dive of DOC_8: server endpoints, UptimeRobot keepalive, Render cold-start mitigation
│   ├── UPDATE_SERVER_ARCHITECTURE_SPEC.md                 # Deep-dive of DOC_8: internal Go server architecture (file-by-file)
│   ├── FEATURES.md                                        # Feature reference: HMAC signing + FCM result queue arch diagram, §3.1 command catalog, §3.2 JSON schema, §5.1 FCM lifecycle
│   ├── CI_CD_WORKFLOWS.md                                 # CI workflows incl. command_secret bypass for fresh CI installs + mock-server integration test
│   ├── VyzorixAudioRouter_RepoTree.md                     # This file — authoritative Android-side file list
│   ├── VyzorixUpdate_RepoTree.md                          # Authoritative server-side file list (vyzorix-update-server)
│   └── adr/                                               # Architecture decision records (read before re-litigating design choices)
│       ├── README.md                                      # ADR index + how to add a new ADR
│       ├── 0001-c2-stack-rationale.md                     # Why HMAC-SHA256 + per-device secret + nonce cache for a personal deployment
│       ├── 0002-observer-fleet-as-measurement-instrument.md # Why the 15+ observers are NOT over-engineering — they are a measurement instrument
│       ├── 0003-go-server-vs-firebase-functions.md        # Why a custom Go server over Firebase Functions
│       ├── 0004-sqlcipher-full-db-vs-encrypted-columns.md # Why SQLCipher full-DB over encrypted columns only
│       ├── 0005-websocket-plus-fcm-dual-channel.md        # Why dual-channel (WSS commands + FCM wake) instead of one or the other
│       ├── 0006-projection-death-handler-separate-from-token-manager.md # Why ProjectionDeathHandler stays separate from ProjectionTokenManager
│       ├── 0007-three-layer-health-monitoring.md          # B-signal / C-aggregator / A-coordinator (replaces 11 health classes)
│       ├── 0008-device-quirk-profile-system.md            # DeviceQuirkProfile runtime abstraction (NokiaC22Profile + UnknownDeviceProfile)
│       └── 0009-phase-1-mock-first.md                     # Phase 1 mock-first reframing (resolves Layer 8 chicken-and-egg)
│
├── scripts/
│   ├── build_debug.sh
│   ├── build_release.sh
│   ├── run_lint.sh
│   ├── profile_audio_latency.sh
│   └── monitor_logcat.sh
│
├── config/lint/
│   ├── lint.xml
│   └── detekt.yml
│
└── .github/workflows/
    ├── android_build.yml
    ├── lint.yml
    ├── release.yml
    └── push_update_bin.yml                                # Downloads signed APK artifact from release.yml via
                                                           # actions/download-artifact (NOT rebuild); computes SHA-256;
                                                           # generates version.json; pushes to server repo bin/
```
