# NOKIA_C22_NOTES.md — Hardware-Specific Implementation Notes

## Document Purpose

The VyzorixAudioRouter daemon targets a very specific hardware deployment: the Nokia C22 (model TA-1502, Android 13, Unisoc SC9863A SoC). Several of the daemon's design decisions only make sense in the context of this exact silicon. This document captures the C22-specific quirks so that whoever implements the affected code paths (especially the native C++ layer) does not assume they are writing for "Android in general".

For broader cross-device latency considerations, see `LATENCY_TUNING.md` (forthcoming — for now, this document holds the entirety of the C22 hardware notes).

Cross-references:
- `SOFT_REBOOT_ANALYSIS.md` — the Nokia C22's soft-reboot failure mode that motivates this whole project.
- `MEDIA_PROJECTION_FLOW.md` §Battery & Soft Reboot Mitigation — how `IdleCaptureController` and thermal throttling address the C22-specific energy budget.
- `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §3.1 — software fallback in `KeystoreManager` for the C22's unreliable TEE.
- `BUILD_ORDER.md` — every layer references on-device verification on the C22 as its acceptance gate.

---

## 1. SoC Summary: Unisoc SC9863A

| Property | Value | Implication |
|----------|-------|-------------|
| CPU | 4× Cortex-A55 @ 1.6 GHz + 4× Cortex-A55 @ 1.2 GHz (octa-core, all small cores) | No big.LITTLE; no high-perf cluster to pin audio threads to. Thermal headroom is very tight. |
| GPU | IMG PowerVR GE8322 | Irrelevant for our use case; we do NOT render video. |
| RAM | 2 GB LPDDR3 | OOM-killer is aggressive. `oom_score_adj` matters. Foreground services must stay foreground or get culled. |
| Storage | 32 GB eMMC | Slow random IO. Room migrations and SQLCipher key derivation are noticeably slower than on flagship hardware. |
| TEE | Unisoc proprietary; hardware-backed Keystore is unreliable | `KeystoreManager` must implement a software fallback (see DOC_7 §3.1). |
| Audio HAL | Unisoc audio_hal (closed-source) | The headset-codec bypass quirk that prompted this project lives here. |
| Android | 13 (stock, no GMS Lite) | A13 background restrictions, Doze behavior, foreground service type enforcement all apply. |

---

## 2. The Thread Scheduler Trap (SCHED_FIFO Silent Fallback)

This is the single most important hardware-specific note for the native layer.

### 2.1 The Standard Assumption

On Qualcomm Snapdragon and MediaTek Helio/Dimensity SoCs, elevating an audio thread to real-time scheduling is straightforward:

```c
struct sched_param sp = { .sched_priority = 5 };
int rc = sched_setscheduler(0, SCHED_FIFO, &sp);
if (rc != 0) {
    // permission error or invalid priority — assume real-time elevation failed
    log_warn("SCHED_FIFO elevation failed: %s", strerror(errno));
} else {
    // SCHED_FIFO is active
    audio_thread_run();
}
```

This is the pattern in `core/audioengine/cpp/thread_priority_guard.cpp` today. **On Qualcomm and MediaTek silicon, a return code of 0 reliably means the policy was applied.**

### 2.2 What Goes Wrong on Unisoc SC9863A

On the Unisoc SC9863A, the kernel's scheduler permission model behaves differently. Even when the calling process has `CAP_SYS_NICE` and `sched_setscheduler()` returns 0, the policy may **silently downgrade to `SCHED_OTHER`** depending on the cgroup the thread happens to be in. This appears to be a side-effect of Unisoc's audio-policy cgroup configuration in the stock Nokia C22 build. It is NOT documented in any Unisoc developer material and is only observable empirically by reading back the policy after the elevation call.

The practical consequence: an audio thread that *thinks* it is running with SCHED_FIFO real-time priority is in fact running with SCHED_OTHER and is subject to normal CFS scheduling. Under load (e.g., a media app launches, the system applies an emergency thermal throttle, or another foreground app spikes CPU), the audio thread misses its deadlines, the ring buffer underruns, and audio glitches audibly.

### 2.3 Required Mitigation: Read-Back Check

`thread_priority_guard.cpp` MUST verify the scheduler assignment after calling `sched_setscheduler` rather than trusting the return code alone:

```c
// pseudocode — actual implementation must compile against bionic
struct sched_param sp = { .sched_priority = 5 };
int rc = sched_setscheduler(0, SCHED_FIFO, &sp);
if (rc != 0) {
    log_warn("SCHED_FIFO elevation failed at syscall: %s", strerror(errno));
    return PRIORITY_RESULT_SYSCALL_FAILED;
}

// READ-BACK CHECK — required for Unisoc SC9863A
int actual_policy = sched_getscheduler(0);
struct sched_param actual_sp;
sched_getparam(0, &actual_sp);

if (actual_policy != SCHED_FIFO) {
    log_warn(
        "SCHED_FIFO requested but actual policy is %d (priority=%d). "
        "Likely Unisoc SC9863A cgroup downgrade.",
        actual_policy, actual_sp.sched_priority
    );
    return PRIORITY_RESULT_SILENT_FALLBACK;
}

log_info("SCHED_FIFO confirmed at priority %d", actual_sp.sched_priority);
return PRIORITY_RESULT_REAL_TIME;
```

The Kotlin/JNI side MUST surface `PRIORITY_RESULT_SILENT_FALLBACK` distinctly from `PRIORITY_RESULT_REAL_TIME`. `DaemonStatusProvider` reports the result so the dashboard can show "Audio: real-time" vs "Audio: best-effort (Unisoc fallback)" — the C22 will commonly show the latter and that is acceptable, BUT only because we know about it.

### 2.4 Compensating for Silent Fallback

When the read-back check returns `PRIORITY_RESULT_SILENT_FALLBACK` we adapt rather than fail:

1. `LatencyOptimizer.kt` increases the capture chunk size (256 → 512 frames) to give the non-real-time thread more headroom per scheduling quantum.
2. `UnderrunRecovery.kt` lowers its underrun-trigger threshold so it pre-emptively grows the buffer before audible glitches.
3. `DaemonStatusProvider` reports the degraded mode so the dashboard shows the user what they're getting.
4. `UnisocPlatformTweaks.kt` applies any additional SoC-specific tunables (timing gaps between ALSA ioctl calls — empirically the Unisoc audio HAL benefits from ~2ms gaps; see comment in `VyzorixAudioRouter_RepoTree.md` line ~580).

The combination of these compensations is what keeps the C22 audio glitch-free in practice even though we never actually get SCHED_FIFO.

### 2.5 What NOT to Do

- Do NOT assume that "no syscall error" means "scheduler applied".
- Do NOT throw an exception or abort the daemon on `PRIORITY_RESULT_SILENT_FALLBACK` — the daemon must run on the C22 even without real-time priority. That is the whole point of the project.
- Do NOT log the plaintext fallback reason to the user-facing notification (it is confusing). Log it to `RuntimeEventTimeline` for forensics; surface only "best-effort" in the dashboard.
- Do NOT hard-code `SCHED_FIFO` requirements in production assertions. Use `BuildConfig.DEBUG`-only assertions if you want to catch this in debug builds on non-Unisoc hardware.

---

## 3. Audio HAL Quirks

### 3.1 The Headset-Codec Phantom Route

The motivating bug for this entire project: on the Nokia C22, the audio policy manager occasionally routes media-stream output to a non-existent / broken headset codec node, producing silence even though no headphones are connected. This is what `SpeakerForceEngine.kt` exists to correct — by holding `MODE_IN_COMMUNICATION` and `setSpeakerphoneOn(true)`, we force the policy manager to route through the VoIP communication path which bypasses the broken codec.

See `VOIP_ROUTE_FORCE.md` for the full explanation. The 500ms re-assertion loop in `RoutePersistenceDaemon` is calibrated specifically to the C22's policy-drift cadence; do NOT tune it down on this device.

### 3.2 ALSA ioctl Timing

The closed-source Unisoc audio_hal has been observed to deadlock if ALSA ioctls are issued too quickly in succession. `UnisocPlatformTweaks.kt` applies small (~2ms) delays between certain HAL calls. Whoever implements `UnisocPlatformTweaks.kt` MUST cross-reference `audio_clock_sync.cpp` and the comments in `VyzorixAudioRouter_RepoTree.md` for the exact ioctl sites that need the delay.

---

## 4. Memory & OOM Notes

- The 2GB RAM budget plus aggressive A13 OOM-killer means our foreground service must stay genuinely foreground (i.e., post its notification within the 5s window required by `startForeground`). `NotificationCompatBridge.kt` handles A13-specific notification flag requirements.
- `MemoryPressureCoordinator` and `RuntimeMemoryMonitor` use signals from `ProcessHealthMonitor` to detect oncoming memory pressure and pre-emptively drop non-essential caches (mainly the rolling diagnostic log buffer in `RollingLogWriter`).
- We do NOT use `largeHeap=true` in the manifest. On 2GB devices it does more harm than good (it makes the GC do more work without giving us materially more usable memory).

---

## 5. Battery & Thermal Notes

- The C22 has no high-performance cluster. Sustained CPU usage above ~30% triggers visible thermal throttling within a few minutes.
- `DeviceThermalMonitor.startPolling()` reads `/sys/class/thermal/thermal_zone*/temp` (the SoC TZ is typically zone 0 on the C22; verify on-device because the index is not stable across firmware revisions).
- The combination of `IdleCaptureController.pauseNativeReads()` (60% CPU reduction at idle) and thermal-driven sample-rate downgrade (48kHz → 44.1kHz under "Severe" throttle) is what keeps the C22 from hitting the soft-reboot threshold documented in `SOFT_REBOOT_ANALYSIS.md`.

---

## 6. Hardware-Backed Keystore Notes

The Unisoc TEE on the SC9863A is unreliable for hardware-backed Keystore operations. Symptoms observed in testing:

- `KeyStore.getInstance("AndroidKeyStore")` succeeds but subsequent `KeyGenerator.generateKey()` throws `ProviderException` with no recoverable cause.
- After a system update, previously-stored keys become unreadable (the TEE re-keys or invalidates its sealing key without notifying the keystore daemon).
- `KeyInfo.isInsideSecureHardware()` returns `true` even when subsequent operations fail.

`KeystoreManager.kt` mitigates this by:

1. Always wrapping Keystore operations in try/catch.
2. On any of the failure signatures above, falling back to a software-derived key (HKDF over install-time UUID + a static salt). The fallback path is documented in `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §3.1.
3. Surfacing the fallback state to `DaemonStatusProvider` so the dashboard shows "Crypto: hardware" vs "Crypto: software" — both states are operational; the software-fallback state is not a failure.

This fallback strategy applies to BOTH downstream consumers:
- The SQLCipher master passcode wrapping (database can still be encrypted-at-rest using the software-derived key).
- The C2 `command_secret` wrapping in `DeviceSecretStore` (per-device secret still bound to install, just not to silicon).

See `DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §1.1 and §3.9 for the full data-flow.

---

## 7. Implementation Checklist (Native Layer)

Before merging any change to `core/audioengine/cpp/`, verify on a physical Nokia C22:

- [ ] `thread_priority_guard.cpp` includes the read-back check from §2.3 above and surfaces `PRIORITY_RESULT_SILENT_FALLBACK` distinctly.
- [ ] `LatencyOptimizer.kt` reads the priority result and adapts chunk size accordingly.
- [ ] `UnisocPlatformTweaks.kt` applies the ALSA ioctl timing gaps (§3.2).
- [ ] `UnderrunRecovery.kt` is tuned for SCHED_OTHER worst-case latency, not SCHED_FIFO best-case.
- [ ] On-device test: play 30 minutes of YouTube + Spotify simultaneously. Underrun count remains <5.
- [ ] On-device test: trigger thermal throttle (run a CPU stress test in parallel). Audio degrades gracefully (sample rate drops) instead of silencing.
- [ ] On-device test: force-stop SystemUI to provoke `MediaProjection` death. `ProjectionDeathHandler` recovers within 2s.
- [ ] Logcat shows the actual scheduling policy assigned post-elevation on every audio thread.
