# vyzorix-update-server — 
```

vyzorix-update-server/

│
├── go.mod                                                 # Binds Go module path and compiler version; declares all
│                                                          # external dependencies with pinned versions
│                                                          # - github.com/gin-gonic/gin v1.9.1
│                                                          # - github.com/gorilla/websocket v1.5.0
│                                                          # - firebase.google.com/go/v4 v4.11.0
│                                                          # - github.com/mattn/go-sqlite3 v1.14.17
│                                                          # - crypto/hmac + crypto/sha256 (stdlib — no extra dep needed)
├── go.sum                                                 # Cryptographic checksums of all backend dependencies;
│                                                          # modified or hijacked dependency blocks compilation at build time
├── main.go                                                # Master system initialization entrypoint
│                                                          # Execution sequence:
│                                                          # 1. config.Load() — parses env vars into Config struct
│                                                          # 2. storage.InitDB() — SQLite pool + migrations
│                                                          # 3. services/fcm.InitFCM() — Firebase Admin Client
│                                                          # 4. go hub.ActiveHub.Run() — spawns WS broker goroutine
│                                                          # 5. Registers CORS, middleware, Gin REST routes
│                                                          # 6. Binds and listens on configured port
│                                                          # - Fatal exit if DB locked or Firebase credentials invalid
├── Dockerfile                                             # Multi-stage build
│                                                          # Stage 1 (Go Builder): golang:1.20-alpine; statically linked
│                                                          #   CGO-enabled binary (CGO_ENABLED=1)
│                                                          # Stage 2 (React Builder): Node Alpine; npm install + Vite
│                                                          #   production build of frontend/
│                                                          # Stage 3 (Runner): bare alpine:latest; binary + React dist/
│                                                          #   only; final image under 35MB
├── render.yaml                                            # Declarative Render cloud infrastructure manifest
│                                                          # - service name: vyzorix-update-server
│ 
│                                                          # - Persistent disk at /data/ prevents SQLite DB reset on redeploy
│                                                          # - healthCheckPath: /health
│                                                          # - autoDeploy: true
│                                                          # NOTE: Render hobby plan sleeps after 15-30min inactivity;
│                                                          # mitigated by UptimeRobot pinging /health every 10min
├── .env.example                                           # Environment variable template
│                                                          # - PORT=3000
│                                                          # - NODE_ENV=production
│                                                          # - DATABASE_URL=/data/vyzorix.db
│                                                          # - FIREBASE_CREDENTIALS=<raw JSON string of service account>
│                                                          # - TOKEN_SECRET=<random 32+ char secret for API validation>
│                                                          # - JWT_SECRET=<separate random 32+ char secret for JWT signing>
│                                                          #   [— must be distinct from TOKEN_SECRET per JWT
│                                                          #   best practice of separate signing vs validation keys]
└── .gitignore                                             # Excludes: binaries, node_modules, local .db files, .env,
                                                           # SSL certs, frontend/dist/, bin/*.apk (managed by CI only)
│
├── config/
│   └── config.go                                          # Parses env vars into strictly typed Config struct
│                                                          # type Config struct {
│                                                          #   Port            string  // e.g. "3000"
│                                                          #   Env             string  // "production"|"development"
│                                                          #   DatabaseURL     string  // e.g. "/data/vyzorix.db"
│                                                          #   FirebaseCreds   string  // raw service-account.json string
│                                                          #   TokenSecret     string  // dashboard API validation key
│                                                          #   JWTSecret       string  // explicit JWT signing
│                                                          #                           // key for middleware/auth.go login
│                                                          #                           // flow; separate from TokenSecret
│                                                          # }
│
├── storage/
│   ├── sqlite.go                                          # SQLite3 connection pool with low-RAM optimizations
│                                                          # - PRAGMA journal_mode=WAL (concurrent reads + writes)
│                                                          # - PRAGMA cache_size=-2000 (2MB cache footprint cap)
│                                                          # - PRAGMA busy_timeout=5000 (prevents transaction deadlocks)
│   └── migrations.go                                      # Schema migrator; executes DDL on start to verify tables exist
│                                                          # Tables:
│                                                          # - devices: id, fcm_token, android_version, is_online,
│                                                          #   last_seen, command_secret [NEW COLUMN — per-device HMAC
│                                                          #   signing secret generated on registration; stored server-side
│                                                          #   for all subsequent command signing operations]
│                                                          # - device_logs: diagnostic traces, crash signatures,
│                                                          #   blacklisted package matches
│                                                          # - update_history: past OTA download transactions
│
├── models/
│   ├── device.go                                          # Maps registered client attributes
│                                                          # type Device struct {
│                                                          #   ID             string    `json:"id" db:"id"`
│                                                          #   FcmToken       string    `json:"fcmToken" db:"fcm_token"`
│                                                         
│                                                          #   AndroidVersion string    `json:"androidVersion"`
│                                                          #   IsOnline       bool      `json:"isOnline"`
│                                                          #   LastSeen       time.Time `json:"lastSeen"`
│                                                          #   CommandSecret  string    `json:"-" db:"command_secret"`
│                                                          #  field  json:"-" prevents secret leaking in API
│                                                          #   responses; only used internally by command_signer.go]
│                                                          # }
│   ├── telemetry.go                                       # Models high-frequency inbound telemetry frames from daemon
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
│   ├── command.go                                         # Models outgoing C2 command payloads to device daemon
│                                                          # type CommandFrame struct {
│                                                          #   TransactionID string    `json:"transactionId"`
│                                                          #   DeviceID      string    `json:"deviceId"`
│                                                          #   Action        string    `json:"action"`
│                                                          #   Timestamp     time.Time `json:"timestamp"`
│                                                          #   Params        string    `json:"params"`
│                                                          #   Nonce         string    `json:"nonce"`
│                                                          #    cryptographically random 16-byte hex per
│                                                          #   command; generated by command_signer.go; prevents replay
│                                                          #   HMAC          string    `json:"hmac"`
│                                                          #   HMAC-SHA256 of canonical string:
│                                                          #   transactionId|deviceId|action|timestampMs|nonce|params;
│                                                          #   computed using devices.command_secret from SQLite
│                                                          # }
│   └── response.go                                        # Standardized envelope structs for all Gin REST API responses;
│                                                          # ensures consistent schemas across all endpoints
│
├── hub/
│   ├── hub.go                                             # Full-duplex WebSocket broker; tracks active device connections
│                                                          # Hub goroutine manages three channels:
│                                                          # - register: newly connected device clients
│                                                          # - unregister: disconnected clients; updates DB is_online
│                                                          # - broadcast: routes signed command packets to target devices
│                                                          # - sync.RWMutex on connection map prevents concurrent write panics
│   └── client.go                                          # Wraps gorilla *websocket.Conn; spawns two goroutines per conn
│                                                          # - readPump: deserializes inbound telemetry; updates SQLite
│                                                          # - writePump: dispatches signed CommandFrame from hub channel
│                                                          #   to physical TCP socket
│                                                          # - Ping/pong every 15s to prevent carrier NAT timeout drops
│
├── controllers/
│   ├── updater.go                                         # OTA distribution; serves version.json + APK binaries; Gin
│                                                          # file-serving with Range header support for resumable downloads
│   ├── device.go                                          # Device registration handler
│                                                          # POST /v1/device/register:
│                                                          # 1. Validates request payload
│                                                          # 2. Generates command_secret = crypto/rand 32 bytes → hex
│                                                          # 3. Stores device + command_secret in devices table
│                                                          # 4. Returns { "deviceId": uuid, "commandSecret": "..." }
│                                                          #    over HTTPS — only time secret is ever transmitted
│                                                          # Also handles: device state sync, manual offline unregister
│   ├── command.go                                         # C2 command dispatch handler
│                                                          # POST /v1/command (auth-gated):
│                                                          # 1. Parse + validate CommandFrame from dashboard
│                                                          # 2. Fetch device.command_secret from SQLite
│                                                          # 3. Call services/command_signer.SignCommand() to attach
│                                                          #    nonce + hmac to frame
│                                                          # 4. Check device online:
│                                                          #    YES → hub.ActiveHub.Send() → WebSocket to device
│                                                          #    NO  → services/fcm.SendSilentPush() → FCM wake signal
│   └── websocket_handler.go                               # HTTP → WebSocket upgrade entry point; Gin route handler
│                                                          # that performs protocol upgrade via gorilla/websocket Upgrader;
│                                                          # hub.go manages connections post-upgrade but this handler
│                                                          # is the actual Gin route that initiates the upgrade;
│                                                          # without this no WebSocket connections can be established
│                                                          # despite hub.go existing
│
├── services/
│   ├── fcm/
│   │   ├── fcm.go                                         # Initializes Firebase Admin SDK; reads config.FirebaseCreds;
│   │   │                                                  # registers global *messaging.Client
│   │   └── notifier.go                                    # Formulates and dispatches silent high-priority FCM pushes
│   │                                                      # Push payload (command action present):
│   │                                                      # messaging.Message{
│   │                                                      #   Token: targetToken,
│   │                                                      #   Android: &AndroidConfig{ Priority: "high" },
│   │                                                      #   Data: map[string]string{
│   │                                                      #     "action":        "FORCE_SPEAKER",
│   │                                                      #     "transactionId": "...",
│   │                                                      #     "timestamp":     "1748260800000",
│   │                                                      #     "nonce":         "a3f8c1d2e4b56789",
│   │                                                      #     "hmac":          "9f3a1bc2...",
│   │                                                      #   },
│   │                                                      # }
│   │                                                      # FCM command payloads carry same nonce+hmac fields as WS
│   │                                                      # frames; signed by command_signer before dispatch
│   └── command_signer.go                                  # [NEW] Per-command HMAC-SHA256 signing service
│                                                          # SignCommand(frame *CommandFrame, secret string):
│                                                          # 1. nonce = crypto/rand 16 bytes → hex string
│                                                          # 2. canonical = transactionId|deviceId|action|
│                                                          #                timestampMs|nonce|params
│                                                          #    (timestampMs = Unix milliseconds as int64 string —
│                                                          #     never ISO8601 to avoid timezone ambiguity with Kotlin)
│                                                          # 3. mac = hmac.New(sha256.New, []byte(secret))
│                                                          # 4. mac.Write([]byte(canonical))
│                                                          # 5. returns nonce, hex.EncodeToString(mac.Sum(nil))
│                                                          # Called by controllers/command.go before every dispatch
│                                                          # (WebSocket and FCM paths both go through here)
│
├── middleware/
│   ├── auth.go                                            # Request authorizer; validates Authorization Bearer JWT
│   │                                                      # against JWT_SECRET on private C2 endpoints and WebSocket
│   │                                                      # handshakes; prevents unauthorized dashboard or device access
│   ├── cors.go                                            # Explicit CORS middleware for Gin; configures allowed
│   │                                                      # origins: android-app://com.vyzorix.audiorouter and React
│   │                                                      # dashboard domain; Gin has no default CORS handling unlike
│   │                                                      # the Node/Express version; without this all cross-origin
│   │                                                      # requests from device and dashboard are blocked at browser/OS
│   ├── rate_limiter.go                                    # Token-bucket rate limiter on public update endpoints
│   │                                                      # (/api/v1/version, /bin/*); blocks flood from faulty clients
│   └── logger.go                                          # Structured JSON console logger; method, path, elapsed ms,
│                                                          # response status per request
│
├── bin/                                                   # APK binary storage; populated exclusively by CI/CD
│   ├── audiorouter-v2.0.0.apk                             # Release APKs committed by push_update_bin.yml workflow
│   ├── audiorouter-v2.1.0.apk                             # Never manually edited
│   └── audiorouter-v2.2.0.apk
│
├── api/
│   └── v1/
│       ├── version.json                                   # Current version metadata at GET /api/v1/version;
│       │                                                  # updated by push_update_bin.yml on every tagged release;
│       │                                                  # fields: version, versionCode, buildNumber, minSdkVersion,
│       │                                                  # releaseDate, downloadUrl, checksumSha256, fileSize,
│       │                                                  # releaseNotes, forced, changelog
│       └── changelog.json                                 # Historical changelog at GET /api/v1/changelog;
│                                                          # updated by push_update_bin.yml; prepend structure preserves
│                                                          # full version history
│
├── db/
│   └── .gitkeep                                           # [FIXED: was vyzorix.db — runtime-generated file must not
│                                                          # be committed; .gitkeep ensures directory exists in git;
│                                                          # actual .db file lives on Render persistent disk at /data/]
│
├── public/
│   ├── index.html                                         # Server landing page; shows service status, version, health link
│   ├── style.css                                          # Minimal styling for landing page
│   ├── health.json                                        # Static health check fallback file
│   ├── favicon.ico                                        # Browser favicon; Vite warns on build without it
│   └── manifest.json                                      # Web app manifest; suppresses Vite missing-manifest warnings;
│                                                          # declares app name, icons, theme color for dashboard PWA
│
├── scripts/
│   ├── generate_version.sh                                # Reads APK binary; computes SHA-256; auto-generates
│   │                                                      # api/v1/version.json with all required fields
│   ├── compute_checksum.sh                                # Computes SHA-256 for a given APK; outputs raw hash string
│   ├── validate_apk.sh                                    # Validates APK integrity before CI push; checks file type,
│   │                                                      # minimum size, and signature presence
│   └── cleanup_old_apks.sh                                # Prunes stale APK binaries from bin/; retains only
│                                                          # N most recent releases (default 3, configurable);
│                                                          # prevents unbounded APK accumulation in bin/;
│                                                          # called by push_update_bin.yml after new APK is committed
│
├── .github/
│   └── workflows/
│       └── deploy.yml                                     # Auto-deploy to Render on push to main
│                                                          # Steps:
│                                                          # 1. Validate APK files via scripts/validate_apk.sh
│                                                          # 2. Trigger Render deploy via API
│                                                          # 3. Wait 30s then GET /health to confirm deployment
│
└── frontend/                                              # Vite + React + TypeScript + Tailwind CSS C2 dashboard SPA
    ├── package.json                                       # Frontend deps and build commands
    │                                                      # Key: react, react-dom, react-router-dom, axios, recharts,
    │                                                      # tailwindcss, typescript, vite
    ├── tsconfig.json                                      # Strict TypeScript compilation rules
    ├── vite.config.ts                                     # Asset bundling, path aliases, proxy to Go backend in dev
    ├── tailwind.config.js                                 # Tailwind theme extensions and custom breakpoints
    ├── postcss.config.js                                  # CSS postprocessor for Tailwind
    ├── index.html                                         # Root HTML; React DOM mount point at #root
    │
    └── src/
        ├── main.tsx                                       # React DOM init; imports global CSS; mounts App into #root
        ├── index.css                                      # Tailwind base, components, utilities; custom scrollbar classes
        ├── App.tsx                                        # Master layout; React Router routes; wraps WebSocket/Theme/Auth
        │
        ├── context/
        │   ├── WebSocketContext.tsx                       # Keeps persistent live C2 WebSocket connection alive across
        │   │                                              # all page navigations; exposes sendMessage() and lastMessage
        │   ├── ThemeContext.tsx                           # Global dark/light mode context; persists to localStorage
        │   └── AuthContext.tsx                            # Validates admin session states; wraps protected routes;
        │                                                  # exposes login/logout/isAuthenticated
        │
        ├── models/
        │   ├── device.interface.ts                        # TypeScript interface mapping models/device.go Device struct
        │   ├── telemetry.interface.ts                     # TypeScript interface mapping models/telemetry.go TelemetryFrame
        │   ├── command.interface.ts                       # TypeScript interface mapping models/command.go CommandFrame
        │   │                                              # Includes nonce and hmac fields for display in command history
        │   │                                              # Note: hmac displayed truncated for readability in dashboard
        │   └── user.interface.ts                          # TypeScript interface for admin auth models
        │
        ├── hooks/
        │   ├── useWebSocket.ts                            # Automatic reconnection, ping cycles, message routing;
        │   │                                              # consumes WebSocketContext
        │   ├── useTelemetry.ts                            # Pools and organizes live chart data from telemetry frames
        │   ├── useDevices.ts                              # REST API calls for device fleet state retrieval
        │   └── useAuth.ts                                 # [STUB REQUIRED] Custom auth hook consumed by AuthContext.tsx
        │                                                  # and authService.ts; must exist even as minimal stub or
        │                                                  # frontend will not compile; handles login state, JWT token
        │                                                  # storage in memory, session validation
        │
        ├── services/
        │   ├── api.ts                                     # Axios client; base URL, request/response interceptors,
        │   │                                              # auth header injection for all backend REST calls
        │   └── authService.ts                             # Calls backend /login /logout; stores and clears JWT tokens;
        │                                                  # consumed by useAuth.ts and AuthContext.tsx
        │
        ├── utils/
        │   ├── formatters.ts                              # Timestamps (ISO8601 → readable), uptimes (s → HH:MM:SS),
        │   │                                              # memory (bytes → MB/GB), HMAC (full hex → truncated display)
        │   ├── validators.ts                              # Validates hex inputs, APK version strings, command params
        │   └── cn.ts                                      # Tailwind className merger (clsx + twMerge); used by UI
        │                                                  # component library for conditional class composition;
        │                                                  # ensure actually imported or remove to avoid dead file
        │
        ├── pages/
        │   ├── LoginPage.tsx                              # [STUB REQUIRED] Admin login page; consumed by App.tsx router;
        │   │                                              # must exist even as minimal stub or frontend will not compile;
        │   │                                              # renders login form calling authService.login()
        │   ├── DashboardPage.tsx                          # Master dashboard; summary metrics, quick controls, alerts
        │   ├── DevicesPage.tsx                            # Device fleet grid; searchable, filterable, paginated
        │   ├── DiagnosticsPage.tsx                        # Live C2 console; terminal log stream, live telemetry graphs
        │   ├── UpdatesPage.tsx                            # [NEW] OTA update management page; displays current
        │   │                                              # version.json state per device; provides UI to trigger
        │   │                                              # WAKE_UP_UPDATER remote command; shows update history;
        │   │                                              # referenced in FEATURES.md §3.1 but had no UI surface
        │   ├── SettingsPage.tsx                           # Config: cooldown thresholds, check intervals, rate limits,
        │   │                                              # server endpoint overrides
        │   └── NotFoundPage.tsx                           # Fallback 404 page for unmatched routes
        │
        └── components/
            ├── layout/
            │   ├── Sidebar.tsx                            # Navigation panel; links to all pages; shows WebSocket
            │   │                                          # connection status indicator badge
            │   ├── Navbar.tsx                             # Top nav; server status, alert count, and live WebSocket
            │   │                                          # connection state indicator (connected/reconnecting/
            │   │                                          # disconnected) driven by WebSocketContext state;
            │   │                                          # required — without it users have zero feedback when WS drops
            │   └── Footer.tsx                             # Copyright, API version, build target info
            │
            ├── ui/
            │   ├── Button.tsx                             # Tailwind button variants: Primary, Secondary, Danger, Icon
            │   ├── Card.tsx                               # Content cards with elevation, borders, margins
            │   ├── Badge.tsx                              # Status badges: Green/OK, Yellow/Warn, Red/Critical, Gray/Unknown
            │   ├── Modal.tsx                              # Animating overlay dialog with backdrop
            │   ├── Table.tsx                              # Responsive tabular fleet lists with sort + pagination
            │   ├── Spinner.tsx                            # Loading animation for async fetches and transitions
            │   └── Tooltip.tsx                            # Accessible info overlays on hover
            │
            ├── dashboard/
            │   ├── DeviceGrid.tsx                         # Active device cards with live status from WebSocket telemetry
            │   ├── MetricsSummary.tsx                     # Fleet stats: total devices, online count, avg risk score,
            │   │                                          # avg CPU/RAM pressure
            │   └── SystemAlerts.tsx                       # Live alert feed: critical exceptions, reboot alerts,
            │                                              # route loss events, HMAC rejection alerts across all devices
            │
            ├── device/
            │   ├── DeviceDetailModal.tsx                  # Full device overlay: all telemetry, log history, command history
            │   ├── DeviceControlPanel.tsx                 # Remote command buttons: HAL Reset, Force Speaker, Toggle
            │   │                                          # Capture, Reinit Projection, Dump Flight Data, Wake Updater
            │   ├── DeviceLogTerminal.tsx                  # Scrollable console; live log stream via WebSocket
            │   ├── RouteStateCard.tsx                     # Routing state: speaker forced/headset lock/drifting;
            │   │                                          # last correction timestamp
            │   ├── ThermalMetricsCard.tsx                 # SoC temperature readings, throttle status, sample rate
            │   │                                          # reduction state
            │   └── UpdateStateCard.tsx                    #  OTA update state per device (NOT_CHECKED / AVAILABLE
            │                                              # / DOWNLOADING / DOWNLOADED / INSTALLING / SUCCESS / FAILED);
            │                                              # last check timestamp; available version if any; download
            │                                              # progress; feeds from TelemetryFrame update fields
            │
            └── charts/
                ├── LiveCPUChart.tsx                       # Real-time CPU load; windowed to last 60 data points
                ├── MemoryFootprintChart.tsx               # JVM cache budgets, GC pause events, native heap over time
                ├── RiskScoreChart.tsx                     # RecoveryCoordinator risk score history (from absorbed SoftRebootPredictor policy); threshold lines
                │                                          # at 50 (warn) and 75 (critical)
                ├── BufferHealthChart.tsx                  # Real-time audio capture buffer fill level (0-100%);
                │                                          # plots underrun events; critical for diagnosing capture
                │                                          # pipeline starvation on Nokia C22; data from
                │                                          # TelemetryFrame.bufferLevel
                └── ThermalChart.tsx                       # Real-time SoC temperature from TelemetryFrame.thermalTemp;
                                                           # threshold lines matching ThermalMitigationPolicy levels;
                                                           # more operationally relevant for Nokia C22 than CPU chart alone
```

---

