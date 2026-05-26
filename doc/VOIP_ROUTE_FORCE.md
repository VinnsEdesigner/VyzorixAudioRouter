# VOIP_ROUTE_FORCE.md — The "Route War" Strategy

## Objective

Force all audio output (Media, System, Notifications) to the **Built-in Speaker** on a Nokia C22 (Android 13) that is permanently stuck in `DEVICE_OUT_WIRED_HEADSET` mode due to a fried internal codec.

## The Problem: The "Phantom Headset" Lock

1. **Hardware Sensor Failure:** The audio codec reports `headset_state = 1` permanently.

2. **Audio Policy Manager:** Android's system-level `AudioPolicyManager` sees this and creates a "hard route" to the headset.

3. **API Immunity:**
   - `AudioManager.setSpeakerphoneOn(true)` is **ignored** in `MODE_NORMAL`.
   - `AudioTrack` configured with `USAGE_MEDIA` is silently redirected to the headset.
   - Even if you force the stream, the system "fights back" within milliseconds, resetting the route.

## The Loophole: `MODE_IN_COMMUNICATION`

Android grants special routing privileges to **Voice over IP (VoIP)** apps (like WhatsApp/Zoom).

- In `MODE_IN_COMMUNICATION`, the system prioritizes user-selected output devices over sensor detections.
- **The Exploit:** If we trick the system into thinking *every* sound is a VoIP call, we can override the headset sensor.

---

## Architecture of the "Route War"

### 1. The API Stack

To win the war, we must coordinate three specific API calls. If one is missing, the headset wins.

| Component | Configuration | Why it Matters |
|-----------|---------------|----------------|
| **AudioManager** | `.mode = MODE_IN_COMMUNICATION` | Unlocks the routing privilege layer. |
| **AudioManager** | `.isSpeakerphoneOn = true` | The actual command to route to speaker. |
| **AudioTrack** | `USAGE_VOICE_COMMUNICATION` | **CRITICAL.** If you play audio using `USAGE_MEDIA`, the system routes *that specific track* to the headset, regardless of the Mode. |

### 2. The Daemon Subsystems

#### `SpeakerForceEngine.kt` (The Enforcer)

- **Role:** Relentlessly applies the correct Mode and Route.
- **Behavior:** Runs a coroutine loop that checks the route state every 500ms. If the system tries to "heal" the route back to the headset (common on Unisoc chipsets), this engine slams it back to Speaker immediately.
- **Logic:**
  ```kotlin
  if (!audioManager.isSpeakerphoneOn) {
      audioManager.mode = MODE_IN_COMMUNICATION
      audioManager.isSpeakerphoneOn = true
  }
  ```

#### `AudioRouteWatcher.kt` (The Scout)

- **Role:** Monitors `ACTION_HEADSET_PLUG` and `AudioManager.getDevices()`.
- **Behavior:** Listens for any change in the active audio device list.
- **Alert:** If `DEVICE_OUT_SPEAKER` disappears from the active list, it triggers the `SpeakerForceEngine`.

#### `CommunicationRouter.kt` (The Pipeline)

- **Role:** Routes the captured audio from the MediaProjection engine into the SpeakerForce engine.
- **Behavior:** Takes raw PCM from the capture buffer, resamples it if needed, and writes it to an `AudioTrack` explicitly built with `AudioAttributes.USAGE_VOICE_COMMUNICATION`.

---

## The "War" Lifecycle (Execution Flow)

This flow begins immediately after `BootstrapCoordinator` finishes permission checks.

### Phase 1: Initialization (The Setup)

1. **Daemon Start:** `PersistentAudioService` starts.
2. **Sensor Check:** `AudioRouteWatcher` queries `getDevices(GET_DEVICES_OUTPUTS)`.
   - *Result:* `DEVICE_OUT_WIRED_HEADSET` is active. `DEVICE_OUT_SPEAKER` is inactive.
3. **Profile Load:** `NokiaC22DeviceProfile` is loaded. It enables "Aggressive Force Mode".

### Phase 2: Escalation (The Takeover)

1. **Mode Switch:** `SpeakerForceEngine` sets `AudioManager.mode = MODE_IN_COMMUNICATION`.
   - *Note:* This changes the system-wide audio profile. EQ settings may change.
2. **Route Force:** `SpeakerForceEngine` calls `setSpeakerphoneOn(true)`.
3. **Verification:** `AudioRouteWatcher` checks `getDevices()` again.
   - *Success:* `DEVICE_OUT_SPEAKER` appears in the list.
   - *Failure:* If it fails, we trigger `LegacyAudioFallback` (Volume ramping / Workarounds).

### Phase 3: The Loop (Maintaining Control)

- **The Heartbeat:** The `SpeakerForceEngine` enters a loop (delayed by 1000ms).
- **The Conflict:**
  - System Audio Policy tries to route a Notification sound to Headset.
  - System Audio Policy tries to route Spotify to Headset.
  - *Our Response:* Because the Global Mode is `COMMUNICATION`, the Audio Policy Manager consults the `setSpeakerphoneOn` flag. It is `true`. The System yields and routes to Speaker.
- **The Correction:** Every 10 seconds, the engine re-confirms the mode and state to prevent "Drift" (where the system slowly reverts settings).

### Phase 4: Audio Injection (The Payload)

1. **Capture:** `MediaProjection` captures the raw system audio mix (Spotify, YouTube, etc.).
2. **Processing:** Audio enters `AudioPipeline`.
3. **Playback:** `CommunicationRouter` creates an `AudioTrack`:
   ```kotlin
   val attributes = AudioAttributes.Builder()
       .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // <--- The Key
       .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
       .build()
   ```
4. **Output:** The `AudioTrack` writes PCM frames. Because of the Usage Hint and Global Mode, these frames are forced to the Speaker hardware.

---

## Edge Cases & Recovery Strategies

### 1. The "Silent" Failure (System kills VoIP mode)

- **Scenario:** Android 13 kills the `MODE_IN_COMMUNICATION` state to save battery, reverting to `MODE_NORMAL`.
- **Detection:** `AudioRouteWatcher` sees `isSpeakerphoneOn` return `false` despite our setting.
- **Recovery:** `WatchdogEscalationPolicy` triggers. The `SpeakerForceEngine` kills the current `AudioTrack`, re-asserts the Mode, and creates a fresh `AudioTrack`.

### 2. Focus Theft (Incoming Call / Alarm)

- **Scenario:** A real phone call comes in. Android demands `MODE_RINGTONE` or `MODE_IN_CALL`.
- **Strategy:** We *must* yield immediately to avoid a crash or ANR.
- **Recovery:** `AudioFocusMonitor` detects `AUDIOFOCUS_LOSS_TRANSIENT`. We pause the playback loop. Once the call ends (`AUDIOFOCUS_GAIN`), we immediately snap back to `MODE_IN_COMMUNICATION` and resume the loop.

### 3. The "Zygote" Crash (Soft Reboot)

- **Scenario:** Launching a heavy app triggers the Nokia C22 soft reboot.
- **Strategy:** The `LastKnownStateDumper` ensures that when the service restarts, it knows it was in "Force Mode". It skips the slow setup and jumps straight to Phase 2 (Escalation) to get audio back as fast as possible.

---

## On-Device Verification (No PC Required)

### Method 1: In-App Route State Display

- Create a hidden diagnostic screen accessible via a specific tap pattern (e.g., tap app logo 5 times).
- Display:
  - Current `AudioManager.mode` value
  - `isSpeakerphoneOn` state
  - Active output devices (from `getDevices(GET_DEVICES_OUTPUTS)`)
  - `SpeakerForceEngine` loop status (Running / Paused / Correcting)

### Method 2: Notification-Based Status

- Persistent notification shows routing state:
  - Green indicator: Speaker route active, VoIP mode engaged
  - Yellow indicator: System fighting back, correction in progress
  - Red indicator: Route lost, fallback mode active

### Method 3: Audio Feedback Loop

- Use the `SpeakerForceEngine` to create a "route verification tone":
  - Play a 440Hz tone for 100ms through `USAGE_VOICE_COMMUNICATION` AudioTrack
  - If tone plays through speaker: Route war is active, audio pipeline functional
  - If tone is silent: Audio pipeline blocked or routed to headset (failure)

### Method 4: Physical Confirmation

- Play a YouTube video or music track.
- If audio comes out of the bottom speaker instead of the dead headset jack, the route force is working.
- If audio is silent or comes from the headset jack, check the diagnostic screen for failure points.
