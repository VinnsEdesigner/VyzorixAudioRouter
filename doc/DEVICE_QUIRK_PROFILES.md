# DEVICE_QUIRK_PROFILES.md — Runtime device-specific behavior

## Purpose

This document defines the `DeviceQuirkProfile` system: how the daemon adapts to device-specific hardware and OS behaviors at runtime, without hardcoding device checks across the codebase.

See **ADR-0008** for the rationale of this design over the previous hardcoded approach.

## The schema

```kotlin
// core/common/device/DeviceQuirkProfile.kt

data class DeviceQuirkProfile(
    val deviceClass: String,
    val socFamily: SocFamily,
    val schedulerBehavior: SchedulerBehavior,
    val keystoreReliability: KeystoreReliability,
    val thermalZones: List<String>,
    val alsaTimingGapMs: Int,
    val audioModeQuirks: Set<AudioModeQuirk>,
    val backgroundRestrictionLevel: BackgroundRestrictionLevel,
    val notificationCompat: NotificationCompatMode,
    val foregroundServiceTypeBundle: ForegroundServiceTypeBundle,
    val playProtectStance: PlayProtectStance,
    val packageQueryStrategy: PackageQueryStrategy,
)
```

### Field reference

#### `deviceClass: String`
Canonical short name. Lowercase, snake_case. Examples: `"nokia_c22"`, `"unknown_device"`, `"samsung_a14"` (hypothetical future).

#### `socFamily: SocFamily`
Enum of recognized SoC families. Used by native code to select tuning parameters.
```kotlin
enum class SocFamily {
    UNISOC_SC9863A,
    UNISOC_OTHER,
    QUALCOMM_SNAPDRAGON,
    MEDIATEK_HELIO,
    MEDIATEK_DIMENSITY,
    SAMSUNG_EXYNOS,
    UNKNOWN,
}
```

#### `schedulerBehavior: SchedulerBehavior`
How the kernel scheduler responds to `SCHED_FIFO` elevation.
```kotlin
enum class SchedulerBehavior {
    RELIABLE_SCHED_FIFO,  // sched_setscheduler return code can be trusted
    SILENT_FALLBACK,      // syscall returns 0 but policy may downgrade — read-back check required
    KNOWN_DEGRADED,       // SCHED_FIFO known unavailable; don't even try
}
```

Read by `thread_priority_guard.cpp` and `LatencyOptimizer.kt`. Drives whether the read-back check is mandatory and whether to expand the chunk size pre-emptively.

#### `keystoreReliability: KeystoreReliability`
Whether the Hardware-Backed Keystore TEE is trustworthy.
```kotlin
enum class KeystoreReliability {
    RELIABLE,                     // try HW keystore, treat failures as bugs
    UNRELIABLE_USE_SOFTWARE_FALLBACK,  // try HW, on documented failures derive software key
}
```

Read by `KeystoreManager.kt`. Drives whether documented failure signatures (e.g. `ProviderException` from `KeyGenerator.generateKey()`) trigger automatic software-key derivation or surface as errors.

#### `thermalZones: List<String>`
Ordered list of sysfs paths to read for SoC temperature. First-match-wins. Examples:
```kotlin
NokiaC22Profile = DeviceQuirkProfile(
    thermalZones = listOf(
        "/sys/class/thermal/thermal_zone0/temp",  // SC9863A primary
        "/sys/class/thermal/thermal_zone1/temp",  // fallback observed on some firmware
    ),
    // ...
)
```

Read by `DeviceThermalMonitor.kt`.

#### `alsaTimingGapMs: Int`
Number of milliseconds to wait between consecutive ALSA ioctl calls in the audio HAL bridge. `0` = no gap. Unisoc's closed-source audio HAL benefits from a `2`ms gap to avoid deadlocks.

Read by `UnisocPlatformTweaks.kt` and audio HAL bridge code.

#### `audioModeQuirks: Set<AudioModeQuirk>`
Set of recognized audio-mode behaviors.
```kotlin
enum class AudioModeQuirk {
    PHANTOM_HEADSET_ROUTE,    // policy manager routes to nonexistent headset codec
    MODE_DROPS_ON_DOZE,       // setMode(MODE_IN_COMMUNICATION) silently reverts during Doze
    SPEAKERPHONE_NEEDS_REASSERT, // setSpeakerphoneOn() needs periodic re-call
    BLUETOOTH_AUTO_ROUTES,    // system auto-routes to BT when paired without our consent
}
```

Read by `SpeakerForceEngine.kt`, `RoutePersistenceDaemon.kt`, `AudioModeKeeper.kt`.

#### `backgroundRestrictionLevel: BackgroundRestrictionLevel`
How aggressively the OS reaps background processes / restricts background work.
```kotlin
enum class BackgroundRestrictionLevel {
    LENIENT,    // pre-A13 or modified Android (e.g. some MIUI builds)
    STANDARD,   // stock A13+
    AGGRESSIVE, // some OEMs (e.g. Huawei pre-EMUI-12)
    UNKNOWN,
}
```

Read by `DaemonLifecycleManager.kt` and `ServiceConnectionManager.kt`.

#### `notificationCompat: NotificationCompatMode`
Notification API behavior for the foreground service.
```kotlin
enum class NotificationCompatMode {
    A13_STANDARD,    // POST_NOTIFICATIONS runtime permission + 5s startForeground window
    A12_OR_OLDER,    // no POST_NOTIFICATIONS requirement
}
```

Read by `NotificationCompatBridge.kt`.

#### `foregroundServiceTypeBundle: ForegroundServiceTypeBundle`
The set of `foregroundServiceType` flags this device version requires. On A13+ certain combinations (mediaProjection + microphone + dataSync) must be declared together.

#### `playProtectStance: PlayProtectStance`
Whether Google Play Protect is expected to interfere with sideloaded operations.
```kotlin
enum class PlayProtectStance {
    GPP_DISABLED,                  // operator has confirmed GPP is off
    GPP_ENABLED_TOLERATED,         // GPP may warn but install/run will succeed
    GPP_BLOCKING,                  // GPP actively blocks; daemon must adapt or fail safely
}
```

#### `packageQueryStrategy: PackageQueryStrategy`
How to query installed packages given A11+ package visibility restrictions.
```kotlin
enum class PackageQueryStrategy {
    QUERY_ALL_PACKAGES_GRANTED,    // QUERY_ALL_PACKAGES is in manifest and granted
    SPECIFIC_PACKAGES_ONLY,        // use <queries> with specific packages
    LIMITED,                       // can only see own package + system
}
```

## Profiles defined

### `NokiaC22Profile`

```kotlin
val NokiaC22Profile = DeviceQuirkProfile(
    deviceClass = "nokia_c22",
    socFamily = SocFamily.UNISOC_SC9863A,
    schedulerBehavior = SchedulerBehavior.SILENT_FALLBACK,
    keystoreReliability = KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK,
    thermalZones = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
    ),
    alsaTimingGapMs = 2,
    audioModeQuirks = setOf(
        AudioModeQuirk.PHANTOM_HEADSET_ROUTE,
        AudioModeQuirk.MODE_DROPS_ON_DOZE,
        AudioModeQuirk.SPEAKERPHONE_NEEDS_REASSERT,
    ),
    backgroundRestrictionLevel = BackgroundRestrictionLevel.STANDARD,
    notificationCompat = NotificationCompatMode.A13_STANDARD,
    foregroundServiceTypeBundle = ForegroundServiceTypeBundle.MEDIA_PROJECTION_MICROPHONE_DATA_SYNC,
    playProtectStance = PlayProtectStance.GPP_DISABLED,
    packageQueryStrategy = PackageQueryStrategy.QUERY_ALL_PACKAGES_GRANTED,
)
```

The full rationale for each field is in `NOKIA_C22_NOTES.md`.

### `UnknownDeviceProfile` (default)

Used when no specific profile matches. Conservative defaults that keep the daemon functional but limit aggressive optimizations.

```kotlin
val UnknownDeviceProfile = DeviceQuirkProfile(
    deviceClass = "unknown_device",
    socFamily = SocFamily.UNKNOWN,
    schedulerBehavior = SchedulerBehavior.SILENT_FALLBACK, // assume worst case
    keystoreReliability = KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK,
    thermalZones = listOf("/sys/class/thermal/thermal_zone0/temp"),
    alsaTimingGapMs = 0,
    audioModeQuirks = emptySet(),
    backgroundRestrictionLevel = BackgroundRestrictionLevel.UNKNOWN,
    notificationCompat = NotificationCompatMode.A13_STANDARD,
    foregroundServiceTypeBundle = ForegroundServiceTypeBundle.MEDIA_PROJECTION_MICROPHONE,
    playProtectStance = PlayProtectStance.GPP_ENABLED_TOLERATED,
    packageQueryStrategy = PackageQueryStrategy.SPECIFIC_PACKAGES_ONLY,
)
```

**Important:** when running on an unknown device, the daemon will not crash but will not provide the full route-war behavior either. The phantom-headset-codec bypass is C22-specific; unknown devices fall back to "respect whatever the audio policy manager wants."

## The registry

```kotlin
// core/common/device/DeviceQuirkRegistry.kt

object DeviceQuirkRegistry {
    fun current(): DeviceQuirkProfile = when {
        Build.MANUFACTURER == "HMD Global" && Build.MODEL.startsWith("TA-1502") -> NokiaC22Profile
        // future profiles slot in here, ordered most-specific to least-specific
        else -> UnknownDeviceProfile
    }
}
```

The registry is consulted once during `VyzorixAppInitializer` startup (T+0s in `SYSTEM_MAP.md` §2) and the result is stored in a process-scoped singleton. Tests can override via a separate `setForTesting()` entry point.

## How to add a new device

If you need to support a new physical device:

1. **Catalog the quirks empirically.** Spend at least a week running the daemon on the device with the `UnknownDeviceProfile` baseline. Note which quirk fields have non-default behavior on this device.
2. **Create a new profile object** in `core/common/device/profiles/<DeviceName>Profile.kt`. Document the rationale for every non-default field in code comments AND in a per-device notes doc (e.g. `doc/SAMSUNG_A14_NOTES.md`).
3. **Add a `when` clause** to `DeviceQuirkRegistry.current()`. Order matters: most-specific first.
4. **Update the registry's tests** to assert that the new `when` clause returns the new profile for the expected `Build.MANUFACTURER` + `Build.MODEL` combination.
5. **Update `DaemonStatusAggregator`** if a new field requires a new dashboard indicator.
6. **Add an ADR** if any of the new fields don't exist on the schema yet — schema growth is an architectural decision worth recording.

## How to test

`DeviceQuirkRegistry.setForTesting(profile)` lets instrumented tests inject a synthetic profile. Example:

```kotlin
@Before
fun setUp() {
    DeviceQuirkRegistry.setForTesting(NokiaC22Profile)
}

@Test
fun `LatencyOptimizer expands chunk size on silent-fallback scheduler`() {
    // Profile already injected; LatencyOptimizer reads from registry
    val optimizer = LatencyOptimizer()
    assertThat(optimizer.chunkSize).isEqualTo(LARGE_CHUNK_SIZE)
}

@After
fun tearDown() {
    DeviceQuirkRegistry.clearTestOverride()
}
```

## What does NOT belong in a quirk profile

These belong elsewhere; do not add fields for them:

- **Per-user preferences** (volume, notification style, brightness) — those live in user settings, not device profile.
- **Per-session state** (currently-active app, current audio mode) — those live in runtime state, not profile.
- **Crypto keys or secrets** — those live in `KeystoreManager` / `DeviceSecretStore`, not profile.
- **Server URLs or build-config values** — those live in `BuildConfig` or `UpdateConfig`, not profile.

A field belongs in `DeviceQuirkProfile` only if (a) it varies across physical device models and (b) it's stable for the lifetime of the install (no per-session changes).

## References

- ADR-0008 — design rationale.
- `NOKIA_C22_NOTES.md` — implementation notes that feed into `NokiaC22Profile`.
- `SYSTEM_MAP.md` §2 — startup sequence shows registry consultation at T+0s.
