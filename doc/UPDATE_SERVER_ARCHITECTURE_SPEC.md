# UPDATE_SERVER_ARCHITECTURE_SPEC.md — C2 & Update Server Architecture Specification (deep-dive of DOC_8)

> **This is a deep-dive of [`DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md`](./DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md).** DOC_8 covers the Android-side C2 stack and ties the server in. This document focuses on the **server-internal architecture** — the file-by-file Go layout. For server endpoints see `UPDATE_SERVER.md`; for device-lifecycle see `DEVICE_REGISTRATION.md`. Phase 1 uses the mock server (ADR-0009); the architecture below is the real server target for Phase 1.5.

## Document Purpose
This documents the system and file specification for the **Vyzorix Command & Control (C2) and Update Server** (`vyzorix-update-server`). 

It details:
1.  The general operational mechanics of the server.
2.  How the full-duplex WebSocket Hub, the Firebase Push Notification engine, and the SQLite storage layer coordinate.
3.  The detailed technical specification for **every single backend file** in the repository.

---
((note: i use render hobby plan and it hits to sleep in 15-30mins but a self-ping keepalive via UptimeRobot hitting /health every 10 minutes.)))
# 1. Core System Architecture & Operational Workflows.   

The server is engineered as a lightweight, static-binary Go web service. It coordinates three major runtime boundaries:

```text
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │                                  VYZORIX CONTROL PLATFORM                              │
  │                                                                                        │
  │  ┌─────────────────────────┐     WebSocket Link     ┌───────────────────────────────┐  │
  │  │  Web Dashboard    │◄──────────────────────►│     WebSocket Broker (Hub)     │  │
  │  │                         │                        │  - gorilla/websocket          │  │
  │  └──────────┬──────────────┘                        │  - Melody concurrent tunnels  │  │
  │             │ HTTP POST                             └──────────────▲────────────────┘  │
  │             │ (/v1/command)                                        │                   │
  │             ▼                                                      │ Persistent TCP    │
  │  ┌─────────────────────────┐    High-Priority Push                 │ Socket (Sub-20ms) │
  │  │   FCM Push Notifier     ├─────────────────────┐                 │                   │
  │  │   - firebase-admin SDK  │                     │                 │                   │
  │  └─────────────────────────┘                     ▼                 ▼                   │
  │                                             [ Google FCM ] ──► Nokia C22               │
  │                                             (Wake / Regrant)   (Vyzorix Client)        │
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

### A. The REST API Layer
*   **Purpose**: Manages public, low-overhead endpoints for the client updater (`/api/v1/version`, `/api/v1/changelog`) and handles signed APK package transfers (`/bin/*.apk`) using HTTP Range Support for resumable, chunked downloads. It also exposes private, authenticated routes for dashboard logins and manual command triggers.

### B. The Bidirectional WebSocket Hub
*   **Purpose**: Maintains persistent full-duplex TCP connections with active client daemons. Telemetry data (active CPU usage, memory thresholds, active routes, and risk scores) is parsed and broadcast directly to connected React web control panels in real-time. Incoming dashboard command frames are instantly routed to target device sockets with sub-millisecond dispatch times.

### C. The Firebase Messaging (FCM) Signaling Engine
*   **Purpose**: Translates out-of-band commands into high-priority silent push intents. If a target client goes offline or is put into deep Doze sleep, the server uses the FCM SDK to bypass system-level background execution limits and awaken the daemon.

---

# 2. Root Files & Deployment Specifications

```text
vyzorix-update-server/
├── go.mod
├── go.sum
├── main.go
├── Dockerfile
├── render.yaml
└── .env.example
```

### 2.1 `go.mod` & `go.sum`
*   **Location**: `/go.mod`, `/go.sum`
*   **Architectural Role**: Binds the compiler version and defines type-safe external package dependencies.
*   **Declared Packages**:
    *   `github.com/gin-gonic/gin` v1.9.1 (Main HTTP web framework)
    *   `github.com/gorilla/websocket` v1.5.0 (High-performance WebSocket protocol engine)
    *   `firebase.google.com/go/v4` v4.11.0 (Google Admin SDK for push notifications)
    *   `github.com/mattn/go-sqlite3` v1.14.17 (CGO-based SQLite driver)
*   **Failure Boundaries**: Package checksums are locked in `go.sum`. Any modified or hijacked dependency binary blocks compilation at build time.

### 2.2 `main.go`
*   **Location**: `/main.go`
*   **Architectural Role**: The master system initialization entrypoint. 
*   **Execution Sequence**:
    1.  Loads configurations from `.env` or system variables via `config.Load()`.
    2.  Instantiates the secure SQLite connection pool (`storage.InitDB()`) and executes migrations.
    3.  Initializes the Go Firebase Admin Client (`services/fcm.InitFCM()`).
    4.  Spawns the concurrent WebSocket signaling hub goroutine (`go hub.ActiveHub.Run()`).
    5.  Configures CORS, middleware interceptors, and registers GIN REST route controllers.
    6.  Binds and listens on the specified port.
*   **Failure Boundaries**: If the database file is locked or Firebase private credentials are invalid, it logs a fatal error and exits (`log.Fatalf`), blocking unprovisioned server deployments.

### 2.3 `Dockerfile`
*   **Location**: `/Dockerfile`
*   **Architectural Role**: Multi-stage, high-performance compilation Docker build script.
*   **Stages**:
    *   *Stage 1 (Builder)*: Uses `golang:1.20-alpine` to compile a statically linked, CGO-enabled Go binary (`CGO_ENABLED=1`).
    *   *Stage 2 (React Builder)*: Installs Node, npm-installs frontend dependencies, and builds the static React production assets.
    *   *Stage 3 (Runner)*: Copies only the compiled binary and the React public distribution assets into a bare `alpine:latest` runner image, keeping the container image size **under 35MB**.

### 2.4 `render.yaml`
*   **Location**: `/render.yaml`
*   **Architectural Role**: Declarative infrastructure blueprint for Render cloud hosting. Binds persistent disk volumes under `/data/` to prevent SQLite database resets when containers redeploy.

### 2.5 `.env.example`
*   **Location**: `/.env.example`
*   **Architectural Role**: Exposes configuration templates for environment variables, including ports, token verification secrets, paths, and debugging levels.

---

# 3. Storage & Configuration Modules

```text
vyzorix-update-server/
├── config/
│   └── config.go
└── storage/
    ├── sqlite.go
    └── migrations.go
```

### 3.1 `config/config.go`
*   **Path**: `config/config.go`
*   **Architectural Role**: Parses environmental variables into a strictly typed `Config` struct.
*   **Fields**:
    ```go
    type Config struct {
        Port                string // e.g. "3000"
        Env                 string // "production" or "development"
        DatabaseURL         string // e.g. "db/vyzorix.db"
        FirebaseCreds       string // Raw contents of vyzorix-service-account.json
        TokenSecret         string // Secret for authenticating dashboard requests
    }
    ```

### 3.2 `storage/sqlite.go`
*   **Path**: `storage/sqlite.go`
*   **Architectural Role**: Manages the local SQLite3 connection pool.
*   **Low-RAM Optimizations**:
    *   Enables Write-Ahead Logging (`PRAGMA journal_mode=WAL`) to allow concurrent reads and writes.
    *   Enables cache sharing (`PRAGMA cache_size=-2000` to lock cache footprint to 2MB).
    *   Sets a busy timeout (`PRAGMA busy_timeout=5000`) to prevent transaction lock issues.

### 3.3 `storage/migrations.go`
*   **Path**: `storage/migrations.go`
*   **Architectural Role**: Schema migrator. It executes raw DDL statements on start to verify the required database tables are active:
    *   `devices`: Tracks client UUIDs, Android SDK versions, active statuses, and last seen timestamps.
    *   `device_logs`: Stores incoming diagnostic traces, crash signatures, and matched blacklisted packages.
    *   `update_history`: Logs past OTA download transactions.

---

# 4. Data Models & Payload Schemas (`models/`)

Strict, type-safe structures mapping database and network payloads. It ensures all input streams match specifications, avoiding run-time type casting errors.

```text
vyzorix-update-server/models/
├── device.go
├── telemetry.go
├── command.go
└── response.go
```

### 4.1 `models/device.go`
*   **Path**: `models/device.go`
*   **Architectural Role**: Maps registered client attributes.
*   **Struct**:
    ```go
    type Device struct {
        ID             string    `json:"id" db:"id"`
        FcmToken       String    `json:"fcmToken" db:"fcm_token"`
        AndroidVersion string    `json:"androidVersion" db:"android_version"`
        IsOnline       bool      `json:"isOnline" db:"is_online"`
        LastSeen       time.Time `json:"lastSeen" db:"last_seen"`
    }
    ```

### 4.2 `models/telemetry.go`
*   **Path**: `models/telemetry.go`
*   **Architectural Role**: Models high-frequency inbound telemetry frames.
*   **Struct**:
    ```go
    type TelemetryFrame struct {
        DeviceID      string    `json:"deviceId"`
        Uptime        int64     `json:"uptime"`
        RiskScore     int       `json:"riskScore"`
        AudioMode     int       `json:"audioMode"`
        SpeakerOn     bool      `json:"speakerOn"`
        ActiveDevice  string    `json:"activeDevice"`
        BufferLevel   int       `json:"bufferLevel"`
        ThermalTemp   float64   `json:"thermalTemp"`
        Timestamp     time.Time `json:"timestamp"`
    }
    ```

### 4.3 `models/command.go`
*   **Path**: `models/command.go`
*   **Architectural Role**: Models outgoing C2 command transaction payloads.
*   **Struct**:
    ```go
    type CommandFrame struct {
        TransactionID string    `json:"transactionId"`
        DeviceID      string    `json:"deviceId"`
        Action        string    `json:"action"` // FORCE_SPEAKER, RESET_AUDIO_HAL, etc.
        Timestamp     time.Time `json:"timestamp"`
        Params        string    `json:"params"` // JSON-encoded parameter payload
    }
    ```

### 4.4 `models/response.go`
*   **Path**: `models/response.go`
*   **Architectural Role**: Standardized envelope structures for all GIN-delivered REST API JSON outputs, ensuring consistent response schemas.

---

# 5. Real-Time WebSocket Broker (`hub/`)

The `hub` module acts as the full-duplex network broker, keeping track of active device connections and managing read/write goroutines.

```text
vyzorix-update-server/hub/
├── hub.go
└── client.go
```

### 5.1 `hub/hub.go`
*   **Path**: `hub/hub.go`
*   **Architectural Role**: Binds client WebSocket connections.
*   **Hub Thread Loop**: Runs as a persistent background goroutine managing three active channels:
    *   `register`: Registers newly connected device clients.
    *   `unregister`: Clears disconnected clients and updates database statuses.
    *   `broadcast`: Directs command packets to their target devices.
*   **Failure Boundaries**: It uses mutex locks (`sync.RWMutex`) around the connection map to prevent concurrent write panic crashes when connection storms occur.

### 5.2 `hub/client.go`
*   **Path**: `hub/client.go`
*   **Architectural Role**: Binds network sockets. It wraps Gorilla's `*websocket.Conn` and spawns two persistent goroutines:
    1.  `readPump`: Listens for incoming client messages, deserializes telemetry, and updates SQLite parameters.
    2.  `writePump`: Dispatches command frames from the hub's buffer channel directly to the physical TCP socket.
*   **Heartbeats**: Enforces low-overhead ping/pong frames every 15 seconds to prevent network carrier timeout drops.

---

# 6. REST & WebSocket Controllers (`controllers/`)

```text
vyzorix-update-server/controllers/
├── updater.go
├── device.go
└── command.go
```

### 6.1 `controllers/updater.go`
*   **Path**: `controllers/updater.go`
*   **Architectural Role**: OTA distribution client. It serves `version.json` and static files, using GIN's file-serving wrapper with chunked Range Support to allow resumable APK updates.

### 6.2 `controllers/device.go`
*   **Path**: `controllers/device.go`
*   **Architectural Role**: Manages REST-based device registrations. It validates credentials, records active device statuses, and updates SQLite databases.

### 6.3 `controllers/command.go`
*   **Path**: `controllers/command.go`
*   **Architectural Role**: Receives manual C2 commands from the React dashboard via POST requests and forwards them to the WebSocket broker channels.
*   **Operational Flow**:
    ```text
    POST /v1/command ──► Parse JSON ──► Check if target online?
                                              │
                    ┌─────────────────────────┴─────────────────────────┐
                    ▼ (YES: Direct WS Route)                            ▼ (NO: FCM Signaling)
         hub.ActiveHub.Send()                                services.fcm.SendSilentPush()
    ```

---

# 7. Firebase Push Messaging (`services/fcm/`)

The `fcm` service initializes the Google Admin SDK and dispatches silent high-priority push wakeups.

```text
vyzorix-update-server/services/fcm/
├── fcm.go
└── notifier.go
```

### 7.1 `services/fcm/fcm.go`
*   **Path**: `services/fcm/fcm.go`
*   **Architectural Role**: Binds the Firebase Admin SDK. It reads `config.FirebaseCreds`, configures authentication credentials, and registers the global `*messaging.Client`.
*   **Core APIs**: Binds directly to `firebase.google.com/go/v4/messaging`.

### 7.2 `services/fcm/notifier.go`
*   **Path**: `services/fcm/notifier.go`
*   **Architectural Role**: silent push notifier. It formulates and dispatches silent high-priority push notifications to wake up sleeping background client processes.
*   **Payload Schema**:
    ```go
    message := &messaging.Message{
        Token: targetToken,
        Android: &messaging.AndroidConfig{
            Priority: "high", // Guarantees wake-up bypassing Doze mode
        },
        Data: map[string]string{
            "action": "WAKE_DAEMON",
            "command": "FORCE_SPEAKER",
        },
    }
    ```

---

# 8. Middleware Interceptors (`middleware/`)

```text
vyzorix-update-server/middleware/
├── auth.go
├── rate_limiter.go
└── logger.go
```

### 8.1 `middleware/auth.go`
*   **Path**: `middleware/auth.go`
*   **Architectural Role**: Request authorizer. It intercepts incoming REST and WebSocket handshakes, validating authorization headers against secrets to prevent unauthorized access.

### 8.2 `middleware/rate_limiter.go`
*   **Path**: `middleware/rate_limiter.go`
*   **Architectural Role**: Binds a sliding-window rate limiter on public update endpoints, blocking flood attempts from compromised or faulty clients.

### 8.3 `middleware/logger.go`
*   **Path**: `middleware/logger.go`
*   **Architectural Role**: Structured console logger. It writes incoming request parameters, elapsed execution times, and status results in JSON format.

---

# 9. Deployment & Static Configuration Scripts

```text
vyzorix-update-server/
├── api/v1/
│   ├── version.json
│   └── changelog.json
└── scripts/
    ├── generate_version.sh
    ├── compute_checksum.sh
    └── validate_apk.sh
```

### 9.1 `api/v1/version.json`
*   **Location**: `/api/v1/version.json`
*   **Role**: Public JSON file read by update clients. It declares the active release version code, checksum hashes, download URLs, and forced update flags.

### 9.2 `api/v1/changelog.json`
*   **Location**: `/api/v1/changelog.json`
*   **Role**: Public JSON file mapping release notes and bugfix logs.

### 9.3 `scripts/generate_version.sh`
*   **Path**: `/scripts/generate_version.sh`
*   **Architectural Role**: Automated script. It reads binary files, computes SHA-256 hashes, and automatically updates `version.json` configurations.

### 9.4 `scripts/compute_checksum.sh`
*   **Path**: `/scripts/compute_checksum.sh`
*   **Architectural Role**: Generates target SHA-256 for updater validation.

### 9.5 `scripts/validate_apk.sh`
*   **Path**: `/scripts/validate_apk.sh`
*   **Architectural Role**: Checks and validates APK integrity and metadata before allowing server compilations.
