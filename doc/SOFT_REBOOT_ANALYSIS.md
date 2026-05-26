# SOFT_REBOOT_ANALYSIS.md — Diagnostic Black Box Strategy

## Objective

Diagnose and log system instability on a Nokia C22 (Android 13) where launching newly installed apps triggers a soft reboot (Zygote crash), without requiring root access or external debugging tools.

## The Problem

1. Zygote Process Crash: When certain apps launch, the Zygote process (which spawns all Android app processes) fails, causing a soft reboot.

2. No Direct Access: Android 13 blocks third-party apps from reading system logs, tombstones, or kernel panic data.

3. Daemon Survival: Your AccessibilityService and ForegroundService may survive the reboot or restart immediately after, making them the only diagnostic window.

## The "Black Box" Approach

Since we cannot read system internals, we treat the device as a black box and infer crashes by monitoring external symptoms:

- Uptime anomalies

- Service restart patterns

- Window transition failures

- Accessibility event silence

- Process death notifications

---

## Diagnostic Architecture

### Observers (Passive Monitoring)

| Component | Purpose | Method |
|-----------|---------|--------|
| AppLaunchObserver | Detects new app launches | UsageStatsManager MOVE_TO_FOREGROUND |
| WindowTransitionTracker | Watches UI state changes | AccessibilityEvent TYPE_WINDOWS_CHANGED |
| PackageStateObserver | Differentiates fresh vs known apps | Local isFirstRun tracking |
| SoftRebootPredictor | Identifies reboot patterns | SystemClock.uptimeMillis() monitoring |
| RendererFailureDetector | Detects UI thread deadlocks | Accessibility event timing gaps |

### Recorders (Data Persistence)

| Component | Purpose | Storage |
|-----------|---------|---------|
| LogStreamCollector | Aggregates all diagnostic events | In-memory buffer |
| RollingLogWriter | Writes events to rotating files | 2MB chunks in private storage |
| LastKnownStateDumper | Flight data recorder | last_state.json (continuously overwritten) |
| CrashTraceStore | Persists crash signatures | Indexed log files |
| RuntimeSessionIndexer | Tracks sessions chronologically | Session metadata database |

---

## Detection Strategies

### 1. Uptime Anomaly Detection (Primary Soft Reboot Signal)

Logic:

- Record SystemClock.uptimeMillis() every 10 seconds to a rolling buffer.

- If uptime suddenly drops (or resets to a low value), a reboot occurred.

- If uptime continues but your service restarts without a BOOT_COMPLETED broadcast, a Framework Crash (Soft Reboot) occurred.

Signature:

```
Event: Service Restart
Time Since Last Uptime Check: < 30 seconds
Uptime Gap: > 60 seconds
BOOT_COMPLETED Received: No
Classification: Soft Reboot / Zygote Crash
```

### 2. Window Flash Detection (Crash During Launch)

Logic:

- When AppLaunchObserver detects a new app entering foreground, start a 10-second timer.

- WindowTransitionTracker watches for TYPE_WINDOWS_CHANGED events.

- If a window appears and vanishes in < 500ms, the app crashed during initialization.

Signature:

```
Event: Package MOVE_TO_FOREGROUND
Window Appearance: Detected
Window Disappearance: < 500ms later
Accessibility Events from Package: 0
Classification: Flash Crash / Zygote Rejection
```

### 3. UI Thread Deadlock (Silent Hang Before Reboot)

Logic:

- If foreground app is active (verified via UsageStats) but no TYPE_WINDOW_CONTENT_CHANGED events are received for > 5 seconds, the UI thread is deadlocked.

- This often precedes a soft reboot by 10-30 seconds.

Signature:

```
Event: No Content Changes
Foreground Package: Active
Time Since Last Event: > 5 seconds
User Interaction: Detected but no UI response
Classification: UI Thread Deadlock
```

### 4. Accessibility Event Silence (System Hang)

Logic:

- The AccessibilityService should receive periodic events (clock updates, status bar changes, background refreshes).

- If no events of any type are received for > 15 seconds while the screen is on, the system is hanging.

Signature:

```
Event: Accessibility Silence
Screen State: On
Time Since Last Event: > 15 seconds
Daemon Health: Alive but blind
Classification: System Hang / Pre-Reboot
```

---

## Pattern Recognition Engine

### Crash Signature Matching

The SoftRebootPredictor maintains a database of "Crash Signatures" — patterns that consistently precede a reboot:

| Signature | Pattern | Confidence |
|-----------|---------|------------|
| SIG_01 | App Launch -> Window Flash -> Service Restart | High |
| SIG_02 | App Launch -> UI Deadlock -> Uptime Reset | High |
| SIG_03 | App Launch -> Accessibility Silence -> Framework Crash | Medium |
| SIG_04 | Background Activity -> Thermal Throttle -> Uptime Reset | Medium |
| SIG_05 | Media Playback -> Audio Route Change -> Service Restart | Low |

### Escalation Risk Score

The system calculates a risk score (0-100) based on recent events:

- Each Soft Reboot in last hour: +25 points

- Each Flash Crash in last 5 minutes: +15 points

- Each UI Deadlock in last 10 minutes: +10 points

- Each Accessibility Silence event: +5 points

- Points decay by 10% every 5 minutes

If Risk Score > 75:

- Log "CRITICAL: System Instability Detected"

- Disable non-essential modules (MediaProjection capture, heavy logging)

- Enter "Safe Mode" (only Speaker Force loop active)

- Notify user via persistent notification: "System unstable. Some features disabled."

---

## Log Bundling Strategy

### File Structure (Private Storage)

```
/data/data/com.vyzorix.audiorouter/files/diagnostics/
|-- session_index.json           # Master index of all sessions
|-- current_session.log          # Active log (rotates at 2MB)
|-- crash_bundle_20240523_143022.log  # Archived crash bundles
|-- crash_bundle_20240523_151200.log
|-- heartbeat_buffer.json        # Last known state (continuously updated)
```

### Bundle Contents

Each crash bundle file contains:

1. Header: Timestamp, Session ID, Classification

2. Event Timeline: Chronological list of observed events

3. Signature Match: Which crash signature was detected

4. Context Data: Foreground package, uptime, audio mode, route state

5. Daemon State: Service health, active modules, risk score

### Rotation Logic (LogFileRotator)

- Write to current_session.log continuously

- When file size > 2MB:

  1. Rename to crash_bundle_TIMESTAMP.log

  2. Create new current_session.log

  3. Update session_index.json with metadata

  4. Delete oldest bundles if total count > 10

- Sanitize all user-identifiable data before writing

---

## On-Device Verification (No PC Required)

Since ADB is not available, use these on-device methods to verify the diagnostic system:

### Method 1: In-App Diagnostic Activity

- Create a hidden diagnostic screen accessible via a specific tap pattern (e.g., tap app logo 5 times).

- Display:
  - Current Risk Score
  - Last detected crash signature
  - Uptime history
  - Active observers
  - Log bundle count and size

### Method 2: Notification-Based Status

- Persistent notification shows diagnostic state:
  - Green dot: System stable, all observers active
  - Yellow dot: Elevated risk score, monitoring closely
  - Red dot: Critical instability, safe mode active
  - Gray dot: Diagnostic system initializing

### Method 3: Audio Feedback Loop

- Use the SpeakerForceEngine to create a "diagnostic tone":
  - Play a 440Hz tone for 100ms
  - If tone plays through speaker: Route war is active, audio pipeline functional
  - If tone is silent: Audio pipeline blocked, likely post-crash state

### Method 4: Export Bundle via Share Intent

- User can trigger "Export Diagnostics" from settings

- System uses ACTION_SEND to bundle all log files into a single .zip

- User can share to email, cloud storage, or file manager

- No PC required, fully on-device

---

## Recovery Strategies

### 1. Post-Crash Resurrection

When the service restarts after a soft reboot:

1. Read last_state.json to determine crash context

2. Check if Risk Score was > 75 before crash

3. If yes, enter Safe Mode immediately (disable MediaProjection, heavy logging)

4. If no, resume normal operation but increase monitoring frequency

5. Log "Post-Crash Recovery" event with previous state data

### 2. App Blacklisting

If a specific package triggers crashes repeatedly:

1. Add package to DeviceQuirkRegistry blacklist

2. When UsageStats detects this package launching:
   - Log "Blacklisted app launching"
   - Temporarily disable non-essential modules
   - Increase heartbeat frequency to 2 seconds

3. Notify user: "App [X] may cause system instability. Some features paused."

### 3. Thermal Throttling Response

If DeviceThermalMonitor detects critical temperature:

1. Reduce MediaProjection capture sample rate (48kHz -> 44.1kHz -> 32kHz)

2. Disable heavy logging and forensics modules

3. Keep only SpeakerForceEngine and basic observers active

4. Log "Thermal Throttle: Reducing diagnostic load"

---

## Limitations (Stock Android 13)

| Capability | Status | Reason |
|------------|--------|--------|
| Direct logcat access | Blocked | READ_LOGS is signature-privileged |
| Tombstone reading | Blocked | /data/tombstones/ is system:system 700 |
| dumpsys access | Blocked | Requires DUMP permission (signature-level) |
| Kernel panic logs | Blocked | Requires root or recovery mode |
| System process monitoring | Partial | Can only observe own process and usage stats |
| Hardware crash detection | Inferred | Via uptime anomalies and service restarts |

## Summary

This strategy accepts that we cannot read system internals on stock Android 13. Instead, we build a comprehensive "black box" that infers crashes from external symptoms, bundles diagnostic data locally, and provides on-device verification methods. The goal is not to fix the Zygote crash, but to:

1. Identify which apps trigger it

2. Protect the daemon from being killed during the crash

3. Provide actionable diagnostic data for analysis

4. Maintain core audio routing functionality even during system instability