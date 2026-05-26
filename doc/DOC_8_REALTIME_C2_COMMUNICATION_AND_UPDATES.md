# DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md — Real-Time C2 WebSockets, FCM Push, and OTA Self-Updating

## Document Purpose
This document is Part 8 of the 8-part Vyzorix System Mapping. It details the high-priority silent FCM push receivers, live C2 WebSocket telemetry pipelines, and over-the-air (OTA) resumable updates downloaders. This document serves as the implementation specification for establishing high-performance cloud connectivity and self-repairing update loops on stock Android 13 Go Edition.

---

# 1. Bidirectional WebSocket C2 and Telemetry Stream Flow

The following mapping outlines the real-time, full-duplex communication pipeline running inside the persistent foreground service, routing JSON commands and streaming hardware telemetry:

```text
       CONTROL PANEL WEB DASHBOARD (React)                 RENDER GO BACKEND SERVER
                │                                                     │
                │◄───────────────── (WebSocket / JSON) ──────────────►│
                │                                                     │
                │                                                     ▼
                │                                            WebSocket Hub / melody
                │                                                     │
                │                                                     ▼ (Persistent TCP socket)
                │                                        WebSocketClientManager (Device client)
                │                                                     │
                │                                                     ▼ (Intercepts raw network frames)
                │                                        WebSocketConnectionListener
                │                                                     │
                │                                                     ▼
                │                                        WebSocketFrameHandler
                │                                                     │
                │                                                     ▼ (Directs JSON payloads)
                │                                        DaemonCommandDispatcher
                │                                                     │
                │ 1. Executes routing adjustment                      ▼
                │◄────────────────────────────────────────────────────┤
                │                                                     │
                │ 2. Compiles active telemetry                        ▼
                │├───────────────────────────────────────────────────►│
                │                                                     │
                │ 3. WebSocketTelemetryDispatcher streams metrics     ▼
                │├───────────────────────────────────────────────────►│
```

---

# 2. Over-the-Air Resumable Update and Installation Flow

The following mapping outlines the secure, resumable over-the-air update download and manual package installation process satisfying strict Android 13 Go Edition security:

```text
                                  UPDATECHECKER SCHEDULE
                                             │
                                             ▼
                                 UpdateStateMonitor (Wi-Fi?)
                                             │
                                             ▼ (Polls GET /api/v1/version)
                                     UpdateChecker
                                             │
                       ┌─────────────────────┴─────────────────────┐
                       │                                           │
              Newer Version Available?                   No New Version?
                       │                                           │
                       ▼ (YES: DOWNLOAD)                           ▼ (NO: IDLE)
         UpdateNotificationHandler                           Prune download cache
                       │                                           │
                       ▼ (Shows available notification)            ▼
                User Taps [Download]                       Schedule next poll
                       │
                       ▼ (Launches FOREGROUND_SERVICE_DATA_SYNC)
               UpdateDownloadService
                       │
                       ▼ (OkHttp downloads with Range headers)
                 UpdateDownloader (caches chunk ranges to disk)
                       │
                       ▼ (Verify SHA-256 Checksum)
                 UpdateStateStore (Marks DOWNLOAD_SUCCESS)
                       │
                       ▼ (FileProvider content:// URI)
                 UpdateInstaller (Intent.ACTION_INSTALL_PACKAGE)
                       │
                       ▼ (Mandatory user confirmation dialog)
                    PackageInstaller (OS verification)
                       │
                       ▼ (Success: Restart process)
          BootStateRestorer (Loads previous snap context)
```

---

# 3. Submodule: `fcm` (The Silent Push Pager)

The `fcm` package manages Google Play Services background push notifications, parses silent payloads, and handles Wakelocks during background execution.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/
├── VyzorixMessagingService.kt
├── FcmCommandParser.kt
├── FcmTokenManager.kt
├── FcmNotificationGateway.kt
├── FcmWakeLockHolder.kt
└── FcmRegistrationWorker.kt
```

### 3.1 `VyzorixMessagingService.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/VyzorixMessagingService.kt`
*   **Architectural Role**: Binds the background push listener. It extends `FirebaseMessagingService` to intercept high-priority, silent push payloads and forward them to parsers.
*   **Core APIs**: Binds directly to `com.google.firebase.messaging.FirebaseMessagingService`.
*   **Failure Boundaries & Escape Hatches**: If Play Services are terminated or blocked by aggressive Nokia battery policies, the app falls back to polling via `UpdateChecker` and `TaskScheduler`.

### 3.2 `FcmCommandParser.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/FcmCommandParser.kt`
*   **Architectural Role**: Parses push payloads. It validates incoming JSON payloads against command schemas and triggers the local execution engines.

### 3.3 `FcmTokenManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/FcmTokenManager.kt`
*   **Architectural Role**: Binds the FCM registration token, uploads it to your Render control server, and monitors token refresh callbacks.

### 3.4 `FcmNotificationGateway.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/FcmNotificationGateway.kt`
*   **Architectural Role**: Dispatches high-priority heads-up intents for screen-casting re-grant trampolines if critical permission tokens are lost.

### 3.5 `FcmWakeLockHolder.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/FcmWakeLockHolder.kt`
*   **Architectural Role**: Secures a temporary CPU wake-lock (up to 10s) to guarantee the background push command completes execution before the OS forces CPU sleep.
*   **Core APIs**: Relies on `PowerManager.WakeLock`.

### 3.6 `FcmRegistrationWorker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/fcm/FcmRegistrationWorker.kt`
*   **Architectural Role**: Registers token sync tasks. It leverages `WorkManager` to reliably retry syncing the registration token to your Render control server on active networks.

---

# 4. Submodule: `websocket` (The Real-Time Command & Control Channel)

The `websocket` submodule manages OkHttp full-duplex socket connections, heartbeats, and live telemetry streaming.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/
├── WebSocketClientManager.kt
├── WebSocketConnectionListener.kt
├── WebSocketFrameHandler.kt
├── WebSocketKeepAliveEngine.kt
├── WebSocketReconnectionPolicy.kt
├── WebSocketTelemetryDispatcher.kt
└── WebSocketSessionMetadata.kt
```

### 4.1 `WebSocketClientManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketClientManager.kt`
*   **Architectural Role**: Manages the persistent WebSocket client. It initiates connections to `wss://` Render endpoints and coordinates re-handshake queues after network drops.
*   **Core APIs**: Binds directly to OkHttp's `WebSocket` connection interfaces.

### 4.2 `WebSocketConnectionListener.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketConnectionListener.kt`
*   **Architectural Role**: Direct listener. It intercepts raw network events (`onOpen`, `onMessage`, `onFailure`, `onClosed`) and routes messages to handlers.

### 4.3 `WebSocketFrameHandler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketFrameHandler.kt`
*   **Architectural Role**: Decodes WebSocket frame payloads, parses command JSON structures, and forwards actions to `DaemonCommandDispatcher` for execution.

### 4.4 `WebSocketKeepAliveEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketKeepAliveEngine.kt`
*   **Architectural Role**: Writes lightweight ping frames every 15 seconds to bypass carrier NAT timeouts and keep the background socket alive.

### 4.5 `WebSocketReconnectionPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketReconnectionPolicy.kt`
*   **Architectural Role**: Implements randomized exponential backoff reconnection retry policies with jitter to prevent server congestion during disconnections.

### 4.6 `WebSocketTelemetryDispatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketTelemetryDispatcher.kt`
*   **Architectural Role**: Encodes and streams active device metrics (risk scores, buffer levels, route states) back to your Render dashboard in real-time.

### 4.7 `WebSocketSessionMetadata.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/WebSocketSessionMetadata.kt`
*   **Architectural Role**: Records connection histories, active session durations, and total bytes transmitted.

---

# 5. Submodule: `updates` (The Over-the-Air Self-Updater)

The `updates` package coordinates update polling, resumable downloads, and invokes system package installers using FileProvider content URIs.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/
├── UpdateChecker.kt
├── UpdateDownloader.kt
├── UpdateInstaller.kt
├── UpdateConfig.kt
├── UpdateStateMonitor.kt
├── UpdateStateStore.kt
└── UpdateNotificationHandler.kt
```

### 5.1 `UpdateChecker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateChecker.kt`
*   **Architectural Role**: Periodically polls the server API endpoint (`GET /api/v1/version`) to check for newer APK version releases.

### 5.2 `UpdateDownloader.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateDownloader.kt`
*   **Architectural Role**: Handles chunked, parallel APK downloads, using OkHttp range headers to support resuming interrupted downloads.

### 5.3 `UpdateInstaller.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateInstaller.kt`
*   **Architectural Role**: Generates secure FileProvider content URIs (`content://com.vyzorix.audiorouter.fileprovider`) and invokes system `PackageInstaller` intents to prompt manual user installation.
*   **Core APIs**: Binds to `Intent.ACTION_INSTALL_PACKAGE`.

### 5.4 `UpdateConfig.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateConfig.kt`
*   **Architectural Role**: Defines server URLs, update check intervals, and local file storage paths.

### 5.5 `UpdateStateMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateStateMonitor.kt`
*   **Architectural Role**: Watches active network profiles and defers heavy update downloads to unmetered connections.

### 5.6 `UpdateStateStore.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateStateStore.kt`
*   **Architectural Role**: Persists the progress and state of active update downloads inside database tables.

### 5.7 `UpdateNotificationHandler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/UpdateNotificationHandler.kt`
*   **Architectural Role**: Manages update notifications, displaying progress bars, download states, and installation prompts.
