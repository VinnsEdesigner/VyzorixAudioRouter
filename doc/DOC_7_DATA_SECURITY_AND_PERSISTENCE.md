# DOC_7_DATA_SECURITY_AND_PERSISTENCE.md — Hardware-Backed Cryptography, Permissions, and Scheduling Schedulers

## Document Purpose
This document is Part 7 of the 8-part Vyzorix System Mapping. It details the SQLCipher transparent database encryption, hardware-backed Android Keystore key-sealing, WorkManager constraint schedulers, and alarm managers. This document serves as the implementation specification for securing local private databases and scheduling background tasks safely on stock Android 13 without root.

---

# 1. Cryptographic Security and Database Initialization Flow

The following mapping outlines the sequential steps executed when the database is initialized, binding Android Keystore hardware keys directly to the SQLCipher decryption layer:

```text
                     VYZORIXAPPINITIALIZER INITIATION
                                    │
                                    ▼
                KeystoreManager.getOrGenerateMasterKey()
                                    │
                                    ▼ (Talks directly to TEE/SecureElement)
                       [Hardware-backed Keystore]
                                    │
                                    ▼ (Returns KeySpec wrapper)
                  CryptoHelper.decryptDatabasePasscode()
                                    │
                                    ▼ (Decrypts AES-GCM-NoPadding passcode)
                   SupportFactory (SQLCipher Driver)
                                    │
                                    ▼ (Binds 256-bit AES master passcode)
                    SecureSupportHelper (Room DB Build)
                                    │
                                    ▼ (Transparent decrypt on disk)
                   DaemonDatabase (SQLite Secure tables)
```

---

# 2. Scheduled Tasks Execution and Constraints Flow

The following mapping outlines how background tasks (such as update checks and log synchronization) are evaluated against active device constraints before execution:

```text
                          SCHEDULED BACKGROUND TASK
                                      │
                                      ▼
                                TaskScheduler
                                      │
                                      ▼
                            TaskSchedulerFactory
                                      │
                                      ▼
                              WorkerConstraints
                                      │
               ┌──────────────────────┴──────────────────────┐
               │                                             │
      Constraints Met?                                Constraints Not Met?
               │                                             │
               ▼ (YES: EXECUTE)                              ▼ (NO: DEFER)
         DeferredTaskWorker                              Queue in WorkManager
               │                                             │
               ▼                                             ▼
       Execute Operation                             Retry on Constraint Change
        (Update/Sync)                                (Network/Battery Active)
```

---

# 3. Submodule: `security` (The Cryptographic Guard)

The `security` submodule manages Android Keystore configurations, local database decryption factories, and input intent sanitizers.

```text
core/common/src/main/kotlin/com/vyzorix/audiorouter/common/utils/KeystoreManager.kt
core/common/src/main/kotlin/com/vyzorix/audiorouter/common/utils/CryptoHelper.kt
core/data/src/main/kotlin/com/vyzorix/audiorouter/data/database/SecureSupportHelper.kt
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/
├── ServicePermissionVerifier.kt
├── ProjectionTokenValidator.kt
├── AccessibilityIntegrityChecker.kt
├── SafeIntentSanitizer.kt
└── TokenEncryptor.kt
```

### 3.1 `KeystoreManager.kt`
*   **Path**: `core/common/src/main/kotlin/com/vyzorix/audiorouter/common/utils/KeystoreManager.kt`
*   **Architectural Role**: Hardware-backed key manager. It accesses Android's `KeyStore` container using `KeyGenParameterSpec` to securely generate and store cryptographic keys inside the SoC's Trusted Execution Environment (TEE).
*   **Core APIs**: Binds directly to `KeyStore.getInstance("AndroidKeyStore")`.
*   **Failure Boundaries & Escape Hatches**: If the hardware Keystore is unavailable or corrupted during a system update, this class runs a local software fallback encryption scheme using randomized salt signature --because on Unisoc SC9863A, Keystore hardware attestation is unreliable, KeystoreManager needs a robust software fallback, not just catching the exception — it should silently degrade to a software key derived from install-time UUID + salt.

### 3.2 `CryptoHelper.kt`
*   **Path**: `core/common/src/main/kotlin/com/vyzorix/audiorouter/common/utils/CryptoHelper.kt`
*   **Architectural Role**: Performs hardware-accelerated AES-GCM-NoPadding local encryption/decryption of the database passcode.

### 3.3 `SecureSupportHelper.kt`
*   **Path**: `core/data/src/main/kotlin/com/vyzorix/audiorouter/data/database/SecureSupportHelper.kt`
*   **Architectural Role**: Bridges SQLCipher's 256-bit AES database encryption engine directly into Room's database factory pipeline, ensuring all tables are encrypted before being written to disk.

### 3.4 `ServicePermissionVerifier.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/ServicePermissionVerifier.kt`
*   **Architectural Role**: Validates permission states before executing privileged commands (such as verifying `MODIFY_AUDIO_SETTINGS` before forcing routes).

### 3.5 `ProjectionTokenValidator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/ProjectionTokenValidator.kt`
*   **Architectural Role**: Verifies `MediaProjection` token validity, ensuring expired tokens are flagged for re-grant.

### 3.6 `AccessibilityIntegrityChecker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/AccessibilityIntegrityChecker.kt`
*   **Architectural Role**: Monitors accessibility status, raising alerts if the service is disabled or unbound by the OS.

### 3.7 `SafeIntentSanitizer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/SafeIntentSanitizer.kt`
*   **Architectural Role**: Sanitizes incoming intents from other apps to prevent intent-redirection attacks or crash-induction payloads.

### 3.8 `TokenEncryptor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/TokenEncryptor.kt`
*   **Architectural Role**: Encrypts cached projection credentials before writing them to persistent storage.

---

# 4. Submodule: `permissions` (The State Machines)

The `permissions` package proactively requests dynamic and special system-level permissions.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/
├── PermissionStateRepository.kt
├── PermissionRecoveryDaemon.kt
├── OverlayPermissionManager.kt
├── NotificationPermissionManager.kt
├── ProjectionGrantCache.kt
└── PermissionAutoGranter.kt
```

### 4.1 `PermissionStateRepository.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/PermissionStateRepository.kt`
*   **Architectural Role**: Persists the granted or denied state of all essential permissions inside local databases.

### 4.2 `PermissionRecoveryDaemon.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/PermissionRecoveryDaemon.kt`
*   **Architectural Role**: Restores missing permission bindings, triggering trampolines if critical permissions are revoked.

### 4.3 `OverlayPermissionManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/OverlayPermissionManager.kt`
*   **Architectural Role**: Assists users in enabling `SYSTEM_ALERT_WINDOW` permissions, launching target settings screens directly.

### 4.4 `NotificationPermissionManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/NotificationPermissionManager.kt`
*   **Architectural Role**: Manages the mandatory Android 13 `POST_NOTIFICATIONS` runtime authorization checks.

### 4.5 `ProjectionGrantCache.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/ProjectionGrantCache.kt`
*   **Architectural Role**: Caches MediaProjection tokens and monitors active authorization lifecycles.

### 4.6 `PermissionAutoGranter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/permissions/PermissionAutoGranter.kt`
*   **Architectural Role**: Requests and manages app authorizations, utilizing `ActivityResultContracts` to handle requests without persistent activities.

---

# 5. Submodule: `scheduler` (The Constraint Schedulers)

The `scheduler` package coordinates WorkManager tasks, registers wakeup alarms, and handles Wakelocks.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/
├── TaskScheduler.kt
├── TaskSchedulerFactory.kt
├── WakeupAlarmCoordinator.kt
├── DeferredStartupQueue.kt
├── IdleStateCoordinator.kt
├── DeferredTaskWorker.kt
├── WorkerFactory.kt
├── WorkerConstraints.kt
├── ForegroundLaunchWindow.kt
├── WakeLockCoordinator.kt
└── AlarmRecoveryBridge.kt
```

### 5.1 `TaskScheduler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/TaskScheduler.kt`
*   **Architectural Role**: Central delayed/repeating task coordinator. It manages update checks and log syncs.

### 5.2 `TaskSchedulerFactory.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/TaskSchedulerFactory.kt`
*   **Architectural Role**: Factory building targeted background workers, specifying retry and backoff properties.

### 5.3 `WakeupAlarmCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/WakeupAlarmCoordinator.kt`
*   **Architectural Role**: Registers wakeup alarms using `AlarmManager.setAndAllowWhileIdle()` to awaken the daemon even in deep Sleep (Doze Mode).

### 5.4 `DeferredStartupQueue.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/DeferredStartupQueue.kt`
*   **Architectural Role**: Throttles heavy initialization tasks on boot, preventing CPU spikes from triggering the Nokia C22's Zygote crash bug.

### 5.5 `IdleStateCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/IdleStateCoordinator.kt`
*   **Architectural Role**: Handles Doze state transitions, scaling back WebSocket intervals when the device enters sleep states.

### 5.6 `DeferredTaskWorker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/DeferredTaskWorker.kt`
*   **Architectural Role**: Custom `CoroutineWorker` execution worker. It performs actual background updates.

### 5.7 `WorkerFactory.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/WorkerFactory.kt`
*   **Architectural Role**: Custom WorkManager factory implementing dependency injection for background workers.

### 5.8 `WorkerConstraints.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/WorkerConstraints.kt`
*   **Architectural Role**: Defines task constraints (e.g., restricting updates to run only on Wi-Fi and unmetered networks).

### 5.9 `ForegroundLaunchWindow.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/ForegroundLaunchWindow.kt`
*   **Architectural Role**: Coordinates foreground launch windows, ensuring activities are launched legally under Android 13 Go constraints.

### 5.10 `WakeLockCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/WakeLockCoordinator.kt`
*   **Architectural Role**: Manages CPU wake-locks. It manages lock states, ensuring locks are released properly to prevent battery drain.
*   **Core APIs**: Binds directly to `PowerManager.WakeLock`.

### 5.11 `AlarmRecoveryBridge.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/scheduler/AlarmRecoveryBridge.kt`
*   **Architectural Role**: Binds fallback recovery alarms, coordinating background re-starts if services are terminated by the OS.
