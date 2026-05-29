# DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md — Real-Time C2 WebSockets, FCM Push, and OTA Self-Updating

## Document Purpose
This document is Part 8 of the 8-part Vyzorix System Mapping. It details the high-priority
silent FCM push receivers, live C2 WebSocket telemetry pipelines, and over-the-air (OTA)
resumable updates downloaders. This document serves as the implementation specification for
establishing high-performance cloud connectivity and self-repairing update loops on stock
Android 13 Go Edition.

All remote commands are HMAC-SHA256 signed using a per-device secret established at
registration. See COMMAND_SECURITY.md for the full signing contract, nonce format, timestamp
window, replay cache specification, and key establishment flow.

---

# 1. Bidirectional WebSocket C2 and Telemetry Stream Flow

The following mapping outlines the real-time, full-duplex communication pipeline running
inside the persistent foreground service, routing signed JSON commands and streaming hardware
telemetry:

```text
       CONTROL PANEL WEB DASHBOARD (React)                 RENDER GO BACKEND SERVER
                │                                                     │
                │◄───────────────── (WebSocket / JSON) ──────────────►│
                │                                                     │
                │                                                     ▼
                │                                            WebSocket Hub (hub.go)
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
                │                                                     ▼ (Validates HMAC signature)
                │                                        CommandHmacValidator
                │                                                     │
                │                                                     ▼ (Directs validated JSON payloads)
                │                                        RemoteCommandDispatcher
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

### 1.1 Command Signing Layer (inserted between server dispatch and device execution)

```text
Dashboard POST /v1/command
    │
    ▼
controllers/command.go
    │
    ▼ services/command_signer.go
    - Generate nonce (crypto/rand 16 bytes → hex)
    - Build canonical string: transactionId|deviceId|action|timestampMs|nonce|params
    - Compute HMAC-SHA256 using devices.command_secret from SQLite
    - Attach nonce + hmac fields to CommandFrame
    │
    ▼
hub.ActiveHub.Send() → device WebSocket
    │
    ▼ (on device)
WebSocketFrameHandler.kt
    │
    ▼ CommandHmacValidator.kt
    - Recompute HMAC from same canonical string
    - Constant-time compare
    - Check timestamp ±30s window
    - Check nonce not in NonceCache (replay protection)
    - Store nonce in NonceCache (5min TTL)
    │
    ├── VALID → RemoteCommandExecutor.kt → RemoteCommandDispatcher.kt → subsystem
    └── INVALID → log to CrashTraceStore → send rejection result → do not execute
```

---

# 2. Over-the-Air Resumable Update and Installation Flow

The following mapping outlines the secure, resumable over-the-air update download and manual
package installation process satisfying strict Android 13 Go Edition security:

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

The `fcm` package manages Google Play Services background push notifications, parses silent
payloads, validates HMAC signatures, and handles Wakelocks during background execution.

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
*   **Path**: `core/services/.../fcm/VyzorixMessagingService.kt`
*   **Architectural Role**: Binds the background push listener. Extends `FirebaseMessagingService`
    to intercept high-priority silent push payloads and forward them to parsers.
*   **Failure Boundaries**: If Play Services are terminated or blocked by aggressive Nokia
    battery policies, the app falls back to polling via `UpdateChecker` and `TaskScheduler`.

### 3.2 `FcmCommandParser.kt`
*   **Path**: `core/services/.../fcm/FcmCommandParser.kt`
*   **Architectural Role**: Parses push payloads. Validates incoming JSON payloads against
    command schemas. **Passes reconstructed `CommandFrame` to `CommandHmacValidator.kt`
    before triggering any local execution** — FCM commands use the same HMAC signing contract
    as WebSocket commands (see COMMAND_SECURITY.md §7).
*   **Failure Boundaries**: If HMAC validation fails, logs rejection to `CrashTraceStore`
    and discards the command without execution.

### 3.3 `FcmTokenManager.kt`
*   **Path**: `core/services/.../fcm/FcmTokenManager.kt`
*   **Architectural Role**: Binds the FCM registration token. On first registration, calls
    `POST /v1/device/register` which returns the per-device `command_secret`. Passes secret
    to `DeviceSecretStore.kt` for encrypted persistence. Monitors token refresh callbacks
    and re-uploads updated tokens to the Render server.

### 3.4 `FcmNotificationGateway.kt`
*   **Path**: `core/services/.../fcm/FcmNotificationGateway.kt`
*   **Architectural Role**: Dispatches high-priority heads-up intents for screen-casting
    re-grant trampolines if critical permission tokens are lost.

### 3.5 `FcmWakeLockHolder.kt`
*   **Path**: `core/services/.../fcm/FcmWakeLockHolder.kt`
*   **Architectural Role**: Secures a temporary CPU wake-lock to guarantee the background
    push command completes execution before the OS forces CPU sleep.
*   **Wake Lock Duration Policy**:
    - Payload contains only `WAKE_DAEMON` (no action field): **10 seconds**
    - Payload contains a command action field: **20 seconds** — extended to accommodate
      HMAC validation + command execution + WebSocket reconnect + result dispatch on slow
      mobile connections; 10s was insufficient for the full FCM wake command path
*   **Core APIs**: Relies on `PowerManager.WakeLock`.

### 3.6 `FcmRegistrationWorker.kt`
*   **Path**: `core/services/.../fcm/FcmRegistrationWorker.kt`
*   **Architectural Role**: Registers token sync tasks via `WorkManager` to reliably retry
    syncing the registration token to the Render control server on active networks.

---

# 4. Submodule: `websocket` (The Real-Time Command & Control Channel)

The `websocket` submodule manages OkHttp full-duplex socket connections, heartbeats,
pending result queuing, and live telemetry streaming.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/websocket/
├── WebSocketClientManager.kt
├── WebSocketConnectionListener.kt
├── WebSocketFrameHandler.kt
├── WebSocketKeepAliveEngine.kt
├── WebSocketReconnectionPolicy.kt
├── WebSocketTelemetryDispatcher.kt
├── WebSocketSessionMetadata.kt
└── PendingResultQueue.kt
```

### 4.1 `WebSocketClientManager.kt`
*   **Path**: `core/services/.../websocket/WebSocketClientManager.kt`
*   **Architectural Role**: Manages the persistent WebSocket client. Initiates connections
    to `wss://` Render endpoints and coordinates re-handshake queues after network drops.
*   **Reconnect Flush Behaviour**: On successful reconnect (onOpen callback), checks
    `PendingResultQueue` for unflushed command results. Dispatches all pending results in
    FIFO order before resuming normal telemetry stream. Clears queue after successful flush.
    This ensures FCM-triggered command results that could not be sent during reconnection
    are delivered once the WebSocket is stable.
*   **Core APIs**: Binds directly to OkHttp's `WebSocket` connection interfaces.

### 4.2 `WebSocketConnectionListener.kt`
*   **Path**: `core/services/.../websocket/WebSocketConnectionListener.kt`
*   **Architectural Role**: Direct listener. Intercepts raw network events (`onOpen`,
    `onMessage`, `onFailure`, `onClosed`) and routes messages to handlers.

### 4.3 `WebSocketFrameHandler.kt`
*   **Path**: `core/services/.../websocket/WebSocketFrameHandler.kt`
*   **Architectural Role**: Decodes WebSocket frame payloads. Parses command JSON structures.
    Passes parsed `CommandFrame` to `CommandHmacValidator.kt` for signature verification
    before forwarding validated commands to `RemoteCommandDispatcher` for execution. Frames
    that fail validation are rejected and logged — never forwarded.

### 4.4 `WebSocketKeepAliveEngine.kt`
*   **Path**: `core/services/.../websocket/WebSocketKeepAliveEngine.kt`
*   **Architectural Role**: Writes lightweight ping frames every 15 seconds to bypass
    carrier NAT timeouts and keep the background socket alive.

### 4.5 `WebSocketReconnectionPolicy.kt`
*   **Path**: `core/services/.../websocket/WebSocketReconnectionPolicy.kt`
*   **Architectural Role**: Implements randomized exponential backoff reconnection retry
    policies with jitter to prevent server congestion during disconnections.

### 4.6 `WebSocketTelemetryDispatcher.kt`
*   **Path**: `core/services/.../websocket/WebSocketTelemetryDispatcher.kt`
*   **Architectural Role**: Encodes and streams active device metrics (risk scores, buffer
    levels, route states) back to the Render dashboard in real-time.

### 4.7 `WebSocketSessionMetadata.kt`
*   **Path**: `core/services/.../websocket/WebSocketSessionMetadata.kt`
*   **Architectural Role**: Records connection histories, active session durations, and
    total bytes transmitted.

### 4.8 `PendingResultQueue.kt`
*   **Path**: `core/services/.../websocket/PendingResultQueue.kt`
*   **Architectural Role**: Thread-safe in-memory queue of serialized `CommandResult` JSON
    payloads that could not be dispatched because the WebSocket was reconnecting at the time
    of command execution.
*   **Problem solved**: When the device is woken via FCM push, the command may finish
    executing before `WebSocketClientManager` completes its TLS handshake and auth. Without
    this queue, the result is silently dropped and the dashboard has no confirmation the
    command ran.
*   **Specification**:
    - Storage: `ArrayDeque<PendingResult>` protected by `ReentrantLock`
    - Max capacity: 50 entries — prevents unbounded growth on repeated FCM wakes without
      successful WebSocket reconnects
    - TTL per entry: 5 minutes — stale results (older than 5min) are dropped on enqueue
      and on flush attempt since the dashboard would have timed out waiting anyway
    - Persistence: In-memory only — not persisted across process restarts; result TTL
      ensures no stale deliveries after daemon restart
    - Flush trigger: Called by `WebSocketClientManager.kt` on `onOpen` callback
    - Overflow policy: When capacity is full, oldest entry is evicted (FIFO eviction)
*   **Integration points**:
    - `RemoteCommandResultDispatcher.kt`: checks `WebSocketClientManager.isConnected()`
      before send; if not connected, enqueues to `PendingResultQueue` instead of dropping
    - `WebSocketClientManager.kt`: flushes queue in `onOpen` before resuming telemetry

---

# 5. Remote Command Interface & Payload Contract

When commands are issued via the React Dashboard, they travel to the Android client as
typed, HMAC-signed JSON packets. The client validates the signature before execution.

## 5.1 Supported Remote Command Catalog

| Command Action | Parameters | Natively Allowed | Non-Root Bypass | HMAC Signed |
|---|---|---|---|---|
| `FORCE_SPEAKER` | None | Yes | `MODE_IN_COMMUNICATION` + `isSpeakerphoneOn=true`; 500ms reassertion loop | ✅ |
| `RESET_AUDIO_HAL` | None | No | Soft HAL reset via BT stream cycling + sub-audible micro-burst | ✅ |
| `TOGGLE_CAPTURE` | `active` (boolean) | Yes | Starts/stops `AudioRecord` read loops on MediaProjection thread pool | ✅ |
| `REINIT_PROJECTION` | None | No | High-Priority `fullScreenIntent` → automated by Accessibility engine | ✅ |
| `DUMP_FLIGHT_DATA` | None | Yes | Gathers metrics → JSON postback immediately | ✅ |
| `UPLOAD_CRASH_ZIP` | None | Yes | `CrashSnapshotExporter` → ZIP → POST binary block | ✅ |
| `SET_LOG_LEVEL` | `level` (string) | Yes | Modifies `Logger.minLogLevel` in memory | ✅ |
| `WAKE_UP_UPDATER` | None | Yes | Overrides WorkManager delays; runs `UpdateChecker` instantly | ✅ |

## 5.2 WebSocket Command Frame (JSON Contract)

Command frames include HMAC signature fields. See COMMAND_SECURITY.md §2 for full field
definitions and §3 for canonical string construction.

```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId":      "uuid-nokia-c22-092831",
  "action":        "REINIT_PROJECTION",
  "timestamp":     "2026-05-26T12:00:00.000Z",
  "params":        "{}",
  "nonce":         "a3f8c1d2e4b56789",
  "hmac":          "9f3a1bc2d4e5678901234567890abcdef1234567890abcdef1234567890abcdef"
}
```

On execution completion, the device dispatches a type-safe feedback packet. If the WebSocket
is reconnecting at dispatch time, the result is queued in `PendingResultQueue` and flushed
on next successful WebSocket connection.

```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId":      "uuid-nokia-c22-092831",
  "action":        "REINIT_PROJECTION",
  "success":       true,
  "timestamp":     "2026-05-26T12:00:00.080Z",
  "payload": {
    "tokenState":  "ACTIVE",
    "bufferLevel": "98%"
  }
}
```

Rejection result (HMAC validation failure):
```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId":      "uuid-nokia-c22-092831",
  "action":        "REINIT_PROJECTION",
  "success":       false,
  "timestamp":     "2026-05-26T12:00:00.012Z",
  "payload": {
    "error":  "INVALID_SIGNATURE",
    "detail": "HMAC mismatch — command rejected"
  }
}
```

---

# 6. FCM Wake Command Result Flow

This section documents the result dispatch path specifically for commands triggered via FCM
push when the device was sleeping. This path is distinct from the WebSocket-initiated
command path.

```text
Render Server
    │
    ▼ (device offline / sleeping)
services/fcm/notifier.go → Google FCM Cloud Gateway
    │
    ▼ (silent push delivered to sleeping device)
VyzorixMessagingService.kt receives push intent
    │
    ▼
FcmWakeLockHolder.kt grabs 20s CPU lock
(20s because command payload detected — extended from 10s default)
    │
    ▼
FcmCommandParser.kt deserializes CommandFrame
    │
    ▼
CommandHmacValidator.kt validates HMAC signature
    │
    ├── INVALID → log rejection → release wake lock → stop
    │
    └── VALID
        │
        ▼
RemoteCommandExecutor.kt executes command (~1-5s)
        │
        ▼
RemoteCommandResultDispatcher.kt attempts result dispatch
        │
        ├── WebSocketClientManager.isConnected() = true
        │       → send result immediately via WebSocket
        │
        └── WebSocketClientManager.isConnected() = false
                │
                ▼ (WebSocket still reconnecting — TLS handshake + auth in progress)
            PendingResultQueue.enqueue(result)
                │
                ▼ (WebSocket reconnects — onOpen fires)
            WebSocketClientManager flushes PendingResultQueue
                │
                ▼
            Result delivered to Render server → Dashboard updated
```

**Timeline on slow mobile connection (worst case)**:
```
0ms    FcmWakeLockHolder acquires 20s lock
50ms   FcmCommandParser parses payload
100ms  CommandHmacValidator validates HMAC
200ms  RemoteCommandExecutor begins command execution
3000ms Command completes (e.g. REINIT_PROJECTION automation)
3100ms RemoteCommandResultDispatcher checks WebSocket — still reconnecting
3100ms PendingResultQueue.enqueue(result)
8000ms WebSocketClientManager completes TLS + auth (worst case on 2G/slow 4G)
8010ms onOpen fires → PendingResultQueue.flush()
8020ms Result delivered
       (12s remaining on 20s wake lock — sufficient margin)
```

---

# 7. Storage Encryption & Cryptographic Pipeline

Pre-installed security layers on the Nokia C22 actively scan local storage directories. To
prevent unauthorized retrieval of diagnostic logs, state flags, or payment timelines,
Vyzorix encrypts database tables transparently.

```text
  ┌──────────────────────┐
  │   Android Keystore   │  ← Cryptographically sealed inside hardware Secure Element (SoC)
  └──────────┬───────────┘
             │ getOrGenerateDatabaseKey()
             ▼
  ┌──────────────────────┐
  │   SupportFactory     │  ← Dynamically unlocks SQLCipher database using PBKDF2 hash
  └──────────┬───────────┘
             │ Binds factory
             ▼
  ┌──────────────────────┐
  │   DeviceSecretStore  │  ← command_secret encrypted with same Keystore key via TokenEncryptor
  └──────────┬───────────┘
             │ Decrypts on read
             ▼
  ┌──────────────────────┐
  │  CommandHmacValidator│  ← Uses decrypted secret only within validation call scope
  └──────────────────────┘
```

## 7.1 SQLCipher Integration Details

Rather than relying on basic file-system encryption, Vyzorix uses SQLCipher (256-bit AES
transparent SQLite cryptor). See DOC_7_DATA_SECURITY_AND_PERSISTENCE.md for full details.

---

# 8. Submodule: `updates` (The Over-the-Air Self-Updater)

(unchanged from original — see UPDATE_MECHANISM.md for full OTA update system documentation)

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/updates/
├── UpdateChecker.kt
├── UpdateDownloader.kt
├── UpdateDownloadService.kt
├── UpdateInstaller.kt
├── UpdateConfig.kt
├── UpdateStateMonitor.kt
├── UpdateStateStore.kt
└── UpdateNotificationHandler.kt
```

See UPDATE_MECHANISM.md for full OTA endpoint contract, Render server setup, download
resumption logic, checksum verification, and security considerations.
