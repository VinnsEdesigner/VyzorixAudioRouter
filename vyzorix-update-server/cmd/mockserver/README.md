# mockserver

Thin Go binary that implements just enough of the `vyzorix-update-server` contract for the VyzorixAudioRouter device to exercise Layers 7 and 8 end-to-end during Phase 1. Per [ADR-0009](../../../doc/adr/0009-phase-1-mock-first.md), this is a Phase 1 deliverable. It does NOT replace the real server in Phase 1.5 — the real server takes over with no Android code changes.

The wire protocol (CommandFrame schema, HMAC scheme, REST surface, WSS handshake) is **canonical** — exactly what the device will exercise against the real server. The in-memory implementation details (nonce cache, secret store, persistence) are **mock-grade simplifications** documented per section below.

## Run

```bash
go run ./cmd/mockserver \
    -addr=:8080 \
    -data=./cmd/mockserver/testdata \
    -log-level=info
```

Flags:
- `-addr` (default `:8080`) — listen address.
- `-data` (default `./cmd/mockserver/testdata`) — directory holding `version.json` and the dummy APK.
- `-log-level` (default `info`) — `debug` / `info` / `warn` / `error`.
- `-mock-secret` (default `0000…0000`, 64 hex chars) — the `command_secret` stored on every device record at registration. **Mock-grade simplification**: the real server generates a fresh 32-byte secret per device at registration time (see `doc/DEVICE_REGISTRATION.md` §6). Sharing one secret across all devices makes the CI bypass mode in `doc/CI_CD_WORKFLOWS.md` trivial.
- `-fleet-token` (default `mock-fleet-registration-token`) — the shared `fleet_registration_token` enforced on `POST /v1/device/register`. The device APK bakes this in at build time per `doc/DEVICE_REGISTRATION.md` §3.1.
- `-dashboard-token` (default empty = open) — Bearer token enforced on dashboard endpoints (`GET /v1/device/{id}/status`, `POST /v1/device/{id}/command`, dashboard-initiated `DELETE /v1/device/{id}`). The real server uses a session cookie (`doc/DEVICE_REGISTRATION.md` §3.3); the mock uses Bearer so the contract can be exercised from `curl` without a cookie jar. Leaving this empty makes those endpoints open — convenient for local dev, **explicitly mock-grade**.

State is purely in-memory. Restarting the binary forgets every device. This is intentional — the mock is not a database substitute.

## Endpoints

### Layer 7 (OTA update — per `BUILD_ORDER.md` Layer 7 + `UPDATE_MECHANISM.md`)

| Method | Path | Notes |
|--------|------|-------|
| `GET`  | `/api/v1/version`              | Serves `testdata/version.json` verbatim. |
| `HEAD` | `/api/v1/apk/{filename}`       | Returns `Content-Length` only (used by the device for pre-download size check). |
| `GET`  | `/api/v1/apk/{filename}`       | Serves the file from `testdata/`. Supports `Range` for resumable downloads. |

`version.json` schema (kept in lockstep with `UPDATE_MECHANISM.md`):

```json
{
  "version": "1.0.0",
  "version_code": 1,
  "apk_filename": "vyzorix-audiorouter-1.0.0.apk",
  "apk_sha256": "0000...",
  "apk_size_bytes": 0,
  "release_notes": "Mock release. Do not deploy to a real device."
}
```

### Layer 8 (C2 stack — per `DEVICE_REGISTRATION.md` + `COMMAND_SECURITY.md`)

| Method  | Path                                | Auth                                   | Reference |
|---------|-------------------------------------|----------------------------------------|-----------|
| `POST`  | `/v1/device/register`               | Bearer `fleet_registration_token`      | DR §3.1   |
| `PATCH` | `/v1/device/{id}/fcm-token`         | REST body-signed HMAC (scheme #2)      | DR §3.2   |
| `GET`   | `/v1/device/{id}/status`            | Dashboard token (mocked Bearer)        | DR §3.3   |
| `DELETE`| `/v1/device/{id}`                   | Dashboard token OR REST HMAC           | DR §3.4   |
| `POST`  | `/v1/device/{id}/command`           | Dashboard token; server signs CommandFrame | DR §5 |
| `WSS`   | `/v1/device/{id}/stream`            | CONNECT-style HMAC handshake (scheme #3)| DR §4.1  |
| `GET`   | `/healthz`                          | none                                   | UptimeRobot keepalive |

Returns `201 Created` from `POST /v1/device/register` (matches `DEVICE_REGISTRATION.md` §3.1), `200 OK` from `PATCH /fcm-token`, `204 No Content` from `DELETE`, `202 Accepted` from `POST /command`.

## HMAC scheme — canonical, exactly as `doc/COMMAND_SECURITY.md`

Three distinct canonical-message schemes coexist on the wire. They are **NOT interchangeable**; using the wrong one is a signature failure. Each is enforced by `cmd/mockserver/hmac.go`.

### Scheme 1 — `CommandFrame` (server → device, over WSS or FCM)

Canonical message (no whitespace, no trailing newline):

```
{transactionId}|{deviceId}|{action}|{timestampMs}|{nonce}|{params}
```

- `timestampMs` is **Unix milliseconds (int64)**. ISO8601 is NOT permitted in the canonical string — it would introduce timezone/format ambiguity between Go and Kotlin.
- `params` is the raw JSON object as it appears in the frame, byte-for-byte. Empty params = literal `{}`.
- Output: `hex(HMAC-SHA256(canonical, command_secret))` — 64 lowercase hex characters.
- Embedded in the frame as the `hmac` field. The full frame shape is documented in `doc/COMMAND_SECURITY.md` §2.

Worked example (pinned by `TestCommandFrame_CanonicalMessageFormat`):

```
f7893a2-bcd0-4e12|uuid-nokia-c22-092831|REINIT_PROJECTION|1748260800000|a3f8c1d2e4b56789|{}
```

### Scheme 2 — REST body-signed (device → server admin endpoints)

Used by `PATCH /v1/device/{id}/fcm-token` and device-initiated `DELETE /v1/device/{id}`.

- Canonical = raw request body bytes (empty for `DELETE`).
- Output: `hex(HMAC-SHA256(canonical, command_secret))`.
- Carrier: four headers per `doc/DEVICE_REGISTRATION.md` §3.2:
  - `X-Vyzorix-Hmac:        <hex>`
  - `X-Vyzorix-Device-Id:   <deviceId>`
  - `X-Vyzorix-Timestamp:   <unix milliseconds>`
  - `X-Vyzorix-Nonce:       <UUID v4 or 16-byte hex>`
- The path's `{id}` must equal `X-Vyzorix-Device-Id`. Mismatch returns `401 device_id_mismatch`.

### Scheme 3 — WSS handshake (device → server on upgrade)

Used by `GET /v1/device/{id}/stream` (WebSocket upgrade).

```
canonical = "CONNECT:{deviceId}:{timestampMs}:{nonce}"
```

Same four headers as scheme #2. Output is hex. Per `doc/DEVICE_REGISTRATION.md` §4.1.

### Bearer-token bootstrap

`POST /v1/device/register` is NOT HMAC — it uses `Authorization: Bearer <fleet_registration_token>` (DR §3.1). The fleet token is shared across the fleet and baked into the APK at build time; the per-device `command_secret` it bootstraps is the strong auth used by all post-registration endpoints.

### Replay protection

| Property | Value | Source |
|----------|-------|--------|
| Timestamp window | **±30 seconds** | `doc/COMMAND_SECURITY.md` §3 |
| Nonce cache TTL  | **5 minutes** (2.5× safety margin over the window) | `doc/COMMAND_SECURITY.md` §4 |
| Error codes      | `expired_timestamp`, `replayed_nonce`, `invalid_hmac`, `device_id_mismatch` | `doc/DEVICE_REGISTRATION.md` §3.2 |

Rejection on bad HMAC is **always strict**. There is no "permissive mode" — making the mock loosely enforce its own protocol was exactly the kind of shortcut that caused the original alignment drift.

## What this mock deliberately does NOT do (mock-grade simplifications)

These are simplifications relative to the real server, scoped so the canonical protocol still gets exercised end-to-end. Each one has a documented production design in `doc/`.

### Nonce cache — highly simplified for mock testing

The mock's nonce cache is a flat `map[string]time.Time` with opportunistic GC on each insert. It is **NOT** the canonical implementation documented in `doc/COMMAND_SECURITY.md` §4. Specifically:

- **No LRU eviction**: the canonical design is a thread-safe `LinkedHashMap` with a 200-entry cap; the mock has no cap (relies on the TTL sweep to bound size).
- **No distributed dedup**: the real server's nonce cache will eventually need to be shared across replicas (Redis-backed or similar). The mock is single-process, single-replica.
- **No persistence**: restarts wipe the cache. The real server's design has the same property *intentionally* (the timestamp window is the durable replay defence), but it's noted here so nobody mistakes the mock for production storage.
- **Per-server, not per-device**: the mock dedups nonces globally; the canonical design dedups per-device (collisions across devices are not replays).

If you are reading this because nonce-replay protection misbehaved during integration testing: that's the implementation, not the spec. The spec is in `doc/COMMAND_SECURITY.md` §4.

### Secret store — single shared `-mock-secret`

The real server generates a fresh 32-byte `command_secret` per device at registration time and stores it in a server-side secret store (`doc/DEVICE_REGISTRATION.md` §6.1: filesystem `data/secrets/<deviceId>.bin` with AES-GCM at rest, or KMS-backed in production). The mock stores a single shared secret (`-mock-secret`) on every device record. This is intentional for CI bypass and trivial repro, but means the mock will not catch bugs caused by per-device secret isolation breaking.

### No persistence

No SQLite, no on-disk secret store, no log directory. Restart = blank slate. The real server persists the `devices` table and the secret store (`doc/DEVICE_REGISTRATION.md` §6).

### No multi-device isolation in the secret material

All devices share the same `-mock-secret`, so a stolen device's compromise affects every device. Not a problem for a single-device personal deployment (the threat model in `doc/COMMAND_SECURITY.md` §1 accepts this) but **must not** be carried into the real server.

### No TLS

Listens HTTP only. The Android client treats `localhost` / `127.0.0.1` as a development override per `UPDATE_SERVER.md`.

### No dashboard endpoints

`/v1/dashboard/*` is Phase 2 (`doc/UPDATE_SERVER_ARCHITECTURE_SPEC.md`). The mock's `-dashboard-token` is a placeholder for that future cookie auth.

### No key rotation

Not part of Phase 1 (future ADR, see `doc/adr/0001-c2-stack-rationale.md`).

## Why a separate binary

Co-locating the mock with the real server source (when it eventually lands) lets us share helper packages (`internal/contract`, `internal/proto`) once the real server materializes in Phase 1.5. Until then, the mock is fully self-contained inside this `cmd/mockserver/` directory — every file you need is here.
