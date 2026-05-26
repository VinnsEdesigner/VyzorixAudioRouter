# NOTIFICATION_DASHBOARD.md — Read-Only Status Interface

## Objective

Provide a persistent, read-only status dashboard for the VyzorixAudioRouter daemon via the Android notification bar. This interface must display all critical system health, routing, and diagnostic information without launching any Activity or UI component that could trigger a soft reboot on the Nokia C22.

## Design Constraints

1.  **Zero Interaction:** Tapping the notification, the chevron, or any element within it must NOT open the app.

2.  **System UI Rendering:** The dashboard is drawn by `com.android.systemui`, isolating it from the app's Zygote crash issues.

3.  **Height Limitation:** Android imposes a maximum height on expanded notifications (~256dp to ~400dp depending on OEM/Version). Content exceeding this limit will be clipped unless a scrolling or cycling strategy is used.

4.  **Real-Time Updates:** Status must refresh automatically (every 10 seconds) without user input.

---

## Architecture: The "Heads-Up Dashboard"

### 1. Notification Style

We use `NotificationCompat.DecoratedCustomViewStyle()`. This allows us to replace the standard notification body with a custom `RemoteViews` layout while keeping the system header (Icon, Title, Time, and the Expand/Collapse Chevron).

### 2. RemoteViews Implementation

The layout is defined in XML but rendered remotely by the System UI process.

- **Supported Widgets:** `TextView`, `ProgressBar`, `ImageView`, `LinearLayout`, `RelativeLayout`, `FrameLayout`, `Chronometer`, `ScrollView` (with caveats), `ViewFlipper` (via animation).

- **Unsupported Widgets:** `Button`, `Switch`, `EditText`, `RecyclerView` (unless using specialized APIs not suitable here), Custom Views.

### 3. Layout Structure

The dashboard is divided into three priority tiers:

| Tier | Content | Visibility |
|------|---------|------------|
| **Tier 1** | Route Status (Mode, Speaker, Headset) | Always visible in collapsed/expanded state |
| **Tier 2** | Capture Engine (Projection, Buffer, Sample Rate) | Always visible in expanded state |
| **Tier 3** | System Health (Risk Score, Uptime, Thermal, Reboots) | Visible via scrolling or auto-cycling in expanded state |

---

## Scrolling & Overflow Strategy

Android's `ScrollView` inside `RemoteViews` is supported but **clipped** at the system's max notification height. To guarantee visibility of all data without interaction, we implement a **Hybrid Scrolling Approach**:

### Strategy A: Native ScrollView (Preferred)

We wrap the Tier 3 content in a `<ScrollView>` within the `RemoteViews`.

```xml
<ScrollView
    android:id="@+id/dashboard_scroll"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:fillViewport="true">
    
    <LinearLayout
        android:orientation="vertical"
        android:padding="8dp">
        <!-- Tier 3 Status Items -->
        <include layout="@layout/notification_section_health" />
        <include layout="@layout/notification_section_diagnostics" />
        <include layout="@layout/notification_section_oem_quirks" />
    </LinearLayout>
</ScrollView>
```

**Behavior:** The user can swipe up/down within the notification area to scroll.

**Limitation:** Some OEM skins (like Nokia's stock Android) may restrict touch gestures inside notifications or clip the scrollable area.

### Strategy B: Auto-Cycling Carousel (Fallback)

If scrolling is unreliable or the user prefers "set and forget" monitoring, we implement an **Auto-Cycling View** using `ViewSwitcher` or `ViewFlipper` logic driven by the background update loop.

```kotlin
// Pseudo-logic for Cycling
var currentViewIndex = 0
val views = listOf(view_health, view_diagnostics, view_oem)
scheduler.scheduleAtFixedRate({
    currentViewIndex = (currentViewIndex + 1) % views.size
    updateNotificationWithView(views[currentViewIndex])
}, 0, 5, TimeUnit.SECONDS) // Cycles every 5 seconds
```

**Behavior:** The notification body automatically rotates through different status sections every 5 seconds.

**Advantage:** Works on all devices, no touch gestures required, guarantees all data is seen over time.

### Strategy C: Marquee Text

For single-line fields that are too long (e.g., "Last Crash: com.example.app caused Zygote death at 14:30"), we enable Android's built-in marquee:

```xml
<TextView
    android:id="@+id/txt_last_crash"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:ellipsize="marquee"
    android:marqueeRepeatLimit="marquee_forever"
    android:singleLine="true"
    android:text="..." />
```

---

## Implementation Details

### 1. Non-Clickable Enforcement

To ensure the notification is **strictly read-only** and does not trigger app launches:

**A. Null ContentIntent**

```kotlin
val builder = NotificationCompat.Builder(context, CHANNEL_ID)
    .setContentTitle("VyzorixAudioRouter")
    .setContentIntent(null) // Prevents tap-to-open
    .setOngoing(true)       // Prevents swipe-to-dismiss
    ...
```

**B. No-Op PendingIntent (Compatibility)**

Some Android versions require a non-null `ContentIntent`. In this case, we attach a broadcast that performs no action:

```kotlin
val noOpIntent = Intent("com.vyzorix.audiorouter.ACTION_NO_OP")
val noOpPending = PendingIntent.getBroadcast(
    context, 0, noOpIntent,
    PendingIntent.FLAG_IMMUTABLE
)
builder.setContentIntent(noOpPending)
```

**C. Disable Clickable Children**

All `TextView` and `ProgressBar` elements in the `RemoteViews` layout are configured with:

```xml
android:clickable="false"
android:focusable="false"
android:longClickable="false"
```

### 2. Background Update Mechanism

The dashboard is updated by a coroutine loop running inside `PersistentAudioService`:

```kotlin
// In PersistentAudioService.kt
private fun startDashboardUpdates() {
    lifecycleScope.launch {
        while (isActive) {
            val status = DaemonStatusProvider.gatherCurrentStatus()
            val views = buildRemoteViews(status)
            notificationManager.notify(DASHBOARD_ID, buildNotification(views))
            delay(10_000L) // Update every 10 seconds
        }
    }
}
```

**Data Gathering:**

`DaemonStatusProvider` collects real-time data from:

- `AudioRouteWatcher` (Route state)

- `PlaybackCaptureEngine` (Buffer health, sample rate)

- `SoftRebootPredictor` (Risk score, uptime, reboot count)

- `DeviceThermalMonitor` (Temperature state)

- `LastKnownStateDumper` (Last crash context)

### 3. Visual State Indicators

Since the notification cannot use complex colors or icons (to save space and battery), we use **Text-Based State Markers**:

| State | Indicator | Example |
|-------|-----------|---------|
| Normal | `[OK]` | `Speaker: FORCED [OK]` |
| Warning | `[!!]` | `Risk Score: 65/100 [!!]` |
| Critical | `[XX]` | `Capture: STARVED [XX]` |
| Idle | `[--]` | `Reboots: 0 [--]` |

**Color Coding (If Supported):**

On Android 13+, `RemoteViews` supports `TextView.setTextColor()`. We apply subtle color coding:

- Green (`#4CAF50`): Normal
- Yellow (`#FFC107`): Warning
- Red (`#F44336`): Critical
- Gray (`#9E9E9E`): Idle/Unknown

---

## Layout Blueprint (XML Concept)

```xml
<!-- res/layout/notification_dashboard_expanded.xml -->
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:orientation="vertical"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="12dp">
    <!-- TIER 1: ROUTE STATUS -->
    <TextView
        android:id="@+id/txt_route_title"
        android:text="ROUTING ENGINE"
        android:textStyle="bold" />
    <TextView android:id="@+id/txt_route_mode" />
    <TextView android:id="@+id/txt_speaker_state" />
    <TextView android:id="@+id/txt_headset_state" />
    <TextView android:id="@+id/txt_audiotrack_usage" />
    <View android:layout_height="1dp" android:background="#333" />
    <!-- TIER 2: CAPTURE ENGINE -->
    <TextView
        android:id="@+id/txt_capture_title"
        android:text="MEDIA CAPTURE"
        android:textStyle="bold" />
    <TextView android:id="@+id/txt_projection_token" />
    <TextView android:id="@+id/txt_buffer_health" />
    <TextView android:id="@+id/txt_sample_rate" />
    <TextView android:id="@+id/txt_underruns" />
    <View android:layout_height="1dp" android:background="#333" />
    <!-- TIER 3: SYSTEM HEALTH (Scrollable/Cycling) -->
    <ScrollView
        android:id="@+id/dashboard_scroll"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">
        
        <LinearLayout
            android:orientation="vertical"
            android:paddingBottom="8dp">
            
            <TextView
                android:id="@+id/txt_health_title"
                android:text="SYSTEM HEALTH"
                android:textStyle="bold" />
            <TextView android:id="@+id/txt_risk_score" />
            <TextView android:id="@+id/txt_uptime" />
            <TextView android:id="@+id/txt_reboots_1h" />
            <TextView android:id="@+id/txt_thermal" />
            <TextView android:id="@+id/txt_safe_mode" />
            <TextView android:id="@+id/txt_last_crash" 
                      android:ellipsize="marquee" 
                      android:marqueeRepeatLimit="marquee_forever" />
        </LinearLayout>
    </ScrollView>
</LinearLayout>
```

---

## Edge Case Handling

### 1. Notification Clipping (OEM Limits)

Some devices enforce a hard max height (e.g., 256dp). If content is clipped:

- **Solution:** The `ScrollView` allows vertical dragging to see hidden content.
- **Fallback:** If scrolling is blocked, the background update loop switches to **Strategy B (Auto-Cycling)** automatically after detecting no scroll events for 10 seconds.

### 2. System UI Crash / Restart

If `com.android.systemui` crashes (rare but possible during a soft reboot):

- The notification disappears temporarily.
- `ServiceRecoveryManager` detects the loss via `NotificationManager.getActiveNotifications()`.
- It re-posts the notification within 2 seconds.

### 3. "Safe Mode" Activation

If `SoftRebootPredictor` raises the Risk Score > 75:

- The dashboard switches to a **Minimal View**.
- Non-critical sections (Capture, Diagnostics, OEM) are hidden.
- Only Tier 1 (Route) and a "SAFE MODE ACTIVE" warning are shown to conserve resources.

### 4. Thermal Throttling

If `DeviceThermalMonitor` detects high temperature:

- The dashboard adds a `[HOT]` indicator next to the Thermal status.
- The update frequency drops from 10s to 30s to reduce CPU load.
- A warning text is appended: "Thermal limit reached. Reducing logging."

---

## On-Device Verification (No PC Required)

Since the dashboard is the primary monitoring tool, users can verify system health using these methods:

### Method 1: The "Chevron Pull" Test

1. Pull down the notification shade.
2. Expand the `VyzorixAudioRouter` notification by tapping the chevron.
3. Verify:
   - All text fields are populated (no "--" unless truly unknown).
   - The ScrollView works (swipe up/down to see Tier 3).
   - No "Open App" prompt appears when tapping anywhere.

### Method 2: Audio Feedback Cross-Check

1. Play a YouTube video.
2. Observe the dashboard:
   - `Capture` should show "ACTIVE" and buffer health > 0%.
   - `Speaker` should show "FORCED [OK]".
3. If audio plays through speaker and dashboard confirms, the pipeline is fully functional.

### Method 3: Risk Score Monitoring

1. Open a "known bad" app (one that triggers soft reboots).
2. Watch the `Risk Score` field on the dashboard.
3. If it climbs past 50, the system is detecting instability.
4. If the dashboard disappears and reappears after a few seconds, a soft reboot occurred and the daemon recovered.

### Method 4: Manual Refresh Trigger

1. Tap the notification (configured as no-op broadcast).
2. The broadcast receiver forces an immediate status refresh and updates the notification content.
3. Verify the timestamp/uptime updates without launching the app.

---

## Summary

The Notification Dashboard provides a **safe, persistent, and comprehensive** view of the daemon's health without risking system crashes. By using `RemoteViews` with a hybrid scrolling/cycling strategy, we bypass Android's height limitations. By disabling all `PendingIntent` triggers, we ensure the dashboard is strictly read-only. This is the primary diagnostic and monitoring interface for the Nokia C22 "Route War" and "Soft Reboot" mitigation strategies.