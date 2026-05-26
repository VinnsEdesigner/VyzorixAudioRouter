# DOC_2_ACCESSIBILITY_AND_AUTOMATION_GOVERNANCE.md — Accessibility Bindings, Screen Automation, and Safety Governors

## Document Purpose
This document is Part 2 of the 8-part Vyzorix System Mapping. It details the highly privileged `AccessibilityService` layers, non-interactive automated settings navigation engines, safety circuit breakers, and on-screen overlay toggles. This document serves as the implementation specification for hands-free background consent, boot recovery, and automation throttling.

---

# 1. System UI Event Processing and Automation Flow

The following mapping defines how a system-level window transition or dialog warning is intercepted, parsed, verified, and programmatically clicked by the Vyzorix Automation engine in under 100 milliseconds:

```text
               System UI Window State Transition (com.android.systemui)
                                      │
                                      ▼
                      [onAccessibilityEvent(event)]
                                      │
                                      ▼
                         RouterAccessibilityService
                                      │
                                      ▼
                          AccessibilityEventRouter
                                      │
                                      ▼
                         DialogRecognitionEngine
                                      │
                                      ├── Check: Active overlay or dialog?
                                      ▼
                           UiInteractionSnapshot
                                      │
                                      ├── Parse active Node Tree (Start Now / Consent)
                                      ▼
                          AutomationDecisionEngine
                                      │
            ┌─────────────────────────┴─────────────────────────┐
            │                                                   │
  Is User Active / Locked?                              Is Limit Exceeded?
  [HumanPresenceDetector]                             [AutomationRateLimiter]
            │                                                   │
            ▼ (Screen Unlocked, Idle)                           ▼ (Actions < Max limits)
   [AutomationSafetyGate]                             [AutomationCooldownPolicy]
            │                                                   │
            ▼ (Circuit Breaker: Stable)                         ▼ (Delay verified)
            └─────────────────────────┬─────────────────────────┘
                                      │
                                      ▼
                          AccessibilityGestureQueue
                                      │
                                      ▼
                      Simulated Programmatic Clicks
                     (AccessibilityService.dispatchGesture)
                                      │
                                      ▼
                 Token Granted / Reroute Complete (<100ms)
```

---

# 2. Submodule: `accessibility` (The Privileged Core)

The `accessibility` package implements the main `AccessibilityService` binder interfaces, manages dynamic capabilities, and acts as the headless daemon's boots-on-the-ground interface to System UI.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/
├── RouterAccessibilityService.kt
├── AccessibilityEventRouter.kt
├── PermissionScreenWatcher.kt
├── SettingsAutomation.kt
├── OverlayPermissionAutomator.kt
├── ProjectionPermissionAutomator.kt
├── AudioRouteWatcher.kt
├── UiRecoveryDaemon.kt
├── AccessibilityStateTracker.kt
├── AccessibilityConfigManager.kt
├── AccessibilityRecoveryHandler.kt
└── OverlayShortcutController.kt
```

### 2.1 `RouterAccessibilityService.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/RouterAccessibilityService.kt`
*   **Architectural Role**: The primary, highly privileged bootstrap entry point. When enabled, the OS binds this service and grants it system-level screen-reading capabilities.
*   **Operational Flow**:
    ```text
    onServiceConnected() ──► LauncherIconHider.nukeLauncherIcon()
                                 │
                                 ▼
                             VyzorixAppInitializer.execute()
                                 │
                                 ▼
                             PersistentAudioService.startForeground()
    ```
*   **Core APIs & State Dependencies**: Binds directly to `android.accessibilityservice.AccessibilityService`. Inherits the system-wide privileged binding context.
*   **Failure Boundaries & Escape Hatches**: If the process crashes, the OS restarts it automatically within 1 second. It saves its runtime logs directly to the SQLite databases before exiting.

---

### 2.2 `AccessibilityEventRouter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/AccessibilityEventRouter.kt`
*   **Architectural Role**: Central events distributor. It receives raw accessibility callbacks from the main service thread and forwards them to specialized watchers to avoid blocking the main execution path.
*   **Operational Flow**:
    ```text
    RouterAccessibilityService.onAccessibilityEvent(event)
                                 │
                                 ▼
                      AccessibilityEventRouter
                                 │
         ┌───────────────────────┼───────────────────────┐
         ▼                       ▼                       ▼
    [PermissionWatcher]   [TransitionTracker]    [AutomationEngine]
    ```
*   **Failure Boundaries**: If any sub-watcher crashes or hangs, the router catches the exception, logs it, and continues dispatching events to prevent the accessibility service from being marked unresponsive by the OS.

---

### 2.3 `PermissionScreenWatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/PermissionScreenWatcher.kt`
*   **Architectural Role**: Watches system permission overlays. It scans `TYPE_WINDOW_STATE_CHANGED` packages to identify system-level dialog overlays (e.g., package installers or media projection consent prompts).
*   **State Dependencies**: Relies on `AccessibilityNodeInfo` window package name matches.

---

### 2.4 `SettingsAutomation.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/SettingsAutomation.kt`
*   **Architectural Role**: Simulates navigation clicks inside settings screens. It traverses the node tree to find the "VyzorixAudioRouter" toggle button, clicks it, and dismisses the secondary OS confirmation dialog.
*   **Failure Boundaries**: If the target setting layout is modified by an OEM skin (such as Nokia Go's custom Settings UI), the class falls back to scrolling matching label searches.

---

### 2.5 `OverlayPermissionAutomator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/OverlayPermissionAutomator.kt`
*   **Architectural Role**: Automates the overlay permission consent. When `OverlayPermissionManager` triggers the system display-over-other-apps screen, this automator identifies the target switch node, toggles it, and navigates back.

---

### 2.6 `ProjectionPermissionAutomator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/ProjectionPermissionAutomator.kt`
*   **Architectural Role**: Automates `MediaProjection` dialog consent. It scans the layout node tree for system-level strings containing "Start Now" or "Start recording", and programmatically clicks them within 100ms.
*   **State Dependencies**: Intercepts package `com.android.systemui`.

---

### 2.7 `AudioRouteWatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/AudioRouteWatcher.kt`
*   **Architectural Role**: Passive route observer. It listens for `ACTION_HEADSET_PLUG` system broadcasts and monitors `AudioManager.getDevices()`. If it detects that the speaker output route has drifted, it signals the forcing engine to reassert control.

---

### 2.8 `UiRecoveryDaemon.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/UiRecoveryDaemon.kt`
*   **Architectural Role**: Re-launches crashed settings screens. If a system-level permission screen crashes or is closed before automation is complete, this daemon re-fires the corresponding intent to resume settings configuration.
*   **State Dependencies**: Relies on `SettingsAutomation` completion flags.

---

### 2.9 `AccessibilityStateTracker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/AccessibilityStateTracker.kt`
*   **Architectural Role**: Keeps track of active service states. It provides true/false indicators to other modules to verify if accessibility capabilities are alive.

---

### 2.10 `AccessibilityConfigManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/AccessibilityConfigManager.kt`
*   **Architectural Role**: Manages dynamic accessibility parameters. During high CPU or thermal stress, it dynamically disables unneeded accessibility flags (e.g., turning off node content scanning and keeping only window tracking active) to conserve resources.

---

### 2.11 `AccessibilityRecoveryHandler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/AccessibilityRecoveryHandler.kt`
*   **Architectural Role**: Handles accessibility service unbinds. On Nokia firmwares, background services may be stripped during reboots. This handler catches the unbind state and triggers the UI recovery daemon to open settings for re-enabling.

---

### 2.12 `OverlayShortcutController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/accessibility/OverlayShortcutController.kt`
*   **Architectural Role**: Manages the floating shortcut button. It renders a translucent, interactive on-screen button using `WindowManager` and `TYPE_APPLICATION_OVERLAY`. Clicks on this button instantly toggle `PersistentAudioService` routing modes.
*   **State Dependencies**: Requires the `SYSTEM_ALERT_WINDOW` permission.

---

# 3. Submodule: `automation` (The Safety Governors)

The `automation` submodule acts as a safety layer over the accessibility APIs, enforcing strict rate limits, backoff periods, and safety gates to prevent system lockups.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/
├── AutomationRateLimiter.kt
├── HumanPresenceDetector.kt
├── AutomationCooldownPolicy.kt
├── AutomationSafetyGate.kt
├── DialogRecognitionEngine.kt
├── AccessibilityGestureQueue.kt
├── AutomationDecisionEngine.kt
└── UiInteractionSnapshot.kt
```

### 3.1 `AutomationRateLimiter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/AutomationRateLimiter.kt`
*   **Architectural Role**: Limits the rate of automated gestures. It enforces a strict cap on maximum automated actions per minute (e.g., maximum 5 settings clicks per minute), preventing layout loops.

---

### 3.2 `HumanPresenceDetector.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/HumanPresenceDetector.kt`
*   **Architectural Role**: Evaluates user activity status. It queries the lockscreen keyguard state and watches `MotionEvent` inputs to verify if the user is actively using the phone before running background settings clicks. This prevents click simulations from interrupting manual user input.

---

### 3.3 `AutomationCooldownPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/AutomationCooldownPolicy.kt`
*   **Architectural Role**: Implements exponential backoffs. If a settings automation sequence fails or is interrupted, this policy forces a delay before retrying.
*   **Backoff Profile**:
    ```text
    1st failure ──► Delay 5 seconds
    2nd failure ──► Delay 30 seconds
    3rd failure ──► Delay 5 minutes
    ```

---

### 3.4 `AutomationSafetyGate.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/AutomationSafetyGate.kt`
*   **Architectural Role**: The final circuit breaker. If automation retries exceed safe thresholds or trigger layout errors, this gate completely disables simulated clicks, transitions the service to safe-mode fallback, and notifies the user.

---

### 3.5 `DialogRecognitionEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/DialogRecognitionEngine.kt`
*   **Architectural Role**: Parses layout node trees to identify dialog boxes. It scans for matching system labels and checks the structural hierarchy to ensure simulated clicks only execute on verified system buttons.

---

### 3.6 `AccessibilityGestureQueue.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/AccessibilityGestureQueue.kt`
*   **Architectural Role**: Manages simulated click gestures. It maps target screen coordinates and dispatches click paths sequentially, preventing coordinate collisions.
*   **State Dependencies**: Relies on `AccessibilityService.dispatchGesture()`.

---

### 3.7 `AutomationDecisionEngine.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/AutomationDecisionEngine.kt`
*   **Architectural Role**: Resolves whether automation is currently safe to execute. It evaluates inputs from `HumanPresenceDetector`, `AutomationRateLimiter`, and active window states to determine if the system is ready to proceed.

---

### 3.8 `UiInteractionSnapshot.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/automation/UiInteractionSnapshot.kt`
*   **Architectural Role**: Captures on-screen node layouts. It creates an in-memory representation of the active UI tree containing element coordinates and texts, validating target coordinates before click simulations run.
