# DOC_7_DATA_SECURITY_AND_PERSISTENCE.md — Hardware-Backed Cryptography, Permissions, and Scheduling Schedulers

## Document Purpose
This document is Part 7 of the 8-part Vyzorix System Mapping. It details the SQLCipher transparent database encryption, hardware-backed Android Keystore key-sealing, WorkManager constraint schedulers, and alarm managers. This document serves as the implementation specification for securing local private databases and scheduling background tasks safely on stock Android 13 without root.

---

# 1. Cryptographic Security and Database Initialization Flow

`KeystoreManager` is the single root-of-trust for ALL local cryptography in the daemon. It seals two distinct downstream secrets:

1. The **SQLCipher master passcode** that decrypts the Room database (`AppDatabase`). Wrapped by `CryptoHelper` via AES-GCM-NoPadding.
2. The **C2 `command_secret`** (32 random bytes / 64 hex chars) that authenticates remote commands. Wrapped by `TokenEncryptor` via AES-GCM and persisted by `DeviceSecretStore` to a dedicated DataStore file. See `COMMAND_SECURITY.md` for the full HMAC contract.

Both secrets share the same hardware-key envelope but live in different on-disk containers (Room DB vs DataStore). They are NEVER stored in plaintext anywhere on disk, in logcat, or in crash dumps. The `command_secret` in particular is never held in a non-scoped variable for longer than the `CommandHmacValidator.validate()` call.

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
                   AppDatabase (SQLite Secure tables)
```

## 1.1 Command Secret Storage Flow (Separate from Database)

The per-device `command_secret` follows an analogous but **separate** initialization flow. It does NOT live in the Room database — it lives in its own DataStore container managed by `DeviceSecretStore.kt`. This isolation means a Room migration bug cannot corrupt the C2 authentication state, and vice versa.

```text
              FIRST DEVICE REGISTRATION (after Accessibility grant)
                                    │
                                    ▼
               FcmTokenManager.registerDevice()
               POST /v1/device/register over HTTPS/WSS
                                    │
                                    ▼ (Server response includes command_secret)
                  DeviceSecretStore.put(secret: String)
                                    │
                                    ▼ (Delegates to TokenEncryptor.encrypt())
                       TokenEncryptor (AES-GCM)
                                    │
                                    ▼ (Key sourced from KeystoreManager)
                       [Hardware-backed Keystore]
                                    │
                                    ▼ (Encrypted blob; never plaintext on disk)
                  DataStore: device_secret.preferences_pb


              SUBSEQUENT COMMAND VALIDATION (per command)
                                    │
                                    ▼
          CommandHmacValidator.validate(frame, ???)
                                    │
                                    ▼ (Decrypt-on-demand)
                DeviceSecretStore.getSecret()
                                    │
                                    ▼ (TokenEncryptor.decrypt())
                    plaintext command_secret
                  (scoped to validate() call only;
                   not retained in any field/property)
                                    │
                                    ▼
               HMAC-SHA256(canonical_string, secret)
                  (see COMMAND_SECURITY.md §3)
```

Note: `KeystoreManager` MUST handle the Unisoc SC9863A's unreliable TEE — see §3.1 for the software-fallback rationale. The same fallback path is used for both the database passcode and the command_secret wrapping key.

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

The `security` submodule manages Android Keystore configurations, local database decryption factories, input intent sanitizers, and the C2 command-secret encryption layer.

```text
core/common/src/main/kotlin/com/vyzorix/audiorouter/common/utils/KeystoreManager.kt
core/common/src/main/kotlin/com/vyzorix/audiorouter/common/utils/CryptoHelper.kt
core/data/src/main/kotlin/com/vyzorix/audiorouter/data/database/SecureSupportHelper.kt
core/data/src/main/kotlin/com/vyzorix/audiorouter/data/datastore/DeviceSecretStore.kt
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/
├── ServicePermissionVerifier.kt
# NOTE: ProjectionTokenValidator.kt folded into ProjectionTokenManager (ADR-0006).
├── AccessibilityIntegrityChecker.kt
├── SafeIntentSanitizer.kt
├── TokenEncryptor.kt
├── CommandHmacValidator.kt   # consumer of DeviceSecretStore; spec in COMMAND_SECURITY.md
└── NonceCache.kt             # replay protection for HMAC-validated commands
```

Note on layout: `KeystoreManager` is the canonical location for hardware-backed key management (it is in `core/common/utils/`, not `services/security/`). The pre-existing `services/security/KeystoreManager.kt` reference in older docs is stale; the canonical path is the one listed above. See the repo-tree comment on `services/security/`.

`DeviceSecretStore.kt` lives in `core/data/datastore/` because it is fundamentally a persistence concern (encrypted DataStore container) that happens to be consumed by `services/security/` components. Putting it in `core/data` keeps the data-layer boundary clean and lets Layer 1 of `BUILD_ORDER.md` ship it before any C2 code exists.

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

### 3.5 ~~`ProjectionTokenValidator.kt`~~ — folded into `ProjectionTokenManager` (ADR-0006)
*   **Architectural Role**: Token validation is part of the token's lifecycle. A single class (`ProjectionTokenManager`, under `core/services/projection/`) owns acquire / validate / refresh / store. The old separation between manager and validator created an artificial boundary; the manager itself now exposes `isValid()` and `refreshIfExpired()` directly.

### 3.6 `AccessibilityIntegrityChecker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/AccessibilityIntegrityChecker.kt`
*   **Architectural Role**: Monitors accessibility status, raising alerts if the service is disabled or unbound by the OS.

### 3.7 `SafeIntentSanitizer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/SafeIntentSanitizer.kt`
*   **Architectural Role**: Sanitizes incoming intents from other apps to prevent intent-redirection attacks or crash-induction payloads.

### 3.8 `TokenEncryptor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/security/TokenEncryptor.kt`
*   **Architectural Role**: Generic AES-GCM-NoPadding wrapper used for **two** distinct secrets, with key material sourced from `KeystoreManager`:
    1. Cached `MediaProjection` credentials (legacy use case, pre-C2).
    2. The per-device C2 `command_secret` — called by `DeviceSecretStore` on `put()` to encrypt before write, and on `getSecret()` to decrypt on read.
*   **Why one class, two callers**: Both secrets need an AES-GCM envelope keyed off the same TEE-sealed root. Sharing `TokenEncryptor` avoids drift between two near-identical crypto wrappers. The IV is generated fresh per `encrypt()` call (12 bytes, `SecureRandom`) and stored alongside the ciphertext in the DataStore blob.
*   **Failure semantics**: If decryption throws `AEADBadTagException`, the caller MUST treat the stored secret as compromised — do NOT silently regenerate. `DeviceSecretStore` surfaces this as `SecretIntegrityException`; `CommandHmacValidator` then refuses to authenticate any command until re-registration completes. This is intentional: a tampered AEAD tag almost always means the on-disk blob was edited externally.
*   **Concurrency**: AES-GCM operations are CPU-bound and short. `TokenEncryptor` is thread-safe (stateless except for the cipher instance, which is created per-call). Callers may invoke from `AppDispatchers.Default` or `AppDispatchers.IO` interchangeably.

### 3.9 `DeviceSecretStore.kt`
*   **Path**: `core/data/src/main/kotlin/com/vyzorix/audiorouter/data/datastore/DeviceSecretStore.kt`
*   **Architectural Role**: Encrypted DataStore container for the per-device C2 `command_secret`. The secret is established once by `FcmTokenManager` during the first `POST /v1/device/register` round-trip (see `COMMAND_SECURITY.md` §5) and consumed thereafter only by `CommandHmacValidator.validate()`.
*   **API surface**:
    ```kotlin
    suspend fun put(secret: String)
    suspend fun getSecret(): String?           // decrypts on each read; never caches plaintext
    suspend fun clear()                          // for deregistration / safe-mode wipe
    val hasSecret: Flow<Boolean>                 // for BootStateRestorer and DaemonStatusAggregator
    ```
*   **Storage**: Preferences DataStore (`device_secret.preferences_pb`) holding only the AES-GCM blob produced by `TokenEncryptor.encrypt()`. The plaintext `command_secret` is **never** written to disk, never written to logcat, never included in `CrashSnapshotExporter` bundles, and never serialized into `LastKnownStateDumper.last_state.json`.
*   **Key material**: Sourced from `KeystoreManager` via `TokenEncryptor`. On Unisoc SC9863A devices where hardware-backed Keystore is unreliable, the software fallback documented in §3.1 applies (key derived from install-time UUID + salt). This is acceptable for the threat model in `COMMAND_SECURITY.md` §1 — the secret is still bound to this specific install and cannot be lifted by reading the DataStore file alone.
*   **Thread model**: Operations are `suspend` and dispatched to `AppDispatchers.IO` by the caller. `CommandHmacValidator` calls `getSecret()` once per validation and lets the plaintext go out of scope immediately after the `Mac.doFinal()` call — see `COMMAND_SECURITY.md` §3 for the validator implementation.
*   **Failure semantics**:
    - `getSecret()` returns `null` on a fresh install before registration completes; `CommandHmacValidator` treats this as "reject all commands until registration completes" and emits a `MISSING_SECRET` rejection. CI environments should use the bypass documented in `CI_CD_WORKFLOWS.md`.
    - On `AEADBadTagException` from `TokenEncryptor.decrypt()`, surfaces `SecretIntegrityException`; `SafeModeController` is invoked, `NonceCache.clear()` runs, and remote commands are disabled until re-registration.
*   **Cross-references**:
    - HMAC contract: `COMMAND_SECURITY.md` §3, §5.
    - SYSTEM_MAP: §6.3 (cross-dispatcher locking is NOT relevant here because access is suspension-serialized through DataStore's internal lock; it IS relevant for the downstream `NonceCache`).
    - Build order: Layer 1 in `BUILD_ORDER.md` (the persistence-layer concerns are pre-Layer 8).

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
