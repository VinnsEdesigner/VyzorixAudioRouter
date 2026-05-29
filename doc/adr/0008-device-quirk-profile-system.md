# ADR-0008: `DeviceQuirkProfile` runtime abstraction over hardcoded Nokia C22 logic

## Status
Accepted

## Context

The Nokia C22 (Unisoc SC9863A, Android 13) has several hardware-specific behaviors that the daemon must adapt to. These include:

- SCHED_FIFO silent fallback in the Unisoc kernel scheduler (see `NOKIA_C22_NOTES.md` §2).
- Unreliable Hardware-Backed Keystore TEE (see DOC_7 §3.1).
- ALSA ioctl timing gaps required by the closed-source Unisoc audio HAL (see `NOKIA_C22_NOTES.md` §3.2).
- Headset-codec phantom routing requiring `MODE_IN_COMMUNICATION` (see `VOIP_ROUTE_FORCE.md`).
- Specific `/sys/class/thermal/thermal_zone*` indices that vary across firmware revisions.
- A13 background restriction enforcement patterns.

In the initial design, these were hardcoded throughout the codebase:

```kotlin
// scattered through native + Kotlin code
if (Build.MANUFACTURER == "HMD Global" && Build.MODEL.startsWith("TA-1502")) {
    // apply Unisoc tweak
}
```

This works for one device. It does not survive any of these scenarios:

- The C22 becomes unavailable and we have to support a different device with similar hardware bugs.
- A new firmware revision changes thermal zone indices.
- We test the daemon on a non-C22 device (e.g. for development on the operator's other phone) and it crashes because there's no fallback path.
- A second person joins the project and tries to understand which behaviors are "the design" and which are "C22 workarounds."

## Decision

Introduce a runtime `DeviceQuirkProfile` data class that captures all device-specific knobs as data, and a `DeviceQuirkRegistry` that selects the active profile at startup.

```kotlin
data class DeviceQuirkProfile(
    val deviceClass: String,                       // "nokia_c22", "future_x", "unknown"
    val socFamily: SocFamily,                      // UNISOC_SC9863A, MEDIATEK, QUALCOMM, UNKNOWN
    val schedulerBehavior: SchedulerBehavior,
        // RELIABLE_SCHED_FIFO, SILENT_FALLBACK, KNOWN_DEGRADED
    val keystoreReliability: KeystoreReliability,
        // RELIABLE, UNRELIABLE_USE_SOFTWARE_FALLBACK
    val thermalZones: List<String>,                // sysfs paths, ordered by preference
    val alsaTimingGapMs: Int,                      // 0 = no gap, 2 = Unisoc HAL needs 2ms
    val audioModeQuirks: Set<AudioModeQuirk>,      // PHANTOM_HEADSET_ROUTE, MODE_DROPS_ON_DOZE
    val backgroundRestrictionLevel: BackgroundRestrictionLevel,
    val notificationCompat: NotificationCompatMode,
    // ...add fields as new quirks are discovered
)

object DeviceQuirkRegistry {
    fun current(): DeviceQuirkProfile = when {
        Build.MANUFACTURER == "HMD Global" && Build.MODEL.startsWith("TA-1502") -> NokiaC22Profile
        // future profiles slot in here
        else -> UnknownDeviceProfile
    }
}
```

Each profile is a constant object (e.g. `NokiaC22Profile`, `UnknownDeviceProfile`). Adding a device = adding a profile + a `when` clause.

`UnknownDeviceProfile` uses conservative defaults: SCHED_FIFO assumed unreliable, Keystore assumed reliable but failures fall back to software, no ALSA timing gap, full thermal monitoring, etc. The daemon must not crash on an unknown device — it should run degraded but functional.

## Alternatives Considered

### Continue with scattered `if (isNokiaC22)` checks

Rejected because:
- Cannot be unit-tested in isolation (the device identity is global state).
- Cannot be overridden in tests (we can't make the test environment pretend to be a C22).
- Quirk decisions are tangled with business logic; refactors require touching every call site.

### Compile-time `BuildConfig` flag

Considered: ship a `c22-only` build variant with hardcoded behavior. Rejected because:
- Doubles the build matrix.
- Doesn't help if a single build needs to run on multiple devices (which becomes true the moment we support a second device).
- Hides the quirk decisions from runtime introspection — `DaemonStatusAggregator` cannot report "this device is in degraded scheduler mode" if the decision was a compile-time `#ifdef`.

### Inheritance-based polymorphism

Considered: define an abstract `DeviceAdapter` and override per-device. Rejected because:
- Quirks are mostly data, not behavior. A data-class profile is the right shape.
- Inheritance forces every quirk into the abstract base, even if only one device exhibits it. Adding a quirk means modifying the base class, which ripples across all subclasses.
- Profiles can be composed (e.g. "Unisoc family quirks + C22-specific overrides") if we add a `parent: DeviceQuirkProfile?` field later. Inheritance pre-commits to one hierarchy.

### Off-the-shelf device-info library

Considered libraries like Android's `Build` + community device databases (e.g. WURFL, DeviceAtlas). Rejected because:
- They identify devices; they do not characterize our specific quirks.
- We are the authority on what "SCHED_FIFO silent fallback on Unisoc" means for our daemon. No external library encodes that.

## Consequences

**Locked in:**
- All device-specific behavior must flow through `DeviceQuirkProfile`. No `if (isNokiaC22)` checks scattered through the codebase.
- New device support = new profile, not new code.
- `DeviceQuirkProfile` is a data class with no behavior; behavior lives in the classes that read the profile (e.g. `LatencyOptimizer` reads `profile.schedulerBehavior` and adapts chunk size accordingly).
- `DaemonStatusAggregator` includes the active profile name in its status output, so the dashboard always shows which device the daemon thinks it's running on.

**Closed off:**
- Compile-time conditional behavior. All quirks are runtime data.

**Opened up:**
- Testing: tests can inject a mock `DeviceQuirkProfile` (e.g. `TestProfile.fakeQuirks(scheduler = RELIABLE_SCHED_FIFO)`) to exercise non-C22 code paths.
- Future device support: adding a second device is mechanical.
- Forensic clarity: when a soft-reboot happens, the dump includes the active profile, making the bug report self-contained.

## References

- `doc/DEVICE_QUIRK_PROFILES.md` — full schema documentation and process for adding a new device.
- `doc/NOKIA_C22_NOTES.md` — refactored to describe `NokiaC22Profile` as data, with rationale for each field.
- `doc/SYSTEM_MAP.md` — `DeviceQuirkRegistry` shown in the startup sequence at T+0s.
