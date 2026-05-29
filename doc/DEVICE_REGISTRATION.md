# DEVICE_REGISTRATION.md — Server-Side Device Lifecycle (deep-dive of DOC_8)

> **This is a deep-dive of [`DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md`](./DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md).** DOC_8 is the canonical spec for the C2 stack; this document covers the server-side device lifecycle in implementation-level detail.

> **Repo scope:** This document is authored in `VyzorixAudioRouter/doc/` and auto-synced to `vyzorix-update-server/doc/` by the `sync_repo.yml` workflow. It describes the **server side** of the device registration / lifecycle flow. The device-side counterpart lives in `COMMAND_SECURITY.md` (HMAC contract) and `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` (storage).

## Document Purpose

The `command_secret` (per-device shared HMAC key) is generated server-side during the first `POST /v1/device/register` call. The device side of this flow is well-documented in `COMMAND_SECURITY.md`. The server side has **no document** — the only reference is a single line in `UPDATE_SERVER_ARCHITECTURE_SPEC.md` §6.2 that says "Manages REST-based device registrations." That is not enough to implement against.

This document fills that gap. It is the canonical REST + WSS contract for everything the Go server in `vyzorix-update-server` does with a `Device` row in SQLite, from first-time registration through deregistration.

Cross-references:
- `COMMAND_SECURITY.md` §5 — device-side registration flow.
- `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §1.1 and §3.9 — how the device stores the returned secret.
- `UPDATE_SERVER.md` and `UPDATE_SERVER_ARCHITECTURE_SPEC.md` — the broader Go server architecture this slots into.
- `SYSTEM_MAP.md` §3 service interaction matrix — `FcmTokenManager → DeviceSecretStore` is the device-side caller of every endpoint in this document.

---

## 1. Threat Model & Design Goals

Goals:

1. The `command_secret` is per-device, never reused, and never transmitted over the wire after the initial registration response.
2. Registration is idempotent at the device-ID level: re-registering the same physical device returns either the existing secret (under the same Firebase identity) or refuses (under a different identity).
3. Token refresh (FCM token rotation) does NOT require a new `command_secret`. The secret is bound to the device, not the FCM token.
4. Deregistration is explicit and irreversible on the server (the device row is marked deregistered; subsequent commands fail).
5. No anonymous registration. Devices must present a valid Firebase Installation ID at registration time so we can correlate with FCM delivery later.

Non-goals:

- We are NOT building a multi-tenant SaaS. The current `vyzorix-update-server` is single-fleet (one Vyzorix install, many C22 daemons). Per-fleet isolation is future work.
- We do NOT support multiple `command_secret`s per device (no key rotation in v1). If a device's secret is compromised, the only remediation is `DELETE /v1/device/:id` + re-register.

---

## 2. Device States

A device on the server transitions through these states. The state is stored in `devices.state` (column to be added to the schema; see §6).

```
            POST /v1/device/register
                       │
                       ▼
                 ┌───────────┐
                 │ REGISTERED│  initial state after secret generation
                 └─────┬─────┘
                       │
            WSS connect or first telemetry
                       │
                       ▼
                 ┌───────────┐    WSS disconnect    ┌───────────┐
                 │  ONLINE   │ ───────────────────► │  OFFLINE  │
                 │           │ ◄─────────────────── │           │
                 └─────┬─────┘    WSS reconnect     └─────┬─────┘
                       │                                   │
                       │      DELETE /v1/device/:id        │
                       └─────────────────┬─────────────────┘
                                         ▼
                                   ┌─────────────┐
                                   │DEREGISTERED │  terminal state;
                                   │             │  commands rejected
                                   └─────────────┘
```

Transitions:

| From | To | Trigger | Side effects |
|------|----|---------|--------------|
| (none) | REGISTERED | `POST /v1/device/register` | Generate command_secret; insert devices row; return secret in response body |
| REGISTERED | ONLINE | First successful WSS handshake OR first telemetry frame received | `devices.is_online = true`; `devices.last_seen = NOW()` |
| ONLINE | OFFLINE | WSS disconnect (clean close OR ping timeout, see UPDATE_SERVER §5.2) | `devices.is_online = false`; `devices.last_seen` retained |
| OFFLINE | ONLINE | WSS reconnect | `devices.is_online = true`; `devices.last_seen = NOW()` |
| ANY non-terminal | DEREGISTERED | `DELETE /v1/device/:id` (auth required) | `devices.state = 'deregistered'`; subsequent commands rejected with HTTP 410 Gone or WSS close with reason `device_deregistered` |

Idle timeout: if a device has been OFFLINE for >30 days, the server may move it to DEREGISTERED automatically (sweeper job; not required for v1).

---

## 3. REST Endpoints

### 3.1 `POST /v1/device/register`

**Purpose:** First-time registration. Generates and returns the `command_secret`.

**Request:**

```http
POST /v1/device/register HTTP/1.1
Content-Type: application/json
Authorization: Bearer <fleet_registration_token>

{
  "deviceId":         "<UUID v4 generated by the device on first install>",
  "fcmToken":         "<Firebase Cloud Messaging token>",
  "firebaseInstallId":"<Firebase Installation ID; correlates with FCM identity>",
  "androidVersion":   "13",
  "buildFingerprint": "Nokia/TA1502/RM-1130_00WW:13/...",
  "appVersionCode":   42,
  "appVersionName":   "2.1.0"
}
```

Notes:
- `Authorization: Bearer <fleet_registration_token>` is a SHARED secret baked into the APK at build time. It does NOT identify a specific device — it only proves the request came from a Vyzorix-signed binary. This is intentional weak auth at registration time; the per-device `command_secret` returned in the response is the strong auth used thereafter.
- `deviceId` is a UUID the device generates itself on first install (stored in `core/data/datastore/DeviceIdStore.kt` — out of scope here). The server treats it as opaque.
- `firebaseInstallId` lets us correlate this device row with FCM delivery telemetry later. Without it we cannot reliably push silent wakeups to a specific device.

**Response (201 Created):**

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "deviceId":      "<echoed UUID>",
  "commandSecret": "<64 hex chars = 32 random bytes from crypto/rand>",
  "wssEndpoint":   "wss://updates.vyzorix.com/v1/ws",
  "registeredAt":  "2026-05-28T23:52:00Z",
  "fleetId":       "vyzorix-default"
}
```

- `commandSecret` is generated server-side via `crypto/rand.Read(make([]byte, 32))` then hex-encoded. It is **the only time it is ever transmitted**. The device stores it via `DeviceSecretStore` (encrypted at rest) and the server stores a hash (not the raw value — see §6).

**Errors:**

| Status | Body | Meaning |
|--------|------|---------|
| 400 Bad Request | `{"error":"missing_field","field":"deviceId"}` | Required field omitted or malformed |
| 401 Unauthorized | `{"error":"invalid_fleet_token"}` | Bearer token not recognized |
| 409 Conflict | `{"error":"already_registered","deviceId":"..."}` | Same deviceId already exists with a different firebaseInstallId; rejects to prevent secret hijack |
| 429 Too Many Requests | `{"error":"rate_limited"}` | `middleware/rate_limiter.go` enforces a per-IP limit on registration |
| 500 Internal Server Error | `{"error":"internal"}` | DB write failed or crypto/rand returned error |

**Idempotency:**

If the same `deviceId` + `firebaseInstallId` pair retries `POST /v1/device/register`, the server returns the **existing** registration with the **same** `commandSecret` (201 Created again, but with the original `registeredAt`). This handles the case where the device successfully registered but lost the response (network blip). Without idempotency the device would have no way to recover.

If `deviceId` is the same but `firebaseInstallId` is different, we return 409 Conflict. This handles the case where someone is trying to hijack a device row.

### 3.2 `PATCH /v1/device/:id/fcm-token`

**Purpose:** FCM token rotation. The device must already be registered.

**Request:**

```http
PATCH /v1/device/abc-123/fcm-token HTTP/1.1
Content-Type: application/json
X-Vyzorix-Device-Id: abc-123
X-Vyzorix-Hmac: <hex HMAC-SHA256 over body using command_secret>
X-Vyzorix-Timestamp: 1716937920
X-Vyzorix-Nonce: <UUID v4>

{
  "fcmToken": "<new FCM token>"
}
```

- All four `X-Vyzorix-*` headers are required and validated server-side using the same HMAC contract documented in `COMMAND_SECURITY.md` §3 (mirrored on the server in `middleware/auth.go`).
- The `command_secret` is NEVER in the request body or headers. It is only used to compute the HMAC.

**Response (200 OK):**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "deviceId":   "abc-123",
  "fcmToken":   "<echoed new token>",
  "updatedAt":  "2026-05-29T00:01:00Z"
}
```

**Errors:**

| Status | Body | Meaning |
|--------|------|---------|
| 401 Unauthorized | `{"error":"invalid_hmac"}` | HMAC validation failed |
| 401 Unauthorized | `{"error":"expired_timestamp"}` | Timestamp outside 30s window |
| 401 Unauthorized | `{"error":"replayed_nonce"}` | Nonce already seen within TTL |
| 404 Not Found | `{"error":"device_not_found"}` | No row matches `deviceId` |
| 410 Gone | `{"error":"device_deregistered"}` | Device is in DEREGISTERED state |

### 3.3 `GET /v1/device/:id/status`

**Purpose:** Dashboard query for a single device's current state.

Requires the dashboard's session cookie (not HMAC; this is a human-driven endpoint). See `middleware/auth.go`.

**Response (200 OK):**

```json
{
  "deviceId":      "abc-123",
  "state":         "ONLINE",
  "isOnline":      true,
  "lastSeen":      "2026-05-29T00:00:42Z",
  "registeredAt":  "2026-05-28T23:52:00Z",
  "androidVersion":"13",
  "appVersionCode":42,
  "wsConnected":   true,
  "lastTelemetry": { "uptime": 12345, "riskScore": 0, "audioMode": 3, "speakerOn": true }
}
```

`commandSecret` is NEVER included in this response.

### 3.4 `DELETE /v1/device/:id`

**Purpose:** Explicit deregistration. Either dashboard-initiated (operator) or device-initiated (uninstall flow).

If dashboard-initiated: dashboard session cookie required.
If device-initiated: HMAC headers (§3.2 pattern) required.

**Response (204 No Content):**

```http
HTTP/1.1 204 No Content
```

Side effects:

1. `devices.state = 'deregistered'`.
2. `devices.command_secret_hash = ''` (zero out so even the hash is gone).
3. Server force-closes any active WSS connection for this device with close code 4001 / reason `device_deregistered`.
4. Subsequent HMAC validation rejects all commands from this device with `device_deregistered`.

Re-registering after a deregistration requires a fresh `POST /v1/device/register` and produces a **new** `commandSecret` — the old one is dead.

---

## 4. WSS Lifecycle (Connection State Transitions)

The WSS endpoint is `wss://updates.vyzorix.com/v1/ws` (returned in the registration response). It is documented in `UPDATE_SERVER.md` and `UPDATE_SERVER_ARCHITECTURE_SPEC.md` §5; this section covers only the device-state implications.

### 4.1 Connection

The device opens the WSS connection with these headers:

```
X-Vyzorix-Device-Id: <deviceId>
X-Vyzorix-Hmac: <HMAC over "CONNECT:<deviceId>:<timestamp>:<nonce>" using command_secret>
X-Vyzorix-Timestamp: <unix seconds>
X-Vyzorix-Nonce: <UUID v4>
```

If validation succeeds:
- The `hub` registers the client (see `hub/hub.go` `register` channel).
- The device's state transitions from REGISTERED → ONLINE or OFFLINE → ONLINE.
- `devices.is_online = true`, `devices.last_seen = NOW()`.

If validation fails: close with code 4401 / reason `invalid_hmac`. Device stays in whatever state it was in.

### 4.2 Keepalive

Per `UPDATE_SERVER_ARCHITECTURE_SPEC.md` §5.2: ping/pong frames every 15 seconds. If no pong within 30s, server force-closes and moves the device to OFFLINE.

### 4.3 Clean Disconnect

Either side may send a close frame. The server transitions the device to OFFLINE (not DEREGISTERED — disconnection is normal; deregistration is explicit).

---

## 5. Commands Issued to a Device

This is the operational reason the registration flow exists. The dashboard or an internal job submits a command:

```http
POST /v1/command HTTP/1.1
Content-Type: application/json
Authorization: Dashboard-Session <session_id>

{
  "deviceId": "abc-123",
  "action":   "FORCE_SPEAKER",
  "params":   { "duration_ms": 60000 }
}
```

The server constructs a signed `CommandFrame` (signing on behalf of the dashboard since the dashboard does not have the device's `command_secret`):

1. Look up `devices.command_secret_hash` → reject if device not registered or deregistered.
2. Look up the **raw** `command_secret` from the server-side secret store (see §6 — note that the server DOES retain the raw secret in a separate, access-controlled store; the SQLite row only stores the hash for quick existence checks).
3. Compute HMAC-SHA256 over the canonical command string using the raw secret.
4. Build the `CommandFrame { transactionId, deviceId, action, timestamp, nonce, hmac, params }`.
5. If the device is ONLINE: hand the frame to `hub.ActiveHub.Send(deviceId, frame)`.
6. If the device is OFFLINE: hand the frame to `services/fcm.SendSilentPush(deviceId, frame)` so FCM wakes the daemon (see `UPDATE_SERVER_ARCHITECTURE_SPEC.md` §6.3 and §7.2). The frame is delivered as the silent push payload.
7. The device's `CommandHmacValidator` re-validates the frame against its locally-stored `command_secret`. They must match for the command to execute.

---

## 6. Server-Side Storage

The `devices` table needs additions beyond the current schema in `storage/migrations.go`. Proposed schema (DDL):

```sql
CREATE TABLE IF NOT EXISTS devices (
  id                      TEXT PRIMARY KEY,                  -- deviceId (UUID v4 from device)
  firebase_install_id     TEXT NOT NULL,                     -- correlation with FCM identity
  fcm_token               TEXT NOT NULL,                     -- current FCM token; rotated via PATCH
  android_version         TEXT NOT NULL,
  build_fingerprint       TEXT,
  app_version_code        INTEGER,
  app_version_name        TEXT,

  command_secret_hash     TEXT NOT NULL,                     -- bcrypt or scrypt over the raw secret;
                                                              -- existence check only, not used for HMAC
  -- The RAW command_secret lives in a separate secret store
  -- (not in this table). See §6.1 below.

  state                   TEXT NOT NULL DEFAULT 'REGISTERED',-- REGISTERED | ONLINE | OFFLINE | DEREGISTERED
  is_online               INTEGER NOT NULL DEFAULT 0,        -- mirrors state for query speed
  last_seen               TIMESTAMP,

  registered_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deregistered_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_devices_state    ON devices(state);
CREATE INDEX IF NOT EXISTS idx_devices_lastseen ON devices(last_seen);
```

This is an additive migration to the existing `devices` table; the existing columns (`id`, `fcm_token`, `android_version`, `is_online`, `last_seen`) remain. The migration belongs in a new `storage/migrations.go` step.

### 6.1 Where the Raw `command_secret` Lives

The raw `command_secret` is needed server-side because the server signs commands on behalf of the dashboard (the dashboard never sees the secret).

It does NOT live in the SQLite `devices` table. Two options for the v1 server:

1. **Filesystem secret store**: `data/secrets/<deviceId>.bin` with `0600` permissions, encrypted at rest via a server-side master key from environment variable `VYZORIX_SECRET_MASTER_KEY` (AES-GCM wrap). The `secretstore.Get(deviceId)` interface returns the raw secret; the interface is the only code path that touches the on-disk file. This is the recommended v1 implementation.
2. **External KMS**: AWS Secrets Manager / GCP Secret Manager / HashiCorp Vault. Out of scope for v1 single-fleet single-VPS deployment, but the `secretstore.SecretStore` interface above should be designed so the implementation can be swapped to a KMS-backed one without touching `controllers/device.go`.

Either way, the SQLite row stores ONLY a hash so that:
- Existence checks (`device registered?`) are fast.
- An attacker who exfiltrates `data.db` cannot recover the raw secret.
- The hash is bcrypt or scrypt with a per-secret salt, NOT a fast hash like SHA-256 (we want it to be slow if leaked).

---

## 7. Telemetry & Online-State Tracking

Per `UPDATE_SERVER_ARCHITECTURE_SPEC.md` §4.2, telemetry frames flow over the WSS connection. Each telemetry frame updates `devices.last_seen`. There is no special endpoint for "is online" — `state` is derived from WSS connection presence:

- WSS connected within last 30s → ONLINE.
- WSS not connected → OFFLINE.

The `hub` goroutine in `hub/hub.go` owns the authoritative connection map; it writes `devices.is_online` on register/unregister.

---

## 8. Migration & Backward Compatibility

For devices already running an older build that uses the old (no `command_secret`) flow, the server must:

1. Accept the old registration shape gracefully (no HMAC headers on the registration call itself — only on subsequent calls).
2. Generate a `command_secret` server-side and return it in the response so older devices that were not expecting it can simply discard it without crashing. The next-generation device build is required to actually parse and store the secret.

This means even if the device is on v1.x and ignores `commandSecret`, the server's behavior is unchanged for that device — commands cannot be sent to it because it has no validator. The server just won't try to sign commands for devices whose `command_secret_hash` is empty.

For the cutover plan: bump the device's `appVersionCode` requirement on the dashboard so the operator knows which devices in the fleet are eligible for HMAC-protected remote commands.

---

## 9. Open Questions / Future Work

- **Key rotation**: v1 has no rotation path. Add `POST /v1/device/:id/rotate-secret` that requires both the current HMAC AND a dashboard-session cookie (two-party authorization).
- **Per-fleet isolation**: today `fleetId` is hardcoded `"vyzorix-default"`. Future multi-tenant deployments need this to actually segment devices.
- **Hardware attestation**: today registration trusts the bearer token + device-generated UUID. A future hardened deployment could require Play Integrity API attestation (or a stock-Android equivalent) at registration time. Out of scope for the C22 because we deliberately ship without Play Integrity.
- **Sweeper job**: 30-day OFFLINE → DEREGISTERED automatic transition. Useful but not required for v1.
