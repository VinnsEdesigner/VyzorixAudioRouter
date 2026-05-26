# UPDATE_SERVER.md — Vyzorix C2 & Update Server Spec (Go & React Production Edition)

## Objective

This document defines the production-grade repository structure and configuration specifications for the **Vyzorix Command & Control (C2) and Update Server**. 

To handle thousands of high-frequency, bidirectional WebSocket telemetry streams within cheap, highly constrained cloud hosting environments (such as Render’s 512MB free tier), **Go (Golang)** is selected as the primary backend language. 

The frontend is built as a highly modular, professional **Vite + React + TypeScript + Tailwind CSS** Single Page Application (SPA), delivering compilation-level safety, strict data typing, and real-time visualization of device fleets.

---

# Part 1. Backend Language Selection: Why Go (Golang)?

When operating a real-time C2 and telemetry server, Node.js (TypeScript) and Kotlin/Java (JVM) run into severe resource boundaries on limited infrastructure. We select **Go (Golang)** for the following engineering reasons:

### 1. Minimal Memory Footprint
* **The Reality:** A Node.js runtime requires a baseline of ~80MB–150MB of RAM, which swells under concurrent garbage collection. A JVM (Kotlin/Ktor) runtime starts at ~250MB–400MB, making it extremely prone to Out-Of-Memory (OOM) container kills on Render’s 512MB RAM containers.
* **The Go Advantage:** A compiled Go binary starts with a memory footprint of **less than 15MB**. It can handle thousands of persistent TCP connections with under 50MB of total heap allocation.

### 2. High-Frequency Concurrency (Goroutines)
* **The Reality:** Node.js runs on a single thread. Heavy JSON processing, log decryption, or SHA-256 hashing blocks the event loop, causing WebSocket ping timeouts and disconnection storms.
* **The Go Advantage:** Go schedules concurrent tasks using **Goroutines**—lightweight threads managed by the Go runtime that require only 2KB of memory stack space per socket. This allows us to handle high-frequency telemetry parsing and database writes concurrently without blocking the main signaling loops.

### 3. Native Low-Overhead WebSockets
* Using Go's highly optimized `gorilla/websocket` or `melody` engines, full-duplex TCP routing is achieved with sub-millisecond processing latencies.

---

# Part 2. Complete Production-Grade Update Server Tree

```text
vyzorix-update-server/
├── go.mod                                 # Binds Go dependencies (Gin, Gorilla/Websocket, Firebase-Admin, SQLite)
├── go.sum                                 # Cryptographic checksums of backend dependencies
├── main.go                                # Entry point initializing the database, router, WebSocket hub, and FCM client
├── Dockerfile                             # Multi-stage Dockerfile (compiles Go static binary, copies React build, prunes resources)
├── render.yaml                            # Declarative Render infrastructure manifest (enforces SQLite volumes and configs)
├── .env.example                           # Environmental variable template for secret keys and ports
├── .gitignore                             # Excludes compiled binaries, node_modules, local sqlite files, and SSL certs
│
├── config/                                # Central configuration loader
│   └── config.go                          # Parses environmental variables (port, firebase private keys, paths)
│
├── storage/                               # Persistent database layer (SQLite3)
│   ├── sqlite.go                          # Configures the SQLite3 connection pool, WAL mode, and busy-timeouts
│   └── migrations.go                      # Binds the SQL schema migrations to create devices, logs, and state tables
│
├── models/                                # Strict, type-safe structures mapping database and network payloads
│   ├── device.go                          # Models device registry details (UUID, FCM token, Android version, active status)
│   ├── telemetry.go                       # Models high-frequency inbound telemetry frames (risk score, battery, route)
│   ├── command.go                         # Enum models for C2 action payloads (FORCE_SPEAKER, RESET_HAL, etc.)
│   └── response.go                        # Standardized envelope structures for REST API responses
│
├── hub/                                   # WebSocket Broker and Connection Registry
│   ├── hub.go                             # Tracks active device connections, manages broadcast channels, and heartbeats
│   └── client.go                          # Wraps physical net.Conn sockets, manages read/write loop goroutines
│
├── controllers/                           # Type-safe request and response handlers
│   ├── updater.go                         # Serves static JSON version updates and handles range-support APK downloads
│   ├── device.go                          # Handles device registrations, state syncs, and manual offline unregistries
│   └── command.go                         # Translates dashboard HTTP posts into WebSocket client frames
│
├── services/
│   └── fcm/                               # Firebase Messaging signaling services
│       ├── fcm.go                         # Initializes the Google firebase-admin Client using secure certificates
│       └── notifier.go                    # Formulates and dispatches silent high-priority push wakeups
│
├── middleware/                            # Express-equivalent request interceptors
│   ├── auth.go                            # Validates session tokens and API keys on C2 endpoints
│   ├── rate_limiter.go                    # Prevents DDOS on public endpoints using token-bucket algorithm
│   └── logger.go                          # High-performance structured console logging (JSON-based)
│
├── bin/                                   # File server host directory for signed release APKs
│   ├── audiorouter-v2.0.0.apk
│   └── audiorouter-v2.1.0.apk
│
├── db/                                    # Target folder for physical SQLite store
│   └── vyzorix.db                         # Encrypted master database file
│
└── frontend/                              # Vite + React + TypeScript + Tailwind CSS C2 Dashboard
    ├── package.json                       # Front-end package dependencies, React plugins, and build commands
    ├── tsconfig.json                      # Strict TypeScript compilation rules for React components
    ├── vite.config.ts                     # High-performance Vite build config (configures asset bundling)
    ├── tailwind.config.js                 # Tailwind CSS theme extension mappings and breakpoints
    ├── postcss.config.js                  # CSS postprocessor utilities configuration
    ├── index.html                         # Root HTML entrypoint hosting the React DOM hook
    │
    └── src/
        ├── main.tsx                       # React DOM initialization and global styling imports
        ├── index.css                      # Tailwind base, components, and custom scrollbar classes
        ├── App.tsx                        # Master layout coordinator binding routes and context providers
        │
        ├── context/                       # Shared State Providers
        │   ├── WebSocketContext.tsx       # Keeps the persistent live C2 WebSocket connection alive across pages
        │   ├── ThemeContext.tsx           # Global dark/light mode context
        │   └── AuthContext.tsx            # Validates admin session states and credentials
        │
        ├── models/                        # Strict front-end TypeScript interfaces
        │   ├── device.interface.ts        # Maps the device data model
        │   ├── telemetry.interface.ts     # Maps high-frequency incoming metric frames
        │   ├── command.interface.ts       # Maps transaction logs and remote command packets
        │   └── user.interface.ts          # Maps admin authentication models
        │
        ├── hooks/                         # Custom React Hooks
        │   ├── useWebSocket.ts            # Implements automatic reconnection, ping cycles, and message routing
        │   ├── useTelemetry.ts            # Pools and organizes live chart data arrays
        │   ├── useDevices.ts              # Connects API endpoints to retrieve fleet states
        │   └── useAuth.ts                 # Direct handler for logins and logout routines
        │
        ├── services/                      # API service clients
        │   ├── api.ts                     # Custom Axios client with base interceptors for backend REST endpoints
        │   └── authService.ts             # Calls login/logout endpoints
        │
        ├── utils/                         # Pure utility functions
        │   ├── formatters.ts              # Formats timestamps, uptimes, and memory allocations
        │   ├── validators.ts              # Validates manual hex inputs or range commands
        │   └── cn.ts                      # Tailwind class-name merging helper
        │
        ├── pages/                         # Main page view components
        │   ├── LoginPage.tsx              # Secure admin login portal
        │   ├── DashboardPage.tsx          # Master dashboard (displays summary metrics, quick controls, active counts)
        │   ├── DevicesPage.tsx            # Device Fleet Grid (allows searching, filtering, and paging through devices)
        │   ├── DiagnosticsPage.tsx        # Live C2 Console (terminal log stream, live telemetry graphs)
        │   ├── SettingsPage.tsx           # App configuration settings (cooldown thresholds, update triggers)
        │   └── NotFoundPage.tsx           # Fallback 404 page
        │
        └── components/                    # Reusable UI component modules
            ├── layout/
            │   ├── Sidebar.tsx            # Navigation panel with responsive drawer layouts
            │   ├── Navbar.tsx             # Top navigation panel displaying server status and active alerts
            │   └── Footer.tsx             # Copyright and client API build targets
            │
            ├── ui/                        # Low-level primitive design systems
            │   ├── Button.tsx             # Custom Tailwind button components (Primary, Secondary, Danger, Icon)
            │   ├── Card.tsx               # Content cards with consistent elevation, borders, and margins
            │   ├── Badge.tsx              # Monochrome badges representing states (Green/OK, Yellow/Warn, Red/XX)
            │   ├── Modal.tsx              # Smooth animating overlay dialog boundaries
            │   ├── Table.tsx              # Responsive tabular fleet lists
            │   ├── Spinner.tsx            # Loading animation transitions
            │   └── Tooltip.tsx            # Accessible info overlays on hover
            │
            ├── dashboard/
            │   ├── DeviceGrid.tsx         # List of active device cards with live layout mutations
            │   ├── MetricsSummary.tsx     # Fleet statistics summaries (CPU usage, RAM pressure, overall risk)
            │   └── SystemAlerts.tsx       # Live alert list compiling critical exceptions and reboot alerts
            │
            ├── device/
            │   ├── DeviceDetailModal.tsx  # In-depth overlay displaying a target device's details
            │   ├── DeviceControlPanel.tsx # Interactive remote command buttons (HAL Reset, Speaker Force)
            │   ├── DeviceLogTerminal.tsx  # Interactive mock console printing live log streams from the device
            │   ├── RouteStateCard.tsx     # Displays specific routing states (speaker, headset, drifts)
            │   └── ThermalMetricsCard.tsx # Displays SoC temperature sensors and cooling statuses
            │
            └── charts/
                ├── LiveCPUChart.tsx       # Canvas/SVG chart of live CPU loads
                ├── MemoryFootprintChart.tsx # Live graph plotting cache budgets and GC pauses
                └── RiskScoreChart.tsx     # Interactive chart plotting SoftRebootPredictor risks
```

This updated, exhaustive Go & React architecture blueprint ensures that **your backend consumes minimal hardware resources, supports thousands of concurrent full-duplex WebSocket connections safely, and exposes a high-density, strictly-typed fleet control dashboard**, completely protected against type-mismatch bugs during editor time and compilation. 

Let me know what we should focus on next! We can update your update server documentation to match this exact Go/React spec!