# vyzorix-update-server —  Repo Tree

## Deployment notes

**Render free tier cold-start mitigation.** The free Render hobby plan suspends a service after ~15 minutes of HTTP inactivity. Cold-start latency on resume is 20–40s, which would cause the device's WebSocket reconnect logic to fail several backoff retries before the server is reachable. **An external UptimeRobot monitor pings `https://<render-app>.onrender.com/health` every 5–10 minutes** to keep the dyno warm. This is the agreed-upon mitigation. The health endpoint is intentionally lightweight (`200 OK` with no DB query) so the keepalive ping does not generate meaningful load.

If the UptimeRobot ping is ever removed, the device's `WebSocketReconnectionPolicy` will see longer disconnect windows after periods of low command traffic. The reconnect backoff schedule (1s → 2s → 4s → 8s → 16s → 32s) is designed to tolerate a 40s cold start, so the failure mode is "delayed reconnect", not "broken C2 channel".

For Phase 1 (mock-first per ADR-0009), the mock server runs locally and does not need UptimeRobot.

## Repo tree

```

vyzorix-update-server/

│
├── go.mod                                                 # Binds Go module path and compiler version; declares all external dependencies with pinned versions
│                                                          # - github.com/gin-gonic/gin v1.9.1 (HTTP web framework)
│                                                          # - github.com/gorilla/websocket v1.5.0 (WebSocket protocol engine)
│                                                          # - firebase.google.com/go/v4 v4.11.0 (Firebase Admin SDK for FCM push)
│                                                          # - github.com/mattn/go-sqlite3 v1.14.17 (CGO-based SQLite3 driver)
├── go.sum                                                 # Cryptographic checksums of all backend dependencies; any modified or hijacked dependency binary blocks compilation at build time
├── main.go                                                # Master system initialization entrypoint
│                                                          # Execution sequence:
│                                                          # 1. config.Load() — parses env vars
│                                                          # 2. storage.InitDB() — SQLite connection pool + migrations
│                                                          # 3. services/fcm.InitFCM() — Firebase Admin Client
│                                                          # 4. go hub.ActiveHub.Run() — spawns concurrent WebSocket broker goroutine
│                                                          # 5. Registers CORS, middleware interceptors, Gin REST route controllers
│                                                          # 6. Binds and listens on configured port
│                                                          # - Fatal exit if DB locked or Firebase credentials invalid (blocks unprovisioned deployments)
├── Dockerfile                                             # Multi-stage high-performance build
│                                                          # Stage 1 (Go Builder): golang:1.20-alpine; compiles statically linked CGO-enabled binary (CGO_ENABLED=1)
│                                                          # Stage 2 (React Builder): Node Alpine; npm install + Vite production build of frontend/
│                                                          # Stage 3 (Runner): bare alpine:latest; copies only compiled binary + React dist/; final image under 35MB
├── render.yaml                                            # Declarative Render cloud infrastructure manifest
│                                                          # - service name: vyzorix-update-server  [FIXED: was vyxorix-update-server — missing 'z', typo]
│                                                          # - Binds persistent disk volume at /data/ to prevent SQLite DB reset on container redeploy
│                                                          # - healthCheckPath: /health
│                                                          # - autoDeploy: true
│                                                          # NOTE: Render hobby plan sleeps after 15-30min; mitigated by UptimeRobot pinging /health every 10min
├── .env.example                                           # Environment variable template
│                                                          # - PORT (default 3000)
│                                                          # - NODE_ENV (production/development)
│                                                          # - DATABASE_URL (path to SQLite file, e.g. /data/vyzorix.db)
│                                                          # - FIREBASE_CREDENTIALS (raw JSON string of service account file)
│                                                          # - TOKEN_SECRET (secret for signing/validating dashboard JWT session tokens)
│                                                          # - JWT_SECRET (explicit separate key for JWT signing — required by middleware/auth.go for token generation; distinct from TOKEN_SECRET validation key)
├── .gitignore                                             # Excludes compiled binaries, node_modules, local SQLite files, .env, SSL certs, frontend/dist/
│
├── config/
│   └── config.go                                          # Parses environmental variables into strictly typed Config struct
│                                                          # Fields:
│                                                          #   Port            string  // e.g. "3000"
│                                                          #   Env             string  // "production" or "development"
│                                                          #   DatabaseURL     string  // e.g. "/data/vyzorix.db"
│                                                          #   FirebaseCreds   string  // raw contents of service-account.json
│                                                          #   TokenSecret     string  // secret for validating dashboard API requests
│                                                          #   JWTSecret       string  // [NEW FIELD] explicit key for JWT signing used by middleware/auth.go login flow; separate from TokenSecret to follow JWT best practice of distinct signing vs validation keys
│
├── storage/
│   ├── sqlite.go                                          # Manages SQLite3 connection pool with low-RAM optimizations
│                                                          # - PRAGMA journal_mode=WAL (concurrent reads and writes)
│                                                          # - PRAGMA cache_size=-2000 (locks cache footprint to 2MB)
│                                                          # - PRAGMA busy_timeout=5000 (prevents transaction lock deadlocks)
│   └── migrations.go                                      # Schema migrator; executes DDL on start to verify required tables exist
│                                                          # Tables created/verified:
│                                                          # - devices: UUID, FCM token, Android version, active status, last seen timestamp
│                                                          # - device_logs: diagnostic traces, crash signatures, blacklisted package matches
│                                                          # - update_history: past OTA download transactions
│                                                          # [NOTE: APK retention policy not enforced at DB level — see scripts/cleanup_old_apks.sh]
│
├── models/
│   ├── device.go                                          # Maps registered client attributes
│                                                          # type Device struct {
│                                                          #   ID             string    `json:"id" db:"id"`
│                                                          #   FcmToken       string    `json:"fcmToken" db:"fcm_token"`  [FIXED: was 'String' — not a valid Go built-in type, causes compile error]
│                                                          #   AndroidVersion string    `json:"androidVersion" db:"android_version"`
│                                                          #   IsOnline       bool      `json:"isOnline" db:"is_online"`
│                                                          #   LastSeen       time.Time `json:"lastSeen" db:"last_seen"`
│                                                          # }
│   ├── telemetry.go                                       # Models high-frequency inbound telemetry frames from device daemon
│                                                          # type TelemetryFrame struct {
│                                                          #   DeviceID      string    `json:"deviceId"`
│                                                          #   Uptime        int64     `json:"uptime"`
│                                                          #   RiskScore     int       `json:"riskScore"`
│                                                          #   AudioMode     int       `json:"audioMode"`
│                                                          #   SpeakerOn     bool      `json:"speakerOn"`
│                                                          #   ActiveDevice  string    `json:"activeDevice"`
│                                                          #   BufferLevel   int       `json:"bufferLevel"`
│                                                          #   ThermalTemp   float64   `json:"thermalTemp"`
│                                                          #   Timestamp     time.Time `json:"timestamp"`
│                                                          # }
│   ├── command.go                                         # Models outgoing C2 command transaction payloads to device daemon
│                                                          # type CommandFrame struct {
│                                                          #   TransactionID string    `json:"transactionId"`
│                                                          #   DeviceID      string    `json:"deviceId"`
│                                                          #   Action        string    `json:"action"` // FORCE_SPEAKER, RESET_AUDIO_HAL, TOGGLE_CAPTURE, REINIT_PROJECTION, etc.
│                                                          #   Timestamp     time.Time `json:"timestamp"`
│                                                          #   Params        string    `json:"params"` // JSON-encoded parameter payload
│                                                          # }
│   └── response.go                                        # Standardized envelope structures for all Gin REST API JSON outputs; ensures consistent response schemas across all endpoints
│
├── hub/
│   ├── hub.go                                             # Full-duplex WebSocket broker; tracks active device connections; manages broadcast channels and heartbeats
│                                                          # Hub goroutine loop manages three channels:
│                                                          # - register: registers newly connected device clients
│                                                          # - unregister: clears disconnected clients; updates DB online status
│                                                          # - broadcast: directs command packets to target device sockets
│                                                          # - Uses sync.RWMutex around connection map to prevent concurrent write panics on connection storms
│   └── client.go                                          # Wraps gorilla *websocket.Conn; spawns two persistent goroutines per connection
│                                                          # - readPump: listens for incoming client messages; deserializes telemetry; updates SQLite
│                                                          # - writePump: dispatches command frames from hub buffer channel to physical TCP socket
│                                                          # - Enforces ping/pong frames every 15s to prevent carrier NAT timeout drops
│
├── controllers/
│   ├── updater.go                                         # OTA distribution controller; serves version.json and static APK files; uses Gin file-serving with chunked Range Support for resumable downloads
│   ├── device.go                                          # Handles REST-based device registrations; validates credentials; records active device statuses and last-seen timestamps in SQLite
│   ├── command.go                                         # Receives manual C2 commands from React dashboard via authenticated POST; routes to device
│                                                          # Operational flow:
│                                                          # POST /v1/command → Parse JSON → Check if target device online?
│                                                          #   YES (device connected): hub.ActiveHub.Send() → direct WebSocket route
│                                                          #   NO (device sleeping): services/fcm.SendSilentPush() → FCM wake signal
│   └── websocket_handler.go                               # HTTP-to-WebSocket upgrade handler; the entry point that performs the HTTP → WebSocket protocol upgrade via gorilla/websocket Upgrader; hub.go manages connections once upgraded but this handler is the actual Gin route handler that initiates the upgrade; without this file no WebSocket connections can be established despite hub.go existing
│
├── services/
│   └── fcm/
│       ├── fcm.go                                         # Initializes Firebase Admin SDK; reads config.FirebaseCreds; configures auth credentials; registers global *messaging.Client
│       └── notifier.go                                    # Formulates and dispatches silent high-priority push wakeups to sleeping device daemons
│                                                          # Push payload schema:
│                                                          # message := &messaging.Message{
│                                                          #   Token: targetToken,
│                                                          #   Android: &messaging.AndroidConfig{ Priority: "high" }, // bypasses Doze mode
│                                                          #   Data: map[string]string{ "action": "WAKE_DAEMON", "command": "FORCE_SPEAKER" },
│                                                          # }
│
├── middleware/
│   ├── auth.go                                            # Request authorizer; validates Authorization headers against JWT_SECRET on private C2 endpoints and WebSocket handshakes; prevents unauthorized dashboard or device access
│   ├── cors.go                                            #  CORS middleware for Gin; explicitly configures allowed origins for Android client (android-app://com.vyzorix.audiorouter) and React dashboard domain; required because Gin has no default CORS handling unlike the Node/Express version which had explicit cors() config; without this all cross-origin requests from both the device and dashboard are blocked
│   ├── rate_limiter.go                                    # Token-bucket rate limiter on public update endpoints (/api/v1/version, /bin/*); blocks flood attempts from faulty or compromised clients
│   └── logger.go                                          # High-performance structured JSON console logger; writes incoming request method, path, elapsed execution time, and response status
│
├── bin/                                                   # APK binary storage directory; populated by GitHub Actions push_update_bin.yml workflow
│   ├── audiorouter-v2.0.0.apk                             # Release APK binaries committed by CI/CD — never manually edited
│   ├── audiorouter-v2.1.0.apk
│   └── audiorouter-v2.2.0.apk
│
├── api/
│   └── v1/
│       ├── version.json                                   # Current version metadata served at GET /api/v1/version; updated automatically by push_update_bin.yml on every tagged release; fields: version, versionCode, buildNumber, minSdkVersion, releaseDate, downloadUrl, checksumSha256, fileSize, releaseNotes, forced, changelog
│       └── changelog.json                                 # Historical changelog data served at GET /api/v1/changelog; updated automatically by push_update_bin.yml; append-prepend structure preserves full version history
│
├── db/
│   └── .gitkeep                                           # [FIXED: was vyzorix.db — runtime-generated SQLite file must not be committed; .gitkeep ensures directory exists in git while actual .db file is gitignored; Render persistent disk mounts /data/ at runtime where the real DB lives]
│
├── public/
│   ├── index.html                                         # Simple server landing page showing service status, version, and health endpoint link
│   ├── style.css                                          # Minimal styling for landing page
│   ├── health.json                                        # Static health check fallback file
│   ├── favicon.ico                                        # [Browser favicon; Vite will warn on build without this; also referenced by index.html
│   └── manifest.json                                      #  Web app manifest; required by Vite build pipeline to suppress missing-manifest warnings; declares app name, icons, theme color for the control dashboard PWA surface
│
├── scripts/
│   ├── generate_version.sh                                # Reads APK binary; computes SHA-256; auto-generates api/v1/version.json with all required fields
│   ├── compute_checksum.sh                                # Computes SHA-256 checksum for a given APK file; outputs raw hash string
│   ├── validate_apk.sh                                    # Validates APK integrity and metadata before allowing CI push; checks file type, minimum size, and signature presence
│   └── cleanup_old_apks.sh                                # [NEW] Prunes stale APK binaries from bin/ directory; retains only the N most recent releases (configurable, default 3); prevents bin/ from accumulating unbounded APK history; should be called by push_update_bin.yml after new APK is committed
│
├── .github/
│   └── workflows/
│       └── deploy.yml                                     # Auto-deploy to Render on push to main branch
│                                                          # Steps:
│                                                          # 1. Validate APK files in bin/ via scripts/validate_apk.sh
│                                                          # 2. Trigger Render deploy via API
│                                                          # 3. Wait 30s then hit /health to confirm deployment success
│
└── frontend/                                              # Vite + React + TypeScript + Tailwind CSS C2 dashboard SPA
    ├── package.json                                       # Frontend dependencies and build commands
    │                                                      # Key deps: react, react-dom, react-router-dom, axios, recharts, tailwindcss, typescript, vite
    ├── tsconfig.json                                      # Strict TypeScript compilation rules for all React components
    ├── vite.config.ts                                     # Vite build config; configures asset bundling, path aliases, proxy to Go backend in dev mode
    ├── tailwind.config.js                                 # Tailwind CSS theme extension mappings and custom breakpoints
    ├── postcss.config.js                                  # CSS postprocessor configuration for Tailwind
    ├── index.html                                         # Root HTML entrypoint hosting the React DOM mount point
    │
    └── src/
        ├── main.tsx                                       # React DOM initialization; imports global CSS; mounts App into #root
        ├── index.css                                      # Tailwind base, components, utilities; custom scrollbar classes
        ├── App.tsx                                        # Master layout coordinator; binds React Router routes; wraps with context providers (WebSocket, Theme, Auth)
        │
        ├── context/
        │   ├── WebSocketContext.tsx                       # Keeps persistent live C2 WebSocket connection alive across all page navigations; exposes sendMessage() and lastMessage to child components
        │   ├── ThemeContext.tsx                           # Global dark/light mode context; persists preference to localStorage
        │   └── AuthContext.tsx                            # Validates admin session states and credentials; wraps protected routes; exposes login/logout/isAuthenticated
        │
        ├── models/
        │   ├── device.interface.ts                        # TypeScript interface mapping the device data model from models/device.go
        │   ├── telemetry.interface.ts                     # TypeScript interface mapping high-frequency inbound telemetry frames from models/telemetry.go
        │   ├── command.interface.ts                       # TypeScript interface mapping C2 command transaction packets from models/command.go
        │   └── user.interface.ts                          # TypeScript interface mapping admin authentication models for login/session state
        │
        ├── hooks/
        │   ├── useWebSocket.ts                            # Implements automatic reconnection, ping cycles, message routing; consumes WebSocketContext
        │   ├── useTelemetry.ts                            # Pools and organizes live chart data arrays from incoming telemetry frames; feeds chart components
        │   ├── useDevices.ts                              # Connects to REST API endpoints to retrieve and refresh device fleet states
        │   └── useAuth.ts                                 # [STUB REQUIRED] Custom auth hook consumed by AuthContext.tsx and authService.ts; must exist even as minimal stub or frontend will not compile; handles login state, token storage, session validation
        │
        ├── services/
        │   ├── api.ts                                     # Custom Axios client with base URL, request/response interceptors, auth header injection for all backend REST calls
        │   └── authService.ts                             # Calls backend login/logout endpoints; stores and clears JWT tokens; consumed by useAuth.ts and AuthContext.tsx
        │
        ├── utils/
        │   ├── formatters.ts                              # Formats timestamps (ISO8601 → human readable), uptimes (seconds → HH:MM:SS), memory allocations (bytes → MB/GB)
        │   ├── validators.ts                              # Validates manual hex inputs, APK version strings, and range command parameters before dispatch
        │   └── cn.ts                                      # Tailwind class-name merging utility (clsx + twMerge pattern); used by UI component library for conditional class composition; ensure this is actually imported in at least one component or remove to avoid dead file
        │
        ├── pages/
        │   ├── LoginPage.tsx                              # [STUB REQUIRED] Admin login page; consumed by App.tsx router; must exist even as minimal stub or frontend will not compile; renders login form calling authService.login()
        │   ├── DashboardPage.tsx                          # Master dashboard; displays summary metrics, quick controls, active device counts, and SystemAlerts
        │   ├── DevicesPage.tsx                            # Device fleet grid; searchable, filterable, paginated list of all registered devices
        │   ├── DiagnosticsPage.tsx                        # Live C2 console; terminal log stream, live telemetry graphs, device-specific diagnostic views
        │   ├── UpdatesPage.tsx                            # [NEW] OTA update management page; displays current version.json state; provides UI to trigger WAKE_UP_UPDATER remote command; shows update history per device; referenced in FEATURES.md §3.1 remote command catalog but had no UI surface
        │   ├── SettingsPage.tsx                           # App configuration settings; cooldown thresholds, update check intervals, rate limit configs, server endpoint overrides
        │   └── NotFoundPage.tsx                           # Fallback 404 page for unmatched routes
        │
        └── components/
            ├── layout/
            │   ├── Sidebar.tsx                            # Navigation panel with responsive drawer; links to all pages; shows WebSocket connection status indicator
            │   ├── Navbar.tsx                             # Top navigation panel; displays server status, active alerts count, and live WebSocket connection state indicator
            │   │                                          # [NOTE: WebSocket connection status indicator must be surfaced here — if WebSocket drops the user currently has zero feedback; add connected/reconnecting/disconnected badge driven by WebSocketContext state]
            │   └── Footer.tsx                             # Copyright, API version, build target info
            │
            ├── ui/
            │   ├── Button.tsx                             # Custom Tailwind button variants: Primary, Secondary, Danger, Icon
            │   ├── Card.tsx                               # Content cards with consistent elevation, borders, and margins
            │   ├── Badge.tsx                              # Monochrome status badges: Green/OK, Yellow/Warn, Red/Critical, Gray/Unknown
            │   ├── Modal.tsx                              # Smooth animating overlay dialog with backdrop; used by DeviceDetailModal
            │   ├── Table.tsx                              # Responsive tabular fleet lists with sort and pagination support
            │   ├── Spinner.tsx                            # Loading animation for async data fetches and page transitions
            │   └── Tooltip.tsx                            # Accessible info overlays on hover for metric labels and command buttons
            │
            ├── dashboard/
            │   ├── DeviceGrid.tsx                         # Grid of active device cards with live status mutations driven by WebSocket telemetry
            │   ├── MetricsSummary.tsx                     # Fleet-wide statistics: total devices, online count, average risk score, average CPU/RAM pressure
            │   └── SystemAlerts.tsx                       # Live alert feed compiling critical exceptions, reboot alerts, and route loss events across all devices
            │
            ├── device/
            │   ├── DeviceDetailModal.tsx                  # In-depth overlay for a single device: all telemetry fields, log history, command history
            │   ├── DeviceControlPanel.tsx                 # Interactive remote command buttons: HAL Reset, Force Speaker, Toggle Capture, Reinit Projection, Dump Flight Data
            │   ├── DeviceLogTerminal.tsx                  # Scrollable console printing live log stream from selected device via WebSocket
            │   ├── RouteStateCard.tsx                     # Displays current routing state: speaker forced/headset lock/drifting; last correction timestamp
            │   ├── ThermalMetricsCard.tsx                 # Displays SoC temperature sensor readings, thermal throttle status, current sample rate reduction
            │   └── UpdateStateCard.tsx                    #  Displays current OTA update state per device (NOT_CHECKED / AVAILABLE / DOWNLOADING / DOWNLOADED / INSTALLING / SUCCESS / FAILED); shows last check timestamp, available version if any, and download progress; feeds from TelemetryFrame update fields; referenced by UpdateState enum in Android client
            │
            └── charts/
                ├── LiveCPUChart.tsx                       # Canvas/SVG real-time chart of live CPU load per device; windowed to last 60 data points
                ├── MemoryFootprintChart.tsx               # Live graph plotting JVM cache budgets, GC pause events, and native heap usage over time
                ├── RiskScoreChart.tsx                     # Interactive chart plotting RecoveryCoordinator risk-score history (from the soft-reboot policy that absorbed the former SoftRebootPredictor); threshold lines at 50 (warn) and 75 (critical)
                ├── BufferHealthChart.tsx                  # Real-time chart of audio capture buffer fill level (0-100%); plots underrun events; critical for diagnosing capture pipeline starvation on Nokia C22; data sourced from TelemetryFrame.bufferLevel
                └── ThermalChart.tsx                       # Real-time chart of SoC temperature over time from TelemetryFrame.thermalTemp; threshold lines matching ThermalMitigationPolicy levels; more operationally relevant for Nokia C22 diagnosis than CPU chart alone
```

---

