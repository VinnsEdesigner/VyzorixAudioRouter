# COMMAND_SECURITY.md — Remote Command Signing, Replay Protection, and Key Establishment (deep-dive of DOC_8)

> **This is a deep-dive of [`DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md`](./DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md).** DOC_8 is the canonical spec for the C2 stack; this document covers the HMAC contract, replay protection, and per-device secret flow at implementation depth. See ADR-0001 (C2 stack rationale) and ADR-0005 (WebSocket + FCM dual-channel) for the higher-level design decisions.

## Document Purpose
This document defines the full security contract for remote C2 commands issued from the
Vyzorix control server to the Android daemon client. It covers HMAC-SHA256 payload signing,
per-command nonce generation, timestamp-based replay protection, nonce deduplication cache,
and the per-device secret key establishment flow during device registration.

Transport-layer encryption (WSS/TLS) is assumed and required for all communication. The
mechanisms described here operate **above** TLS to protect against threats TLS cannot cover:
compromised server credentials, stolen JWT dashboard tokens, and replay attacks on captured
WebSocket frames.

---

## 1. Threat Model

**Deployment posture (read this first).** VyzorixAudioRouter currently ships to a **single personal device** (the project owner's Nokia C22). It is **not** designed for adversarial multi-tenant environments. The security depth documented below — HMAC-SHA256, per-device secret, nonce cache, replay window, certificate pinning — is **defense-in-depth for future scaling**, not because there is an active adversary today. We keep this depth in the design because the infrastructure cost to remove it later (after the architecture is depended on) is much higher than the cost to keep it now. If you are reading this and wondering "why is a personal project doing HMAC for a single device?", that is the answer.

The threat coverage table below assumes the **future-scaled** deployment, where the same code may serve multiple devices and a non-trusted Vyzorix dashboard. For the current single-device deployment, the practical attackers are limited to "someone who pulls the device APK and tries to talk to my server" — which all the mechanisms below still cover.

| Threat | TLS Coverage | HMAC Coverage |
|---|---|---|
| Network eavesdropping | ✅ | N/A |
| Compromised Render server issues arbitrary commands | ❌ | ✅ |
| Stolen dashboard JWT used to issue commands | ❌ | ✅ |
| Captured WebSocket frame replayed later | ❌ | ✅ (nonce + timestamp window) |
| MITM on WSS connection | ✅ (certificate pinning recommended) | N/A |
| Physical device dump of command secret | ❌ | ✅ (secret encrypted via TokenEncryptor.kt) |

**Explicitly out of scope** for the current deployment:
- Physical access to the device by a sophisticated adversary (the C22's Unisoc TEE is itself unreliable — see DOC_7 §3.1).
- A compromised Google account that can hijack the FCM channel — FCM is an unauthenticated wake channel here, not a command-authority channel (ADR-0005). Compromise of FCM lets an attacker wake the daemon and prompt it to reconnect to the WSS, but commands themselves must still pass HMAC validation.
- A compromised Nokia firmware that exfiltrates the encrypted secret blob from disk. If the C22 vendor firmware is compromised, this app's security is the least of the user's problems.

See also: ADR-0001 (rationale for the C2 stack depth) and ADR-0005 (WebSocket + FCM dual-channel rationale).

---

## 2. CommandFrame Extended Schema

All C2 command frames sent from server to device include two additional fields beyond the
base transaction payload:

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

### Field Definitions

| Field | Type | Description |
|---|---|---|
| `nonce` | string (16-byte hex, 32 chars) | Cryptographically random value generated per command; never reused; stored in server DB for audit |
| `hmac` | string (SHA256 hex, 64 chars) | HMAC-SHA256 of the canonical message string (see §3); computed using per-device command_secret |

---

## 3. HMAC Computation

### Canonical Message String

The HMAC input is a deterministic concatenation of fields in this exact order, pipe-delimited:

```
{transactionId}|{deviceId}|{action}|{timestamp_unix_ms}|{nonce}|{params}
```

Example:
```
f7893a2-bcd0-4e12|uuid-nokia-c22-092831|REINIT_PROJECTION|1748260800000|a3f8c1d2e4b56789|{}
```

### Rules
- `timestamp` is expressed as Unix milliseconds (int64) in the canonical string — never ISO8601 string — to avoid timezone/format ambiguity between Go and Kotlin
- `params` is the raw JSON string as-is; empty params = `{}`
- No whitespace padding anywhere in the canonical string
- HMAC algorithm: **HMAC-SHA256**
- Key: per-device `command_secret` (32 random bytes, hex-encoded 64 chars)

### Server-side (Go) — `services/command_signer.go`

```go
func SignCommand(frame *models.CommandFrame, secret string) (string, string, error) {
    nonce := generateNonce()  // crypto/rand 16 bytes → hex
    canonical := fmt.Sprintf("%s|%s|%s|%d|%s|%s",
        frame.TransactionID,
        frame.DeviceID,
        frame.Action,
        frame.Timestamp.UnixMilli(),
        nonce,
        frame.Params,
    )
    mac := hmac.New(sha256.New, []byte(secret))
    mac.Write([]byte(canonical))
    return nonce, hex.EncodeToString(mac.Sum(nil)), nil
}
```

### Device-side (Kotlin) — `CommandHmacValidator.kt`

```kotlin
fun validate(frame: CommandFrame, secret: String): ValidationResult {
    // 1. Recompute canonical string
    val canonical = "${frame.transactionId}|${frame.deviceId}|${frame.action}" +
                    "|${frame.timestampMs}|${frame.nonce}|${frame.params}"
    // 2. Recompute HMAC
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.hexToByteArray(), "HmacSHA256"))
    val computed = mac.doFinal(canonical.toByteArray()).toHex()
    // 3. Constant-time comparison (prevents timing attacks)
    if (!MessageDigest.isEqual(computed.toByteArray(), frame.hmac.toByteArray())) {
        return ValidationResult.INVALID_SIGNATURE
    }
    // 4. Timestamp window check (±30s)
    val drift = abs(System.currentTimeMillis() - frame.timestampMs)
    if (drift > 30_000L) return ValidationResult.EXPIRED_TIMESTAMP
    // 5. Nonce deduplication
    if (NonceCache.contains(frame.nonce)) return ValidationResult.REPLAYED_NONCE
    NonceCache.store(frame.nonce)
    return ValidationResult.VALID
}
```

---

## 4. Nonce Cache — Replay Protection

### `NonceCache.kt` specification

- **Storage**: Thread-safe in-memory `LinkedHashMap` with LRU eviction
- **TTL**: 5 minutes — matches the maximum timestamp drift window with 2.5x safety margin
- **Capacity**: 200 entries maximum — on a 2GB device even 200 * ~40 bytes = ~8KB footprint
- **Eviction**: Entries older than 5min are purged on every `store()` call (lazy eviction); no background thread needed
- **Persistence**: Not persisted across process restarts — this is intentional; a replayed frame after a reboot would have a timestamp >30s stale and fail the timestamp check first anyway

```
NonceCache operations:
    contains(nonce: String): Boolean   — O(1) lookup
    store(nonce: String): Unit         — stores with current timestamp; triggers eviction pass
    clear(): Unit                      — called by SafeModeController on safe mode entry
```

---

## 5. Per-Device Secret Key Establishment

### Registration Flow

The `command_secret` is established once per device during initial registration and never
transmitted again after that point.

```text
Device first boot (after Accessibility grant)
    │
    ▼
FcmTokenManager.kt
    - Generates device UUID (stored in DeviceSecretStore.kt)
    - Calls POST /v1/device/register over HTTPS/WSS with:
        { "deviceId": uuid, "fcmToken": token, "androidVersion": "13" }
    │
    ▼
controllers/device.go (Go server)
    - Generates command_secret = crypto/rand 32 bytes → hex (64 chars)
    - Stores command_secret in devices table (command_secret column)
    - Returns in registration response:
        { "deviceId": uuid, "commandSecret": "abc123...64chars" }
    │
    ▼
Device receives response
    - DeviceSecretStore.kt passes secret to TokenEncryptor.kt
    - TokenEncryptor.kt encrypts with AES-GCM using KeystoreManager key
    - Writes encrypted blob to DataStore
    - commandSecret is NEVER stored in plaintext anywhere
    │
    ▼
Subsequent commands
    - RemoteCommandExecutor.kt calls DeviceSecretStore.kt.getSecret()
    - DeviceSecretStore.kt decrypts via TokenEncryptor.kt on each read
    - Secret passed to CommandHmacValidator.kt for HMAC recomputation
    - Secret is never held in a non-scoped variable longer than the validation call
```

### Secret Rotation (future)

Secret rotation is not implemented in Phase 1 but the registration endpoint should accept
an optional `rotateSecret: true` flag in future to allow server-initiated key rotation
without full device re-registration.

---

## 6. Rejection Behaviour

When `CommandHmacValidator.kt` rejects a command, the device:

1. Logs rejection reason to `CrashTraceStore.kt` with full frame metadata (minus secret)
2. Does NOT execute the command
3. Sends a rejection result back via `RemoteCommandResultDispatcher.kt`:
```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId":      "uuid-nokia-c22-092831",
  "action":        "REINIT_PROJECTION",
  "success":       false,
  "timestamp":     "2026-05-26T12:00:00.080Z",
  "payload": {
    "error": "INVALID_SIGNATURE",
    "detail": "HMAC mismatch — command rejected"
  }
}
```
4. After 3 consecutive rejections within 60s → `ServicePermissionVerifier.kt` triggers
   alert and disables remote command execution temporarily (5min cooldown) to prevent
   brute-force probing

---

## 7. FCM Command Signing

Silent FCM push payloads that carry command actions (not just WAKE_DAEMON) must also be
signed. The FCM data payload uses the same HMAC scheme:

```json
{
  "action":        "FORCE_SPEAKER",
  "transactionId": "f7893a2-bcd0-4e12",
  "timestamp":     "1748260800000",
  "nonce":         "a3f8c1d2e4b56789",
  "hmac":          "9f3a1bc2..."
}
```

`FcmCommandParser.kt` passes the reconstructed `CommandFrame` to `CommandHmacValidator.kt`
before any execution — same validation path as WebSocket commands.

---

## 8. Files Involved

### Android (`core/services/`)
| File | Role |
|---|---|
| `security/CommandHmacValidator.kt` | HMAC recomputation, timestamp check, nonce dedup |
| `security/NonceCache.kt` | Thread-safe TTL nonce deduplication store |
| `data/datastore/DeviceSecretStore.kt` | Encrypted persistence of command_secret |
| `ipc/RemoteCommandExecutor.kt` | Calls validator before execution |
| `fcm/FcmCommandParser.kt` | Calls validator on FCM command payloads |

### Go Server (`vyzorix-update-server/`)
| File | Role |
|---|---|
| `services/command_signer.go` | Nonce generation, canonical string construction, HMAC computation |
| `controllers/command.go` | Calls command_signer before dispatching frame |
| `controllers/device.go` | Generates command_secret on registration, stores in DB |
| `models/command.go` | CommandFrame struct with Nonce + HMAC fields |
| `storage/migrations.go` | Adds command_secret column to devices table |
