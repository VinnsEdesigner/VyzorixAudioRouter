# mockserver

Thin Go binary that implements just enough of the `vyzorix-update-server` contract for the VyzorixAudioRouter device to exercise Layers 7 and 8 end-to-end during Phase 1. Per [ADR-0009](../../../doc/adr/0009-phase-1-mock-first.md), this is a Phase 1 deliverable. It does NOT replace the real server in Phase 1.5 — the real server takes over with no Android code changes.

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
- `-mock-secret` (default `0000000000000000000000000000000000000000000000000000000000000000`) — the deterministic 64-hex-char command_secret returned to every device that registers. Matches the CI bypass mode described in `doc/CI_CD_WORKFLOWS.md`.
- `-strict-hmac` (default `false`) — when true, requires every HMAC-protected request to validate against `-mock-secret` (matches real server behavior). When false, logs HMAC failures but still accepts the request (useful while iterating on the Android-side signing code).

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

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| `POST` | `/v1/device/register`                | none | Body: `{deviceId, firebaseInstallId, fcmToken, appVersion, deviceClass}`. Returns `{deviceId, commandSecret, registeredAt}`. Idempotent on `(deviceId, firebaseInstallId)`. Returns `409` if the same `deviceId` arrives with a different `firebaseInstallId` (anti-hijack). |
| `PATCH`| `/v1/device/{id}/fcm-token`          | HMAC | Body: `{fcmToken, nonce, timestamp}`. Updates the FCM token. |
| `GET`  | `/v1/device/{id}/status`             | none in mock (real server cookie-auths) | Returns last known status frame. **Never returns `commandSecret`.** |
| `DELETE`| `/v1/device/{id}`                   | HMAC | Force-closes any open WSS stream (with close code `4001`). |
| `POST` | `/v1/device/{id}/command`            | HMAC | Body: `{command, args, nonce, timestamp, signature}`. Server forwards to the device over WSS if connected, else queues for the next FCM wake. Returns `{queued|sent, dispatchId}`. |
| `WSS`  | `/v1/device/{id}/stream`             | HMAC handshake header | Bidirectional. Device sends `TelemetryFrame`; server sends `CommandFrame`. Pings every 30s. |
| `GET`  | `/healthz`                           | none | `200 ok`. Mirrors what UptimeRobot will hit on the real server. |

### HMAC scheme

Per `doc/COMMAND_SECURITY.md`:

- Algorithm: HMAC-SHA256.
- Canonical message: `METHOD\nPATH\nNONCE\nTIMESTAMP\nBODY` (LF-joined, no trailing newline).
- Header carrier: `X-Vyzorix-Signature: base64(hmac)`, plus `X-Vyzorix-Nonce`, `X-Vyzorix-Timestamp`.
- Replay window: ±5 minutes around server clock. Nonces cached for the entire window.

The mock implements these rules. Set `-strict-hmac=true` to enforce them strictly; `false` (the default) is permissive — useful while the Android signing code is still being iterated on.

## What this mock deliberately does NOT do

- Persistence — no SQLite, no on-disk secret store, no log directory. Restart = blank slate.
- Multi-device authorization — the mock accepts any deviceId and returns the same `commandSecret`. The real server isolates secrets per device.
- TLS — listens HTTP only. The Android client treats `localhost`/`127.0.0.1` as a development override per `UPDATE_SERVER.md`.
- Dashboard endpoints (`/v1/dashboard/*`) — those are Phase 2.
- Key rotation — not part of Phase 1 (future ADR, see `adr/0001-c2-stack-rationale.md`).

## Why a separate binary

Co-locating the mock with the real server source (when it eventually lands) lets us share helper packages (`internal/contract`, `internal/proto`) once the real server materializes in Phase 1.5. Until then, the mock is fully self-contained inside this `cmd/mockserver/` directory — every file you need is here.
