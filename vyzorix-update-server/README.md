# vyzorix-update-server — Go backend (source of truth: `VyzorixAudioRouter/vyzorix-update-server/`)

This tree contains the **server side** of the Vyzorix project. It is **co-located in both repositories** by an automated sync:

- **Source of truth:** [`VinnsEdesigner/VyzorixAudioRouter`](https://github.com/VinnsEdesigner/VyzorixAudioRouter) at `vyzorix-update-server/` — editing happens here so the contract stays in lockstep with the Android consumers in `core/services/security/`, `core/services/c2/`, and `core/services/update/`.
- **Publication target:** [`VinnsEdesigner/vyzorix-update-server`](https://github.com/VinnsEdesigner/vyzorix-update-server) at the repo **root** — what Render builds and what UptimeRobot will keep warm. Pushes to this side are made automatically by `.github/workflows/sync_server.yml` and should not be edited directly.

The publication target also hosts the **Vyzorix dashboard** (React / TanStack Start) under `src/` and the related TS configuration files. The sync workflow explicitly excludes those paths (`src/`, `package.json`, `bun.lock`, `bunfig.toml`, `components.json`, `eslint.config.js`, `tsconfig.json`, `vite.config.ts`, `.lovable/`, `.prettierrc`, `.prettierignore`, `doc/`, plus the standard `.git/`) so the dashboard is never clobbered. The `doc/` directory is synced separately by `.github/workflows/sync_repo.yml`.

## Layout

```
vyzorix-update-server/
├── README.md               # this file
├── go.mod, go.sum
└── cmd/
    └── mockserver/         # Phase 1 mock server — see cmd/mockserver/README.md
        ├── main.go
        ├── server.go
        ├── update.go       # GET/HEAD /api/v1/version, /api/v1/apk/:file
        ├── device.go       # POST /v1/device/register, PATCH fcm-token, GET status, DELETE
        ├── command.go      # POST /v1/device/:id/command
        ├── ws.go           # WSS /v1/device/:id/stream
        ├── store.go        # in-memory device + nonce store
        ├── hmac.go         # HMAC-SHA256 per COMMAND_SECURITY.md
        ├── testdata/       # version.json + dummy APK
        ├── hmac_test.go
        ├── server_test.go
        └── README.md
```

## Running locally

```bash
go run ./cmd/mockserver -addr :8080 -data ./cmd/mockserver/testdata
# server listening on :8080
# POST /v1/device/register / GET /api/v1/version / WSS /v1/device/:id/stream
go test ./...
```

See `cmd/mockserver/README.md` for the full endpoint catalogue and curl examples.

## Phase context

Per [ADR-0009 (Phase 1 mock-first)](https://github.com/VinnsEdesigner/VyzorixAudioRouter/blob/main/doc/adr/0009-phase-1-mock-first.md):

- **Phase 1** — the device runs Layers 0–8 against `cmd/mockserver`. Acceptance: 7 days continuous on the Nokia C22 against the mock. (current state)
- **Phase 1.5** — `cmd/server` (or similar) replaces the mock with the real server: SQLite-backed device store, persistent secret store, REST + WSS, deployable to Render. **No Android code changes** — only the `updateServerUrl` build config flips.
- **Phase 2** — the dashboard (which already lives in the publication target's `src/`) wires up to the real server's `/v1/dashboard/*` endpoints.
- **Phase 3** — hardening: key rotation, multi-device, audit logging, secret-store migration to KMS.

## What this tree deliberately does NOT contain

- The real production server — that arrives in Phase 1.5. See [`UPDATE_SERVER_ARCHITECTURE_SPEC.md`](https://github.com/VinnsEdesigner/VyzorixAudioRouter/blob/main/doc/UPDATE_SERVER_ARCHITECTURE_SPEC.md) for the target layout.
- The dashboard — that's a sibling tree in the publication target, owned by Lovable / direct edits in the dashboard repo.
- Persistent storage — the mock is in-memory by design.
