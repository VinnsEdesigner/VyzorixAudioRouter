# VyzorixAudioRouter — Repo Tree

```

VyzorixAudioRouter/

│
├── README.md                                              # Project Overview, Nokia C22 Notes, Setup and Service Lifecycle
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
├── app/                                                   # Final bootstrap APK module
│   ├── build.gradle.kts                                   # APK packaging, signing configs, dependency aggregation (Retrofit, OkHttp, Firebase, google-services plugin, etc.)
│   ├── proguard-rules.pro                                 # Keep rules for Accessibility + MediaProjection + services + Network + JNI symbols
│   │                                                      # - Includes: -keepclasseswithmembernames class * { native <methods>; }
│   │                                                      # - Includes Retrofit/OkHttp model serialization rules
│   │                                                      # - Includes Firebase/FCM keep rules
│   ├── google-services.json                               # Firebase project config (downloaded from Firebase Console, gitignored in CI but required locally)
│   │                                                      # - Place at /app/google-services.json per Firebase setup
│   │                                                      # - Required for FCM push signaling to compile
│   │                                                      # - Package name must match: com.vyzorix.audiorouter
│   └── src/main/
│       ├── AndroidManifest.xml                            # Master application manifest coordinating accessibility binding, system queries, package visibility, and the required foregroundServiceType declarations (mediaPlayback, dataSync) to satisfy Android 13 background constraints.
│       │                                                  # - Declares <receiver> for BootReceiver (exported=true, RECEIVE_BOOT_COMPLETED)
│       │                                                  # - Declares <receiver> for PackageChangeReceiver (MY_PACKAGE_REPLACED, PACKAGE_ADDED)
│       │                                                  # - Declares <receiver> for NotificationActionReceiver (custom broadcast actions)
│       │                                                  # - Declares <receiver> for StatusRefreshReceiver (ACTION_NO_OP, STATUS_REFRESH)
│       │                                                  # - Declares <receiver> for MediaButtonReceiver (MEDIA_BUTTON)
│       │                                                  # - Declares <receiver> for ScreenStateReceiver (SCREEN_ON, SCREEN_OFF)
│       │                                                  # - Declares <service> for PersistentAudioService (foregroundServiceType=mediaPlayback)
│       │                                                  # - Declares <service> for UpdateDownloadService (foregroundServiceType=dataSync)
│       │                                                  # - Declares <service> for TrampolineService (foregroundServiceType=shortService)
│       │                                                  # - Declares <service> for SilentKeepAliveService (bound service)
│       │                                                  # - Declares <service> for RouterAccessibilityService (BIND_ACCESSIBILITY_SERVICE)
│       │                                                  # - Declares <service> for VyzorixMessagingService (MESSAGING_EVENT intent filter)
│       │                                                  # - Declares <provider> for DiagnosticContentProvider (exported=false, READ_URI_PERMISSION)
│       │                                                  # - Declares <provider> for FileProvider (exported=false, GRANT_URI_PERMISSIONS, file_paths.xml)
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
│       │   │   ├── notification_channels.xml              # Notification Channel definitions (IDs, names, importance)
│       │   │   ├── ids.xml                                # Stable IDs for RemoteViews
│       │   │   ├── bools.xml                              # Feature toggles by build type/device
│       │   │   ├── integers.xml                           # Timing defaults / polling intervals
│       │   │   └── config.xml                             # Runtime-safe XML defaults
│       │   └── xml/
│       │       ├── accessibility_service_config.xml       # Static Accessibility metadata (description, flags, event types)
│       │       ├── accessibility_service_config_dynamic.xml # Runtime-modifiable accessibility configuration
│       │       ├── network_security_config.xml            # Network security policy (Render backend URL trust rules)
│       │       │                                          # - Defines <domain includeSubdomains="true">vyzorix-update-server.onrender.com</domain>
│       │       │                                          # - Blocks cleartext traffic except localhost (debug only)
│       │       ├── file_paths.xml                         # FileProvider paths for exporting crash bundles and APK installs
│       │       │                                          # - <files-path name="diagnostics" path="diagnostics/" />
│       │       │                                          # - <cache-path name="updates" path="updates/" />
│       │       ├── backup_rules.xml                       # Android Auto Backup rules
│       │       ├── data_extraction_rules.xml              # Android 12+ data extraction policy
│       │       ├── provider_paths.xml                     # FileProvider export paths (mirrors file_paths.xml for legacy support)
│       │       ├── notification_permission_flow.xml       # Notification rationale flow metadata
│       │       └── accessibility_gesture_map.xml          # Accessibility automation action map
│       │
│       ├── res/layout/                                    # RemoteViews Layouts for Notification Dashboard + Overlay
│       │   ├── notification_dashboard_collapsed.xml       # Compact view shown in status bar (Icon + Title + "Active" state)
│       │   ├── notification_dashboard_expanded.xml        # Full expanded view with ScrollView for detailed diagnostics
│       │   ├── notification_section_route.xml             # Tier 1 layout: Route status (Mode, Speaker, Headset)
│       │   ├── notification_section_capture.xml           # Tier 2 layout: Capture engine state (Buffer, Sample Rate)
│       │   ├── notification_section_health.xml            # Tier 3 layout: System health (Risk Score, Uptime)
│       │   ├── notification_section_diagnostics.xml       # Tier 3 layout: Crash signatures and last known state
│       │   ├── overlay_shortcut.xml                       # Layout for OverlayShortcutController (enable/disable toggle button)
│       │   └── update_progress.xml                        # Layout for UpdateNotificationHandler (download progress bar)
│       │
│       └── raw/
│           └── silent_anchor.wav                          # Silent VoIP anchor sample played by FocusPersistenceEngine via USAGE_VOICE_COMMUNICATION
│                                                          # - Accessed via AppVersionProvider.getRawResourceUri(R.raw.silent_anchor)
│                                                          # - Must NOT be placed in core/services/res/raw/ — exposed via URI helper instead
│
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
│           ├── AppExitDispatcher.kt                       # [MOVED HERE from previous misplacement at app root]
│           │                                              # - Immediate UI teardown utility called from bootstrap AND projection grant flows
│           │                                              # - Finishes all active activities
│           │                                              # - Ensures process doesn't linger with UI surfaces
│           │                                              # - Called after Accessibility grant and projection grant
│           │                                              # - NOTE: Kept in app/ because it references Activity classes directly
│           │                                              #   Bootstrap calls it via intent broadcast to avoid upward dependency
│           ├── BuildInfo.kt                               # Runtime build/version/device metadata
│           ├── ProcessEntryGuard.kt                       # Prevents duplicate process initialization
│           ├── StrictModeInitializer.kt                   # Debug-only strict mode enforcement
│           └── StartupProfiler.kt                         # Measures cold-start timings against PersistentAudioService bind completion
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
│   │           │   ├── BroadcastActions.kt                # Custom broadcast action strings (ACTION_NO_OP, STATUS_REFRESH, QUICK_TOGGLE, etc.)
│   │           │   ├── FilePaths.kt                       # Storage paths for logs, exports, temp files, update cache
│   │           │   ├── UpdateApiConstants.kt              # Server base URLs, API endpoints, version check intervals
│   │           │   │                                      # - BASE_URL = "https://vyzorix-update-server.onrender.com/api/v1/"
│   │           │   │                                      # - DOWNLOAD_URL_TEMPL = "https://vyzorix-update-server.onrender.com/bin/"
│   │           │   │                                      # - WEBSOCKET_C2_URL = "wss://vyzorix-update-server.onrender.com/c2"
│   │           │   ├── RemoteCommandConstants.kt          # Maps remote command keys, parameters, and telemetry headers
│   │           │   └── AppVersionProvider.kt              # [NEW] Wraps BuildConfig version info for cross-module access
│   │           │                                          # - Exposes VERSION_NAME, VERSION_CODE, APPLICATION_ID
│   │           │                                          # - Exposes getRawResourceUri() for resources in app/ module
│   │           │                                          # - Prevents core/services/ from directly importing app/BuildConfig
│   │           │                                          # - Initialized by VyzorixAppInitializer on startup
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
│   │           │   ├── DaemonStatus.kt                    # Unified status object aggregated by DaemonStatusProvider for dashboard updates
│   │           │   ├── AudioRouteState.kt                 # Current routing state snapshot (mode, devices, speaker flags)
│   │           │   ├── CrashSignature.kt                  # Structured crash pattern data for analysis and blacklisting
│   │           │   ├── PermissionState.kt                 # Current grant/deny state for all permissions
│   │           │   ├── SessionMetadata.kt                 # Diagnostic session metadata (timestamps, counts)
│   │           │   ├── ThermalState.kt                    # Device thermal status and throttling level
│   │           │   └── UpdateInfo.kt                      # Server version info, release notes, download URL, checksumSha256
│   │           ├── logging/
│   │           │   ├── Logger.kt                          # Unified Kotlin logging facade
│   │           │   ├── FileLogger.kt                      # Persistent disk logging (thread-safe)
│   │           │   └── LogcatBridge.kt                    # Lightweight logcat forwarding helper
│   │           ├── concurrency/
│   │           │   ├── AppDispatchers.kt                  # Coroutine dispatcher definitions (IO, Default, Main)
│   │           │   └── ServiceScope.kt                    # Long-lived service coroutine scope bound to PersistentAudioService lifecycle
│   │           ├── audio/
│   │           │   ├── AudioConstants.kt                  # Shared PCM/audio constants (Sample rates, buffer sizes, bit depths)
│   │           │   ├── AudioBufferPool.kt                 # Shared reusable PCM buffers to reduce GC pressure on capture thread
│   │           │   └── AudioDeviceUtils.kt                # Audio route/device helper methods
│   │           ├── device/
│   │           │   ├── NokiaC22DeviceProfile.kt           # Nokia C22 heuristics and compatibility flags (aggressive force mode enabled)
│   │           │   ├── ZygoteCrashMitigator.kt            # Delays risky operations during startup to avoid Zygote crash trigger
│   │           │   └── RuntimeHealthMonitor.kt            # [MOVED — see services/monitoring/] Stub kept here for binary compatibility
│   │           │                                          # - Delegates entirely to ProcessHealthMonitor in services/monitoring/
│   │           │                                          # - Do NOT add logic here; use the services layer version
│   │           └── utils/
│   │               ├── PermissionHelper.kt                # Runtime permission utility methods
│   │               ├── NotificationHelper.kt              # Foreground notification helpers
│   │               ├── IntentUtils.kt                     # Intent helper methods
│   │               ├── SafeHandler.kt                     # Exception-safe handler posting to main thread
│   │               ├── DelayedInitializer.kt              # Defers heavy startup tasks safely via coroutine delay
│   │               ├── AppConfig.kt                       # Centralized configuration (feature flags, thresholds, polling intervals)
│   │               ├── NotificationChannelManager.kt      # Creates and configures notification channels (A13 mandatory, called in VyzorixAppInitializer)
│   │               ├── PermissionIntentHelper.kt          # Centralized PendingIntent creation
│   │               │                                      # - Handles FLAG_IMMUTABLE / FLAG_MUTABLE correctly per API level
│   │               │                                      # - Prevents A12+ SecurityExceptions on implicit broadcast PendingIntents
│   │               ├── UpdateDownloadClient.kt            # Shared HTTP download utility (used by services/updates/)
│   │               │                                      # - Handles large file downloads with Range header resume support
│   │               │                                      # - Verifies SHA-256 checksum from server version.json
│   │               ├── NetworkPingHelper.kt               # [NEW] DNS reachability ping utility
│   │               │                                      # - Pings 8.8.8.8:53 to verify actual internet reachability
│   │               │                                      # - Separate from ConnectivityManager isConnected() which only checks link
│   │               │                                      # - Used by NetworkStateMonitor before triggering update checks
│   │               │                                      # - Runs on AppDispatchers.IO, returns Boolean suspend fun
│   │               ├── KeystoreManager.kt                 # Sealed Android Keystore manager to secure SQLCipher passcodes
│   │               │                                      # - Accesses KeyStore.getInstance("AndroidKeyStore")
│   │               │                                      # - Generates AES-256-GCM keys sealed in SoC TEE
│   │               │                                      # - Software fallback for Unisoc SC9863A unreliable TEE attestation
│   │               │                                      #   (install-time UUID + salt derivation via PBKDF2, silently degrades)
│   │               └── CryptoHelper.kt                    # Hardware-secured AES-GCM-NoPadding local encryptor/decryptor
│
│   ├── data/                                              # Persistent storage layer — depends on core/common only
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── kotlin/com/vyzorix/audiorouter/data/
│   │           ├── converters/
│   │           │   ├── AudioRouteTypeConverters.kt        # Converts AudioDeviceInfo, route enums to/from SQLite integers
│   │           │   ├── CrashEventTypeConverters.kt        # Converts crash signatures, timestamps, list serializations
│   │           │   ├── DaemonStateTypeConverters.kt       # Converts daemon state enums, complex state objects
│   │           │   ├── DateTimeTypeConverters.kt          # Converts Instant/Long timestamps for all entities
│   │           │   └── UpdateStateTypeConverters.kt       # Converts UpdateState enum, download URLs, progress timestamps
│   │           ├── database/
│   │           │   ├── DaemonDatabase.kt                  # Room database definition
│   │           │   │                                      # - Table: crash_events (crash bundles index)
│   │           │   │                                      # - Table: route_history (route transition log)
│   │           │   │                                      # - Table: permission_grants (permission grant timestamps)
│   │           │   │                                      # - Table: update_records (update download/install metadata)
│   │           │   │                                      # - Table: daemon_state_snapshots (last known state)
│   │           │   ├── DaemonDatabaseMigrations.kt        # Schema version management
│   │           │   │                                      # - Migration 1->2: adds update_records table
│   │           │   │                                      # - Migration 2->3: adds permission_grants table
│   │           │   │                                      # - Fallback: destructive migration if schema unrecoverable
│   │           │   └── SecureSupportHelper.kt             # Bridges SQLCipher 256-bit AES encryption layer directly into Room DB
│   │           │                                          # - Calls KeystoreManager.getDatabaseKey()
│   │           │                                          # - Passes key to SupportFactory(SQLiteDatabase.getBytes(passcode))
│   │           │                                          # - Ensures all table blocks encrypted before disk write
│   │           ├── dao/
│   │           │   ├── DaemonStateDao.kt                  # Room DAO for runtime state persistence (snapshots, uptime)
│   │           │   ├── CrashEventDao.kt                   # DAO for crash log entries (insert, query by date, purge old)
│   │           │   ├── RouteHistoryDao.kt                 # DAO for audio route transitions (last N transitions, failure counts)
│   │           │   ├── UpdateStateDao.kt                  # DAO for update download/install history (current state, progress)
│   │           │   └── PermissionGrantDao.kt              # [NEW] DAO for permission grant history
│   │           │                                          # - insert(), getLatestForPermission(), getAllGranted()
│   │           │                                          # - Used by PermissionStateRepository to persist grant timestamps
│   │           │                                          # - Prevents duplicate permission requests across reboots
│   │           ├── entity/
│   │           │   ├── CrashEvent.kt                      # @Entity for crash log entries (timestamp, signature, packageName, riskScore)
│   │           │   ├── RouteHistoryEntry.kt               # @Entity for audio route transitions (fromRoute, toRoute, durationMs)
│   │           │   ├── DaemonStateSnapshot.kt             # @Entity for full daemon state (audioMode, speakerOn, captureState, uptime)
│   │           │   ├── PermissionGrantRecord.kt           # @Entity for permission history (permissionName, grantTime, revokeTime)
│   │           │   └── UpdateRecord.kt                    # @Entity for update download/install tracking (version, state, checksumVerified)
│   │           ├── repository/
│   │           │   ├── StateRepository.kt                 # Unified data access layer (coordinates all DAOs)
│   │           │   ├── CrashEventRepository.kt            # CRUD operations for crash logs
│   │           │   ├── RouteHistoryRepository.kt          # CRUD operations for route history
│   │           │   └── UpdateRepository.kt                # CRUD operations for update state and history
│   │           ├── datastore/
│   │           │   ├── SettingsDataStore.kt               # Proto/DataStore configuration persistence
│   │           │   ├── RuntimeFlagsStore.kt               # Dynamic feature flags (safe mode, low-ram mode, adaptive sampling)
│   │           │   └── ProjectionMetadataStore.kt         # Projection token metadata persistence (grant time, revoke time)
│   │           └── migrations/
│   │               ├── LegacyPrefsMigration.kt            # SharedPreferences → DataStore migration for existing installs
│   │               └── CrashBundleMigration.kt            # Log schema evolution handling (v1 plaintext → v2 encrypted bundles)
│
│   ├── audioengine/                                       # Native C++ processing module — isolated, no Room dependency
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── cpp/
│   │       │   ├── CMakeLists.txt                         # Native audio build definitions
│   │       │   │                                          # - Compiles all 15 .cpp files into libaudioengine.so
│   │       │   │                                          # - Compiler flags: -O3 -ffast-math
│   │       │   │                                          # - Links: liboboe, libOpenSLES, liblog, libandroid
│   │       │   │                                          # - CGO_ENABLED=1, NDK min API 29
│   │       │   ├── capture_ring_buffer.cpp                # Lock-free single-producer single-consumer PCM ring buffer
│   │       │   │                                          # - Implements definitions from include/ring_buffer.h
│   │       │   ├── playback_resampler.cpp                 # Real-time sample rate conversion (44.1kHz -> 48kHz)
│   │       │   │                                          # - Falls back to linear interpolation under CPU stress
│   │       │   │                                          # - Implements declarations from include/playback_resampler.h
│   │       │   ├── latency_tracker.cpp                    # End-to-end pipeline delay profiler (capture -> hardware output)
│   │       │   │                                          # - Implements declarations from include/latency_tracker.h
│   │       │   ├── pcm_mixer.cpp                          # PCM mixing and volume normalization (prevents speaker clipping)
│   │       │   │                                          # - Implements declarations from include/pcm_mixer.h
│   │       │   ├── underrun_guard.cpp                     # Playback underrun detection and silent packet injection
│   │       │   │                                          # - Injects low-amplitude comfort noise when buffer starved
│   │       │   │                                          # - Implements declarations from include/underrun_guard.h
│   │       │   ├── audio_clock_sync.cpp                   # Capture/playback clock drift correction (micro-sample add/drop)
│   │       │   │                                          # - Implements declarations from include/clock_sync.h
│   │       │   ├── logger_engine.cpp                      # Native C++ -> android/log.h bridge (low-overhead, no JNI round-trip)
│   │       │   │                                          # - Implements declarations from include/logger_engine.h
│   │       │   ├── crash_guard.cpp                        # SIGSEGV/SIGBUS signal trap preventing JVM process kill
│   │       │   │                                          # - Implements declarations from include/crash_guard.h
│   │       │   ├── safe_jni_bridge.cpp                    # JNI object casting, array pin/release, telemetry -> Kotlin model conversion
│   │       │   │                                          # - Implements declarations from include/safe_jni_bridge.h
│   │       │   ├── watchdog_ping.cpp                      # Native heartbeat responder for ServiceHeartbeat pings
│   │       │   │                                          # - Implements declarations from include/watchdog_ping.h
│   │       │   ├── memory_guard.cpp                       # malloc/free interceptor preventing native memory leaks
│   │       │   │                                          # - Implements declarations from include/memory_guard.h
│   │       │   ├── ringbuffer_pressure.cpp                # Ring buffer density tracker (>80% triggers frame drop signal)
│   │       │   │                                          # - Implements declarations from include/ringbuffer_pressure.h
│   │       │   ├── audio_fallback_bridge.cpp              # Routes raw capture stream to Java-only pipeline if JNI fails
│   │       │   │                                          # - Implements declarations from include/audio_fallback_bridge.h
│   │       │   └── thread_priority_guard.cpp              # Elevates native threads to SCHED_FIFO RT scheduling class
│   │       │                                              # - Implements declarations from include/thread_priority_guard.h
│   │       │
│   │       └── include/
│   │           ├── ring_buffer.h                          # Lock-free ring buffer declarations and inline helpers
│   │           ├── audio_defs.h                           # Pure constants/enums header (sample rates, format defs) — no .cpp needed, header-only
│   │           ├── latency_tracker.h                      # Latency profiler function declarations
│   │           ├── pcm_mixer.h                            # PCM mixer function declarations
│   │           ├── clock_sync.h                           # Clock sync function declarations
│   │           ├── crash_guard.h                          # Native crash protection interfaces and signal handler declarations
│   │           ├── watchdog_ping.h                        # Native watchdog callback declarations
│   │           ├── safe_jni_bridge.h                      # Safe JNI wrapper function declarations
│   │           ├── audio_latency_profiler.h               # [HEADER-ONLY] Inline latency profiling helpers
│   │           │                                          # - All functions defined inline — no .cpp counterpart needed
│   │           │                                          # - Provides high-resolution timestamp helpers for pipeline timing
│   │           ├── playback_resampler.h                   # Resampler function declarations
│   │           │                                          # - Declares: resample_pcm(), get_resampler_state(), reset_resampler()
│   │           │                                          # - Required by safe_jni_bridge.cpp and audio_fallback_bridge.cpp
│   │           ├── underrun_guard.h                       #  Underrun guard function declarations
│   │           │                                          # - Declares: check_underrun(), inject_silence_frames(), get_underrun_count()
│   │           │                                          # - Required by AudioPipelineController.kt via JNI bridge
│   │           ├── logger_engine.h                        #  Native logger function declarations
│   │           │                                          # - Declares: native_log_debug(), native_log_error(), native_log_info()
│   │           │                                          # - Required by all other .cpp files for internal logging
│   │           ├── memory_guard.h                         #  Memory guard declarations
│   │           │                                          # - Declares: guarded_malloc(), guarded_free(), get_allocation_stats()
│   │           │                                          # - Required by capture_ring_buffer.cpp and pcm_mixer.cpp
│   │           ├── ringbuffer_pressure.h                  # Ring buffer pressure monitor declarations
│   │           │                                          # - Declares: get_buffer_pressure(), should_drop_frames(), reset_pressure_stats()
│   │           │                                          # - Required by AudioPipelineController.kt via JNI bridge
│   │           ├── audio_fallback_bridge.h                #  Fallback bridge declarations
│   │           │                                          # - Declares: init_fallback_bridge(), write_fallback_pcm(), teardown_fallback()
│   │           │                                          # - Required by NativeLoader.kt fallback path via JNI
│   │           └── thread_priority_guard.h                # Thread priority guard declarations
│   │                                                      # - Declares: elevate_thread_priority(), restore_thread_priority()
│   │                                                      # - Required by capture_ring_buffer.cpp and playback threads
│   │
│       └── kotlin/com/vyzorix/audiorouter/audioengine/
│           ├── NativeAudioBridge.kt                       # JNI bridge wrapper (nativeWriteBuffer, nativeReadBuffer, nativeGetLatency)
│           ├── NativeLoader.kt                            # Safe wrapper for System.loadLibrary("audioengine")
│           │                                              # - Catches UnsatisfiedLinkError gracefully
│           │                                              # - Logs failure and signals NativeSafetyController to disable native pipeline
│           │                                              # - Triggers JavaOnlyCaptureFallback if native load fails
│           ├── AudioPipeline.kt                           # Capture -> processing -> playback pipeline lifecycle hooks
│           ├── PcmFrame.kt                                # Shared PCM frame container with pooling to avoid GC overhead
│           ├── AudioPipelineController.kt                 # Coordinates native + Kotlin audio stages (buffer levels, JNI dispatch)
│           ├── PipelineStateTracker.kt                    # Tracks pipeline operational state (INITIALIZING, STREAMING, PAUSED, ERROR)
│           ├── NativeSafetyController.kt                  # Guards JNI/native runtime stability, receives warning signals from C++
│           ├── NativeCrashRecovery.kt                     # [MOVED — see services/resilience/] Stub kept for binary compat
│           │                                              # - Delegates to services/resilience/NativeCrashRecovery.kt
│           │                                              # - Do NOT add logic here
│           ├── PipelineBackpressureController.kt          # Drops oldest frames when consumer pipeline stalls
│           └── AudioEngineHealthState.kt                  # Native engine telemetry model (buffer pressure, resampler rate, underrun count)
│
│   ├── ui/                                                # Ultra-minimal trampoline UI layer
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   ├── activity_bootstrap.xml             # Tiny bootstrap permission layout
│   │       │   │   └── activity_projection.xml            # MediaProjection grant layout
│   │       │   ├── drawable/
│   │       │   │   ├── ic_speaker.xml
│   │       │   │   └── ic_permission.xml
│   │       │   └── values/
│   │       │       ├── strings.xml
│   │       │       ├── themes.xml
│   │       │       └── colors.xml
│   │       └── kotlin/com/vyzorix/audiorouter/ui/
│   │           ├── BootstrapActivity.kt                   # Opens Accessibility settings then exits immediately
│   │           ├── ProjectionPermissionActivity.kt        # Requests MediaProjection permission then exits immediately
│   │           ├── UiExitController.kt                    # Immediately destroys all transient UI surfaces
│   │           ├── HeadlessModeLauncher.kt                # Confirms PersistentAudioService health, terminates self in <50ms
│   │           └── CrashSafeActivity.kt                   # Minimal fallback activity with hardware acceleration disabled
│
│   └── services/                                          # Main headless orchestration layer
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml                        # Module manifest
│           │                                              # - Declares all receivers, services, and providers listed in app/AndroidManifest.xml
│           │                                              # - app/ manifest merges this at build time
│           ├── aidl/
│           │   └── com/vyzorix/audiorouter/
│           │       ├── IAudioRouterService.aidl           # Main Client-to-Service AIDL interface (getDaemonStatus, forceRoute, etc.)
│           │       └── IAudioRouterStatusListener.aidl    # Service-to-Client callback AIDL interface (onRouteChanged, onCaptureStateChanged)
│           ├── res/
│           │   ├── xml/
│           │   │   └── accessibility_service_config.xml   # Accessibility service event subscriptions (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOWS_CHANGED)
│           │   └── raw/
│           │       └── .gitkeep                           # Raw resources in services module (silent_anchor.wav stays in app/raw, accessed via AppVersionProvider)
│           │
│           └── kotlin/com/vyzorix/audiorouter/services/
│               │
│               ├── accessibility/
│               │   ├── RouterAccessibilityService.kt      # Primary daemon orchestrator entrypoint
│               │   │                                      # - Listens for UI events via AccessibilityEventRouter
│               │   │                                      # - Triggers boot sequence on enable via VyzorixAppInitializer
│               │   │                                      # - Calls LauncherIconHider.nukeLauncherIcon() on first grant
│               │   │                                      # - Starts BootStateRestorer if reboot detected
│               │   │                                      # - onServiceConnected() fires PersistentAudioService.startForeground()
│               │   ├── AccessibilityEventRouter.kt        # Dispatches raw accessibility events to PermissionScreenWatcher, AppLaunchObserver, WindowTransitionTracker
│               │   ├── PermissionScreenWatcher.kt         # Detects TYPE_WINDOW_STATE_CHANGED system permission dialogs
│               │   ├── SettingsAutomation.kt              # Automates Accessibility/system setting taps via node tree traversal
│               │   ├── OverlayPermissionAutomator.kt      # Automates SYSTEM_ALERT_WINDOW permission screen toggle
│               │   ├── ProjectionPermissionAutomator.kt   # Detects and clicks "Start Now" on MediaProjection grant dialog (<100ms)
│               │   ├── AudioRouteWatcher.kt               # Detects ACTION_HEADSET_PLUG and AudioManager.getDevices() changes
│               │   ├── UiRecoveryDaemon.kt                # Reopens crashed permission screens via intent re-fire
│               │   ├── AccessibilityStateTracker.kt       # Tracks enabled/disabled service state for all modules to query
│               │   ├── AccessibilityConfigManager.kt      # Manages runtime accessibility flags (disables node scanning under thermal throttle)
│               │   ├── AccessibilityRecoveryHandler.kt    # Handles Accessibility stripped on Nokia reboot, triggers UiRecoveryDaemon
│               │   └── OverlayShortcutController.kt       # TYPE_APPLICATION_OVERLAY floating toggle button (SYSTEM_ALERT_WINDOW)
│               │
│               ├── automation/
│               │   ├── AutomationRateLimiter.kt           # Max 5 settings clicks per minute hard cap
│               │   ├── HumanPresenceDetector.kt           # Queries keyguard state + MotionEvent to verify user idle before automation
│               │   ├── AutomationCooldownPolicy.kt        # Exponential backoff on failure (5s -> 30s -> 5min)
│               │   ├── AutomationSafetyGate.kt            # Final circuit breaker — disables automation entirely if threshold exceeded
│               │   ├── DialogRecognitionEngine.kt         # Parses AccessibilityNodeInfo tree for system dialog identification
│               │   ├── AccessibilityGestureQueue.kt       # Queues and dispatches AccessibilityService.dispatchGesture() calls
│               │   ├── AutomationDecisionEngine.kt        # Combines HumanPresenceDetector + AutomationRateLimiter + window state to decide safety
│               │   └── UiInteractionSnapshot.kt           # In-memory node tree snapshot for pre-click coordinate validation
│               │
│               ├── audio/
│               │   ├── AudioFocusHandler.kt               # AudioManager.OnAudioFocusChangeListener — handles gains, losses, transient duck
│               │   ├── InterruptionPolicy.kt              # Defines per-focus-loss-type reaction (call=pause, alarm=duck, notification=ignore)
│               │   ├── focus/
│               │   │   ├── FocusRecoveryCoordinator.kt    # Schedules delayed focus reclaim after system interruption ends
│               │   │   ├── FocusPriorityPolicy.kt         # PHONE_CALL > SYSTEM_ALARM > ACTIVE_DAEMON > BACKGROUND_MEDIA hierarchy
│               │   │   ├── FocusConflictResolver.kt       # Resolves competing focus between background media sessions
│               │   │   ├── FocusPersistenceEngine.kt      # Plays silent_anchor.wav via USAGE_VOICE_COMMUNICATION to hold state dominance
│               │   │   │                                  # - Accesses silent_anchor.wav via AppVersionProvider.getRawResourceUri()
│               │   │   ├── FocusEventHistory.kt           # SQLite journal of focus transitions for debugging
│               │   │   ├── FocusSuppressionPolicy.kt      # Suspends focus reclaim if system unstable or repeatedly rejects
│               │   │   └── AudioDuckController.kt         # Volume ducking/restore during system alerts and notifications
│               │   ├── media/
│               │   │   ├── ActiveMediaSessionResolver.kt  # Identifies dominant playback app via MediaSessionManager.getActiveSessions()
│               │   │   ├── MediaPriorityPolicy.kt         # Foreground media overrides navigation streams priority rule
│               │   │   ├── ForegroundPlaybackResolver.kt  # Correlates UsageStats + active media sessions to find pipeline source
│               │   │   ├── CaptureOwnershipArbitrator.kt  # Resolves multi-session simultaneous audio capture conflicts
│               │   │   ├── MediaSessionWatcher.kt         # Binds listeners for new media players starting up
│               │   │   ├── PlaybackOriginClassifier.kt    # Categorizes stream source (music app vs system alert vs navigation)
│               │   │   ├── MediaSessionPlaybackMonitor.kt # [RENAMED from PlaybackStateMonitor] Session-specific playback state tracking
│               │   │   │                                  # - Tracks active media session play/pause/stop callbacks
│               │   │   │                                  # - Notifies CaptureLifecycleController to adjust buffer sizes
│               │   │   │                                  # - Renamed to avoid collision with monitoring/SystemPlaybackMonitor.kt
│               │   │   └── SessionEvictionPolicy.kt       # Drops stale/inactive session structures to conserve memory
│               │   ├── route/
│               │   │   ├── RouteAssertionEngine.kt        # Continuously validates DEVICE_OUT_SPEAKER is active output
│               │   │   ├── RouteConflictResolver.kt       # Resolves routing conflicts when Bluetooth connects/disconnects
│               │   │   ├── RouteEscalationPolicy.kt       # STAGE_1(retry) -> STAGE_2(cycle BT) -> STAGE_3(HAL reset) -> STAGE_4(VoIP fallback)
│               │   │   └── RouteFailureJournal.kt         # Persistent database records of routing failures for hardware degradation analysis
│               │   └── session/
│               │       ├── AudioSessionRegistry.kt        # Tracks active playback session UIDs in local database table
│               │       ├── SessionPriorityManager.kt      # Chooses which playback session holds capture dominance
│               │       ├── PlaybackUidTracker.kt          # Maps active app audio UIDs to process identifiers
│               │       └── CaptureEligibilityChecker.kt   # Checks setAllowedCapturePolicy() for target package before capture
│               │
│               ├── bootstrap/
│               │   ├── TrampolineService.kt               # Lightweight bootstrap foreground service keeping process alive during init
│               │   ├── BootstrapCoordinator.kt            # Waits for Accessibility + projection token readiness before handoff
│               │   ├── PermissionStateMachine.kt          # INITIAL -> ACCESSIBILITY_GRANTED -> NOTIFICATIONS_GRANTED -> PROJECTION_GRANTED -> READY
│               │   ├── ServiceTrampoline.kt               # Launches PersistentAudioService, signals TrampolineService to stop
│               │   ├── SelfDestructController.kt          # Stops all transitional init services once daemon reaches steady-state
│               │   ├── LauncherIconHider.kt               # Permanently disables BootstrapActivity via PackageManager.setComponentEnabledSetting(DISABLED)
│               │   │                                      # - Prevents Nokia C22 launcher crash from icon tap
│               │   │                                      # - Verifies hidden via queryIntentActivities() check
│               │   └── BootStateRestorer.kt               # Reads last_state.json on reboot, restores PENDING state, validates projection token
│               │                                          # - Skips already-completed init phases
│               │                                          # - Resumes SpeakerForceEngine loop at previous state
│               │
│               ├── capture/
│               │   ├── MediaProjectionCaptureSession.kt   # Manages active projection session, token binding, revocation callbacks
│               │   ├── PlaybackCaptureEngine.kt           # Configures AudioRecord with AudioPlaybackCaptureConfiguration, reads PCM into AudioBufferPool
│               │   ├── AudioCaptureConfig.kt              # Capture parameters (48kHz, 16-bit, stereo, buffer budget)
│               │   ├── CapturePermissionStore.kt          # Persists MediaProjection consent state and token expiration
│               │   ├── PlaybackCaptureFactory.kt          # Builds AudioPlaybackCaptureConfiguration targeting system audio source
│               │   ├── CaptureLifecycleController.kt      # Start/stop capture loops; pauses when no active player detected (>30s silence)
│               │   ├── CaptureRecoveryEngine.kt           # Recovers AudioRecord thread if halted or resources reclaimed by OS
│               │   ├── ProjectionTokenManager.kt          # Token lifecycle management, onStop revocation callbacks, re-request scheduling
│               │   ├── TokenPersistence.kt                # Encrypts token metadata via CryptoHelper, stores to ProjectionMetadataStore
│               │   └── ProjectionDeathHandler.kt          # [NEW] Dedicated MediaProjection death callback handler
│               │                                          # - Registers MediaProjection.Callback for onStop()
│               │                                          # - Separate from ProjectionTokenManager (that manages lifecycle; this handles death)
│               │                                          # - On death: flushes capture, notifies CaptureRecoveryEngine
│               │                                          # - Triggers UiRecoveryDaemon for re-grant if token cannot be restored from cache
│               │                                          # - Logs death event to CrashTraceStore with timestamp and last known token state
│               │
│               ├── compat/
│               │   ├── Android13Behavior.kt               # Android 13-specific API workarounds (background activity restrictions, etc.)
│               │   ├── LegacyAudioFallback.kt             # Android 10/11 AudioManager API compatibility helpers
│               │   ├── ForegroundServiceCompat.kt         # FG service API differences across API levels 29-33
│               │   ├── NotificationCompatBridge.kt        # Cross-version notification building and RemoteViews compatibility
│               │   ├── AppInfoConfig.kt                   # Removes CATEGORY_LAUNCHER filter; Settings->Apps shows only [Uninstall][Disable]
│               │   ├── ForegroundStartRestrictionBypass.kt # Handles A13 foreground launch timing legally via notification windows
│               │   ├── NotificationTrampolineCompat.kt    # Handles Android 12+ notification trampoline restrictions
│               │   └── PendingIntentCompatPolicy.kt       # FLAG_IMMUTABLE / FLAG_MUTABLE enforcement per API level
│               │
│               ├── crash/
│               │   ├── GlobalExceptionHandler.kt          # Thread.UncaughtExceptionHandler — classifies SYSTEM_DIED vs APP_BUG, dumps state before exit
│               │   ├── NativeCrashMarker.kt               # Heuristic scanner for SIGSEGV/SIGBUS markers in own process logs
│               │   ├── SoftRebootTracker.kt               # Rolling buffer of last 5 reboot timestamps for instability pattern detection
│               │   └── LastKnownStateDumper.kt            # Continuously overwrites last_state.json (uptime, audioMode, route, foreground package)
│               │
│               ├── diagnostics/
│               │   ├── RoutingLogCollector.kt             # Captures and structures audio route transition diagnostics to SQLite
│               │   ├── AudioPolicySnapshot.kt             # Dumps AudioManager.getDevices() and active route states
│               │   ├── NokiaC22Compatibility.kt           # Adjusts diagnostic thresholds for Nokia C22 low-resource environment
│               │   ├── CrashTraceStore.kt                 # Persists and indexes JVM stack traces for remote telemetry reporting
│               │   ├── SoftRebootDetector.kt              # Parses ApplicationExitInfo for REASON_OTHER indicating framework restart
│               │   ├── RuntimeEventTimeline.kt            # Chronological daemon event log (routing switches, network reconnects, crashes)
│               │   ├── LogStreamCollector.kt              # In-memory event aggregator — buffers logs from all subsystems, flushes to disk
│               │   ├── RuntimeTraceAssembler.kt           # Correlates launch timelines and crash events into unified trace post-crash
│               │   ├── DiagnosticCompression.kt           # Compresses diagnostic files into encrypted ZIP archives
│               │   ├── EventCorrelationEngine.kt          # Matches app launches against system crashes to identify crash-trigger packages
│               │   ├── SystemHealthScorer.kt              # Computes 0-100 risk score (soft reboot+25, flash crash+15, deadlock+10, silence+5)
│               │   └── system/
│               │       ├── AppLaunchObserver.kt           # UsageStatsManager MOVE_TO_FOREGROUND watcher with 10-second survival timer
│               │       ├── WindowTransitionTracker.kt     # TYPE_WINDOWS_CHANGED monitor — detects Flash Crash (<500ms window lifetime)
│               │       ├── PackageStateObserver.kt        # Differentiates fresh app installs from established apps for crash correlation
│               │       ├── SoftRebootPredictor.kt         # SystemClock.uptimeMillis() anomaly detection for Zygote crash identification
│               │       └── RendererFailureDetector.kt     # Accessibility event silence >5s with foreground active = GPU/SurfaceFlinger freeze
│               │
│               ├── fallback/
│               │   ├── PlaybackCaptureFallback.kt         # Redirects pipeline to alternate AudioRecord config if MediaProjection revoked
│               │   ├── JavaOnlyCaptureFallback.kt         # [NEW] Pure Java AudioRecord capture path when native library fails to load
│               │   │                                      # - Activated by NativeLoader.kt on UnsatisfiedLinkError
│               │   │                                      # - Uses standard AudioRecord (no AudioPlaybackCaptureConfiguration)
│               │   │                                      # - Higher latency than native path but functional fallback
│               │   │                                      # - Logs FALLBACK_ACTIVE to CrashTraceStore for diagnostics
│               │   ├── CommunicationModeFallback.kt       # Bypasses capture entirely; maintains MODE_IN_COMMUNICATION for basic speaker routing
│               │   ├── SpeakerBypassFallback.kt           # Writes test audio directly to AudioTrack to verify physical speaker routing
│               │   └── SilentRecoveryMode.kt              # Minimal mode — deactivates all telemetry, dedicates CPU to core routing only
│               │
│               ├── fcm/
│               │   ├── VyzorixMessagingService.kt         # Extends FirebaseMessagingService — intercepts high-priority silent push payloads
│               │   ├── FcmCommandParser.kt                # Deserializes FCM JSON data payloads into structured CommandFrame objects
│               │   ├── FcmTokenManager.kt                 # Manages FCM registration token, uploads to Render backend via WorkManager
│               │   ├── FcmNotificationGateway.kt          # Posts high-priority fullScreenIntent heads-up for projection re-grant trampolines
│               │   ├── FcmWakeLockHolder.kt               # Holds 10-second PowerManager.WakeLock to complete push command before CPU sleep
│               │   └── FcmRegistrationWorker.kt           # WorkManager CoroutineWorker — reliably syncs FCM token to Render on active network
│               │
│               ├── foreground/
│               │   ├── PersistentAudioService.kt          # Primary foreground daemon (foregroundServiceType=mediaPlayback)
│               │   │                                      # - Holds capture loops, JNI bridges, WebSocket managers alive
│               │   │                                      # - Coordinates DaemonLifecycleManager.startAll()
│               │   │                                      # - Dashboard updates every 10s via ServiceNotificationDashboard
│               │   ├── DaemonStatusProvider.kt            # Aggregates live telemetry from all subsystems into DaemonStatus model
│               │   │                                      # - Pulls from: AudioRouteWatcher, PlaybackCaptureEngine, SoftRebootPredictor
│               │   │                                      #              DeviceThermalMonitor, ProcessHealthMonitor, CrashMetrics
│               │   │                                      #              BatteryImpactMonitor, UpdateStateStore, NetworkStateMonitor
│               │   │                                      # - Called by ServiceNotificationDashboard every 10 seconds
│               │   │                                      # - Also called by WebSocketTelemetryDispatcher for C2 stream
│               │   │                                      # - Returns DaemonStatus model defined in core/common/model/DaemonStatus.kt
│               │   ├── ServiceNotification.kt            # Configures base notification layout, builder priorities, non-clickable intent flags
│               │   ├── ServiceNotificationDashboard.kt    # Builds and pushes RemoteViews with live DaemonStatus to NotificationManager every 10s
│               │   ├── SilentKeepAliveService.kt          # Low-priority bound service maintaining binder references (prevents LMK kill)
│               │   ├── ServiceHeartbeat.kt                # Watchdog pinging active threads every 5s — triggers recovery on stall
│               │   ├── DaemonWatchdog.kt                  # Full daemon liveness monitor
│               │   │                                      # - Sends ping to all critical subsystems every 5s
│               │   │                                      # - Verifies: SpeakerForceEngine loop alive, capture thread running, playback thread running
│               │   │                                      # - On ping timeout: notifies ServiceRecoveryManager with failed subsystem ID
│               │   │                                      # - Separate from ServiceHeartbeat (that pings threads; this monitors subsystem health)
│               │   ├── PipelineHealthChecker.kt           # [NEW] Audio pipeline-specific liveness verifier
│               │   │                                      # - Verifies capture/playback threads are actively processing frames
│               │   │                                      # - Monitors buffer fill rate — zero fill rate = pipeline stalled
│               │   │                                      # - Reports health state to DaemonWatchdog and DaemonStatusProvider
│               │   │                                      # - Triggers CaptureRecoveryEngine if pipeline confirmed stalled
│               │   ├── ServiceRecoveryManager.kt          # Re-binds crashed services, executes StartupBackoffScheduler on repeated crashes
│               │   ├── BootReceiver.kt                    # RECEIVE_BOOT_COMPLETED broadcast receiver — triggers BootStateRestorer
│               │   └── actions/
│               │       ├── NotificationActionReceiver.kt  # Processes broadcast clicks from RemoteViews dashboard buttons
│               │       ├── QuickToggleAction.kt           # Instantly toggles speaker-forcing on/off, updates RemoteViews status icon
│               │       ├── RestartPipelineAction.kt       # Halts, flushes, and restarts AudioRecord + AudioTrack threads without process restart
│               │       └── EmergencyStopAction.kt         # Stops all services and releases permissions if bootloop state detected
│               │
│               ├── headless/
│               │   ├── HeadlessDaemonController.kt        # Manages background processes, routes log payloads to local databases
│               │   ├── HeadlessBootSequence.kt            # Launches core services directly on boot, no Activity rendering
│               │   ├── SilentPermissionFlow.kt            # Accessibility-driven permission verification and notification scheduling
│               │   └── InvisibleRecoveryCoordinator.kt    # Headless component restarts — zero UI flash, zero service interruption
│               │
│               ├── ipc/
│               │   ├── AudioRouterBinder.kt               # AIDL IAudioRouterService implementation — exposes daemon status to UI clients
│               │   ├── ServiceConnectionManager.kt        # Internal service binding manager, handles DeadObjectException and rebinds
│               │   ├── DaemonCommandDispatcher.kt         # Routes received commands to target modules (FORCE_SPEAKER -> SpeakerForceEngine, etc.)
│               │   ├── RemoteCommandExecutor.kt           # Validates, decrypts, and executes incoming WebSocket/FCM C2 commands
│               │   └── RemoteCommandResultDispatcher.kt   # Compiles execution results to JSON, dispatches back via WebSocket or HTTP postback
│               │
│               ├── managers/
│               │   ├── AudioRouteManager.kt               # Centralized route authority — executes speaker overrides, logs hardware transitions
│               │   ├── ProjectionSessionManager.kt        # Owns MediaProjection token lifecycle and revocation callback monitoring
│               │   ├── DaemonLifecycleManager.kt          # Enforces strict start order: focus -> routing -> capture -> schedulers
│               │   ├── SpeakerForceManager.kt             # Single source of routing truth — evaluates mode and commands SpeakerForceEngine
│               │   └── RecoveryOrchestrator.kt            # Global recovery coordinator — evaluates failures and triggers target fallbacks
│               │
│               ├── memory/
│               │   ├── MemoryClassProfiler.kt             # ActivityManager.isLowRamDevice() check, configures memory-conscious thresholds
│               │   ├── LowRamModeController.kt            # Deactivates unneeded tracking features under RAM pressure
│               │   ├── CacheBudgetManager.kt              # Dynamically resizes log queues and in-memory trace databases
│               │   ├── ServiceTrimCoordinator.kt          # ComponentCallbacks2.onTrimMemory() interceptor — commands resource reductions
│               │   ├── NativeHeapWatcher.kt               # JNI allocation monitor — logs potential native memory leaks
│               │   ├── AllocationPressureMonitor.kt       # JVM allocation spike detector — flags patterns that trigger GC pauses
│               │   └── EmergencyMemoryReducer.kt          # System.gc() + native JNI heap reclaim + cache reset on critical memory limit
│               │
│               ├── metrics/
│               │   ├── AudioLatencyMetrics.kt             # End-to-end capture->speaker latency measurement and logging
│               │   ├── RouteSwitchMetrics.kt              # Route transition success rates and duration tracking
│               │   ├── CrashMetrics.kt                    # Process-level crash counter (used by SystemHealthScorer)
│               │   ├── CapturePerformanceTracker.kt       # Packet drop rate and stream jitter measurement
│               │   └── BatteryImpactMonitor.kt            # Battery status polling and approximate power usage estimation
│               │
│               ├── monitoring/
│               │   ├── HeadsetStateMonitor.kt             # ACTION_HEADSET_PLUG native listener — reports phantom headset state
│               │   ├── BluetoothRouteMonitor.kt           # A2DP/SCO/HFP profile state change listeners
│               │   ├── AudioFocusMonitor.kt               # Tracks active focus owners system-wide, notifies InterruptionPolicy
│               │   ├── SystemPlaybackMonitor.kt           # [RENAMED from PlaybackStateMonitor] System-wide active media playback state tracking
│               │   │                                      # - Monitors MediaController callbacks for play/pause across all apps
│               │   │                                      # - Distinct from audio/media/MediaSessionPlaybackMonitor (session-specific)
│               │   │                                      # - Renamed to eliminate PlaybackStateMonitor name collision
│               │   ├── DeviceThermalMonitor.kt            # SoC thermal sensor polling, notifies ThermalMitigationPolicy on threshold breach
│               │   ├── RuntimeMemoryMonitor.kt            # System-wide RAM metrics, alerts when available memory < critical threshold
│               │   ├── ProcessHealthMonitor.kt            # Process health watchdog (memory leaks, ANR precursors, zombie threads)
│               │   │                                      # - RuntimeHealthMonitor in core/common/device/ delegates here
│               │   └── NetworkStateMonitor.kt             # ConnectivityManager.NetworkCallback + NetworkPingHelper DNS check before update triggers
│               │
│               ├── oem/
│               │   ├── NokiaAudioWorkarounds.kt           # Nokia-specific AudioManager retry routines bypassing background restrictions
│               │   ├── UnisocPlatformTweaks.kt            # Unisoc SC9863A thread parameter tuning and timing gap scheduling
│               │   ├── VendorRouteResetter.kt             # HAL re-probe via specific intents forcing routing table refresh
│               │   └── DeviceQuirkRegistry.kt             # Central registry of device-specific behaviors for automatic workaround selection
│               │
│               ├── performance/
│               │   ├── AdaptiveSamplingController.kt      # Dynamic polling interval scaling (500ms -> 2000ms when route stable)
│               │   │                                      # - Must ship with first build — prevents CPU churn on 2GB device
│               │   ├── CpuLoadBalancer.kt                 # Thread-load optimization to prevent CPU starvation on core audio threads
│               │   ├── FeatureLoadShedding.kt             # Auto-disables non-critical diagnostic observers under heavy CPU load
│               │   ├── LightweightModeController.kt       # Minimal operational mode — all background modules scaled back to minimum
│               │   └── ThermalMitigationPolicy.kt         # Reduces capture sample rate (48kHz->44.1kHz->32kHz) on thermal throttle
│               │
│               ├── permissions/
│               │   ├── PermissionStateRepository.kt       # Persists granted/denied state of all essential permissions via PermissionGrantDao
│               │   ├── PermissionRecoveryDaemon.kt        # Restores missing permissions, triggers trampolines on revocation
│               │   ├── OverlayPermissionManager.kt        # Launches SYSTEM_ALERT_WINDOW settings screen directly
│               │   ├── NotificationPermissionManager.kt   # Android 13 POST_NOTIFICATIONS runtime authorization check
│               │   ├── ProjectionGrantCache.kt            # Caches MediaProjection token state, monitors authorization lifecycle
│               │   └── PermissionAutoGranter.kt           # ActivityResultContracts-based permission request coordinator (no persistent Activity)
│               │
│               ├── playback/
│               │   ├── SpeakerPlaybackEngine.kt           # Sub-millisecond PCM playback coordinator — reads from C++ ring buffer, writes to AudioTrack
│               │   ├── AudioTrackController.kt            # Low-level AudioTrack play/pause/flush command coordinator
│               │   ├── AudioTrackFactory.kt               # Creates USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH AudioTrack for forced speaker routing
│               │   ├── LatencyOptimizer.kt                # Dynamic buffer resize to prevent stutters under heavy CPU load
│               │   ├── RouteRecoveryEngine.kt             # Re-initializes AudioTrack output on routing failure detection
│               │   ├── PlaybackGainController.kt          # Volume normalization preventing signal clipping on speaker hardware
│               │   ├── SpeakerOutputVerifier.kt           # Confirms active output device matches DEVICE_OUT_SPEAKER
│               │   ├── PlaybackThread.kt                  # High-priority dedicated worker thread for AudioTrack write loop
│               │   └── UnderrunRecovery.kt                # Injects silence frames to prevent AudioTrack hardware stall on buffer starvation
│               │
│               ├── projection/
│               │   ├── ProjectionLaunchCoordinator.kt     # Orchestrates projection requests — verifies screen/lock state before launch
│               │   ├── FullScreenIntentBridge.kt          # Posts high-priority fullScreenIntent notification to legally surface permission dialog
│               │   ├── ProjectionActivityMediator.kt      # Translucent trampoline Activity mediator for projection result callbacks
│               │   ├── ProjectionLaunchConditions.kt      # Pre-launch condition check (screen unlocked, notification channel active)
│               │   ├── ProjectionRetryPolicy.kt           # Throttles projection requests to prevent layout loops under stress
│               │   ├── ProjectionVisibilityGuard.kt       # Aborts launch if foreground eligibility missing — prevents background activity crash
│               │   └── ProjectionForegroundEscalator.kt   # Temporarily elevates service priority during permission re-grant sequences
│               │
│               ├── provider/
│               │   ├── DiagnosticContentProvider.kt       # ContentProvider wrapping encrypted ZIP log bundles for secure share intent export
│               │   └── AuthorityDefinitions.kt            # Content Provider authority URI (com.vyzorix.audiorouter.diagnostics) and permission flags
│               │
│               ├── receivers/
│               │   ├── NoOpReceiver.kt                    # Null-action broadcast receiver for non-clickable notification (prevents app launch on tap)
│               │   ├── StatusRefreshReceiver.kt           # Forces immediate DaemonStatusProvider refresh and dashboard update
│               │   ├── PackageChangeReceiver.kt           # MY_PACKAGE_REPLACED + PACKAGE_ADDED listener — updates AppLaunchObserver blacklist
│               │   ├── MediaButtonReceiver.kt             # MEDIA_BUTTON interceptor — prevents headset hardware events from hijacking audio route
│               │   └── ScreenStateReceiver.kt             # SCREEN_ON/OFF monitor — pauses high-frequency polling and drops WebSocket intervals on screen off
│               │
│               ├── resilience/
│               │   ├── AudioServerReconnectHandler.kt     # IBinder.DeathRecipient for audioserver crash — flushes AudioTrack refs, rebuilds after 1500ms
│               │   ├── BinderRecoveryLoop.kt              # Sequential IPC interface rebinding after binder crash (IAudioRouterService)
│               │   ├── ThreadIsolationExecutor.kt         # Single-threaded ExecutorService for crash-prone JNI calls — isolates from coroutine pool
│               │   ├── DeadObjectRecovery.kt              # DeadObjectException interceptor — terminates stale binders, re-establishes clean connection
│               │   ├── WatchdogEscalationPolicy.kt        # STAGE_1(SetSpeaker) -> STAGE_2(cycle BT) -> STAGE_3(HAL reset) -> STAGE_4(VoIP fallback)
│               │   └── NativeCrashRecovery.kt             # [MOVED HERE from audioengine/] JNI crash interceptor — rebuilds native state safely
│               │                                          # - audioengine/ version is a stub that delegates here
│               │                                          # - Intercepts JVM crashes originating from JNI
│               │                                          # - Coordinates with NativeSafetyController for graceful fallback
│               │
│               ├── scheduler/
│               │   ├── TaskScheduler.kt                   # Central delayed/repeating task coordinator (update checks, log syncs)
│               │   ├── TaskSchedulerFactory.kt            # WorkManager worker factory with retry/backoff constraint specifications
│               │   ├── WakeupAlarmCoordinator.kt          # AlarmManager.setAndAllowWhileIdle() for Doze mode penetration
│               │   ├── DeferredStartupQueue.kt            # Throttles heavy init tasks on boot — prevents Nokia C22 Zygote crash trigger
│               │   ├── IdleStateCoordinator.kt            # Doze state transition handler — scales back WebSocket intervals on sleep
│               │   ├── DeferredTaskWorker.kt              # Custom CoroutineWorker for background update execution
│               │   ├── WorkerFactory.kt                   # Custom WorkManager factory implementing DI for background workers
│               │   ├── WorkerConstraints.kt               # Task constraints (WiFi-only, unmetered network for APK downloads)
│               │   ├── ForegroundLaunchWindow.kt          # Legal foreground activity launch window coordinator under A13 Go constraints
│               │   ├── WakeLockCoordinator.kt             # PowerManager.WakeLock manager — ensures proper acquire/release to prevent battery drain
│               │   └── AlarmRecoveryBridge.kt             # Fallback wakeup alarms for service restart if OS terminates daemon
│               │
│               ├── security/
│               │   ├── ServicePermissionVerifier.kt       # Validates MODIFY_AUDIO_SETTINGS + other permissions before privileged command execution
│               │   ├── ProjectionTokenValidator.kt        # Verifies MediaProjection token validity, flags expired tokens for re-grant
│               │   ├── AccessibilityIntegrityChecker.kt   # Monitors accessibility service bind status, raises alert on unexpected disable
│               │   ├── SafeIntentSanitizer.kt             # Sanitizes incoming intents from external apps — prevents intent-redirect attacks
│               │   └── TokenEncryptor.kt                  # Encrypts cached projection credentials via CryptoHelper before persistent storage
│               │
│               ├── stability/
│               │   ├── CrashLoopProtector.kt              # Tracks restart frequency — >3 crashes in 5min triggers SafeModeController
│               │   ├── SafeModeController.kt              # Shuts non-essential modules — keeps only SpeakerForceEngine active
│               │   ├── StartupBackoffScheduler.kt         # Exponential restart delay (5s -> 30s -> 300s) after crash
│               │   └── ProcessRestartLimiter.kt           # Time-elapsed check blocking restart storms
│               │
│               ├── state/
│               │   ├── RuntimeStateStore.kt               # Persists active daemon state to DataStore
│               │   ├── AudioRouteSnapshot.kt              # Holds routing state snapshots for quick restoration
│               │   ├── ProjectionStateStore.kt            # Holds MediaProjection status snapshots
│               │   └── AccessibilityStateStore.kt         # Tracks daemon readiness state for bootstrap coordination
│               │
│               ├── storage/
│               │   ├── RuntimeCheckpointWriter.kt         # Writes lightweight system state checkpoint logs to database tables
│               │   ├── PersistentEventQueue.kt            # Thread-safe file-backed event queue surviving process crashes
│               │   ├── CrashBundleRetentionPolicy.kt      # Enforces max 10 archived log files, purges oldest, keeps disk under 25MB
│               │   └── logs/
│               │       ├── LogFileRotator.kt              # Monitors current_session.log, rotates at 2MB limit
│               │       ├── CrashSnapshotExporter.kt       # Compresses diagnostics to encrypted ZIP, generates FileProvider content:// URI
│               │       ├── TimestampedLogFormatter.kt     # UTC timestamps + thread ID + package source log line formatter
│               │       └── RuntimeSessionIndexer.kt       # Maps session IDs to log folders, prevents data corruption on concurrent writes
│               │
│               ├── testing/
│               │   ├── AudioRouteSimulation.kt            # Simulates headset insertion/removal route transitions for test coverage
│               │   ├── ProjectionStressTester.kt          # Tests projection token revoke/recovery loops under load
│               │   ├── AccessibilityFlowTester.kt         # Tests automation decision engine and gesture queue logic
│               │   ├── SoftRebootRecoveryTester.kt        # Simulates process collapse and verifies recovery sequence ordering
│               │   ├── DiagnosticTestRunner.kt            # On-device test runner triggerable from diagnostic tap pattern
│               │   ├── MockAccessibilityEvents.kt         # Simulates TYPE_WINDOW_STATE_CHANGED and TYPE_WINDOWS_CHANGED events
│               │   └── SimulatedCrashTrigger.kt           # Triggers controlled crash types for testing GlobalExceptionHandler paths
│               │
│               ├── updates/
│               │   ├── UpdateChecker.kt                   # GET /api/v1/version poller — compares remote versionCode vs AppVersionProvider.VERSION_CODE
│               │   ├── UpdateDownloader.kt                # APK download coordinator — delegates to UpdateDownloadService foreground service
│               │   ├── UpdateDownloadService.kt           # Foreground service (foregroundServiceType=dataSync) for APK download
│               │   │                                      # - Declared in AndroidManifest.xml module manifest
│               │   │                                      # - Uses UpdateDownloadClient for chunked Range-header download with resume
│               │   │                                      # - Verifies SHA-256 checksum after completion
│               │   │                                      # - Reports progress to UpdateNotificationHandler
│               │   │                                      # - Marks state DOWNLOADED in UpdateStateStore on success
│               │   ├── UpdateInstaller.kt                 # Intent.ACTION_INSTALL_PACKAGE with FileProvider content:// URI generation
│               │   ├── UpdateConfig.kt                    # Server URLs, check intervals, retry policies, WiFi-only download flag
│               │   ├── UpdateStateMonitor.kt              # ConnectivityManager.NetworkCallback + NetworkPingHelper for pre-download internet check
│               │   ├── UpdateStateStore.kt                # Room-backed download/install state persistence via UpdateStateDao
│               │   └── UpdateNotificationHandler.kt       # Progress notifications (Available -> Downloading X% -> Install Ready -> Failed + retry)
│               │
│               ├── voip/
│               │   ├── SilentVoipSession.kt               # Initializes active VoIP session state keeping OS in high-priority voice routing
│               │   ├── CommunicationRouter.kt             # Forces system audio streams through VoIP routing layers
│               │   ├── VoipAudioAnchor.kt                 # Silent looping AudioTrack (USAGE_VOICE_COMMUNICATION) keeping routing exemption active
│               │   ├── AudioModeKeeper.kt                 # Programmatically reasserts MODE_IN_COMMUNICATION if other apps attempt override
│               │   ├── SpeakerForceEngine.kt              # 500ms reassertion loop (MODE_IN_COMMUNICATION + isSpeakerphoneOn=true)
│               │   │                                      # - AdaptiveSamplingController pushes this to 2000ms+ when route stable
│               │   ├── CommunicationDeviceSelector.kt     # Android 11+ audioManager.getCommunicationDevice() reassertion to built-in speaker
│               │   └── RoutePersistenceDaemon.kt          # Detects speaker fallback to broken headset jack, triggers immediate recovery
│               │
│               └── websocket/
│                   ├── WebSocketClientManager.kt          # OkHttp persistent WebSocket to wss://vyzorix-update-server.onrender.com/c2
│                   ├── WebSocketConnectionListener.kt     # onOpen, onMessage, onFailure, onClosed raw event interceptor
│                   ├── WebSocketFrameHandler.kt           # Deserializes command JSON frames, forwards to DaemonCommandDispatcher
│                   ├── WebSocketKeepAliveEngine.kt        # 15-second ping frames to bypass carrier NAT timeout drops
│                   ├── WebSocketReconnectionPolicy.kt     # Jittered exponential backoff on disconnect (prevents server flood)
│                   ├── WebSocketTelemetryDispatcher.kt    # Encodes DaemonStatusProvider output to JSON, streams to Render dashboard in real-time
│                   └── WebSocketSessionMetadata.kt        # Connection duration, total bytes transmitted, session history
│
├── doc/                                                   # Documentation (note: doc/ not docs/ — keep consistent with uploaded file paths)
│   ├── README.md
│   ├── CI_CD_WORKFLOWS.md
│   ├── FEATURES.md
│   ├── MEDIA_PROJECTION_FLOW.md
│   ├── NOTIFICATION_DASHBOARD.md
│   ├── SERVER_REPO_STRUCTURE.md
│   ├── SOFT_REBOOT_ANALYSIS.md
│   ├── SYSTEM_MAP.md
│   ├── UPDATE_MECHANISM.md
│   ├── UPDATE_SERVER.md
│   ├── UPDATE_SERVER_ARCHITECTURE_SPEC.md
│   ├── VOIP_ROUTE_FORCE.md
│   ├── DOC_1_BOOTSTRAP_AND_ORCHESTRATION.md
│   ├── DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md
│   ├── DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md
│   ├── DOC_4_RESILIENCE_FALLBACKS_AND_RECOVERY.md
│   ├── DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md
│   ├── DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md
│   ├── DOC_7_DATA_SECURITY_AND_PERSISTENCE.md
│   └── DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md
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
        ├── android_build.yml                              # CI APK compilation on PR/push to develop
        ├── lint.yml                                       # Static analysis CI checks on every push
        ├── release.yml                                    # Tagged release — builds signed APK, creates GitHub Release with artifact
        └── push_update_bin.yml                            # Downloads signed APK from release.yml artifact (NOT rebuilds), pushes to server repo
                                                           # - Uses actions/download-artifact to pull from release.yml run
                                                           # - Generates version.json and pushes to vyzorix-update-server/bin/
                                                           # - Triggers Render deploy + health check wait
```
