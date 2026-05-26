# DOC_6_MEMORY_PERFORMANCE_AND_HARDWARE_MONITORING.md — Low-RAM Adaptation, Thermal Control, and Hardware Profiling

## Document Purpose
This document is Part 6 of the 8-part Vyzorix System Mapping. It details the Nokia C2Go Edition low-resource profiles, `onTrimMemory` system callback interpreters, CPU load-balancers, hardware telemetry metrics, and platform-level audio timing modifications. This document serves as the implementation specification for preventing OS process reclamation and optimizing battery performance under heavy workloads.

---

# 1. Low-RAM System Memory Trim and Adaptation Flow

The following mapping outlines the progressive resource degradation steps executed by the memory coordinator when the OS broadcasts a low-resource or memory trim callback:

```text
                        SYSTEM ON_TRIM_MEMORY(LEVEL) CALLBACK
                                           │
                                           ▼
                                 ServiceTrimCoordinator
                                           │
                                           ▼
                                 MemoryClassProfiler
                                           │
                                           ▼
                                 LowRamModeController
                                           │
               ┌───────────────────────────┴───────────────────────────┐
               │                                                       │
     Trim Level >= MODERATE?                                 Trim Level < MODERATE?
               │                                                       │
               ▼ (YES: AGGRESSIVE REDUCTION)                           ▼ (NO: LIGHT CLEANUP)
         CacheBudgetManager                                   AllocationPressureMonitor
               │                                                       │
               ├── Shrink PCM JNI buffers (4MB -> 2MB)                 └── Prune oldest log list
               ├── Shed non-essential monitoring observers             
               ├── Disable diagnostic trace logging                    
               │                                                       
               ▼                                                       
       EmergencyMemoryReducer                                          
               │                                                       
               ├── Force GC run (System.gc())                          
               └── Reclaim native JNI heaps                            
```

---

# 2. Submodule: `memory` (The RAM Governors)

The `memory` package manages RAM profiling, handles low-memory signals, limits cache sizes, and executes extreme memory recovery routines under heavy workload stress.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/
├── MemoryClassProfiler.kt
├── LowRamModeController.kt
├── CacheBudgetManager.kt
├── ServiceTrimCoordinator.kt
├── NativeHeapWatcher.kt
├── AllocationPressureMonitor.kt
└── EmergencyMemoryReducer.kt
```

### 2.1 `MemoryClassProfiler.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/MemoryClassProfiler.kt`
*   **Architectural Role**: Low-RAM device profiler. It queries `ActivityManager.getMemoryClass()` and identifies if the device is a low-RAM build, configuring memory-conscious thresholds across the entire app.
*   **Core APIs**: Binds directly to `ActivityManager.isLowRamDevice()` APIs.

### 2.2 `LowRamModeController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/LowRamModeController.kt`
*   **Architectural Role**: Manages low-memory configurations. It deactivates unneeded tracking and diagnostic features when RAM pressure increases, dedicating available memory to routing.

### 2.3 `CacheBudgetManager.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/CacheBudgetManager.kt`
*   **Architectural Role**: Controls internal cache budgets, dynamically resizing log queues and in-memory trace databases.

### 2.4 `ServiceTrimCoordinator.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/ServiceTrimCoordinator.kt`
*   **Architectural Role**: Intercepts `onTrimMemory(level)` system callbacks. It interprets the severity of memory signals and commands the controllers to run corresponding resource reductions.
*   **Core APIs**: Implements `ComponentCallbacks2` and binds directly to `Application.registerComponentCallbacks`.

### 2.5 `NativeHeapWatcher.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/NativeHeapWatcher.kt`
*   **Architectural Role**: Monitors native memory usage by querying JNI allocations, logging any potential native memory leaks.

### 2.6 `AllocationPressureMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/AllocationPressureMonitor.kt`
*   **Architectural Role**: Monitors JVM object allocations, flagging allocation spikes that could trigger GC pauses.

### 2.7 `EmergencyMemoryReducer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/memory/EmergencyMemoryReducer.kt`
*   **Architectural Role**: Master memory recovery coordinator. When critical memory limits are hit, it forces garbage collection (`System.gc()`), clears native JNI buffers, and resets caches to prevent out-of-memory crashes.

---

# 3. Submodule: `performance` (Adaptive Schedulers)

The `performance` submodule dynamically balances CPU resources, adjusts polling rates, and scales back features under heavy workloads.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/performance/
├── AdaptiveSamplingController.kt
├── CpuLoadBalancer.kt
├── FeatureLoadShedding.kt
├── LightweightModeController.kt
└── ThermalMitigationPolicy.kt
```

### 3.1 `AdaptiveSamplingController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/performance/AdaptiveSamplingController.kt`
*   **Architectural Role**: Dynamically scales audio-routing polling intervals based on system load (e.g., dropping checks from 500ms to 2000ms when system load is high).

### 3.2 `CpuLoadBalancer.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/performance/CpuLoadBalancer.kt`
*   **Architectural Role**: CPU load balancer. It processes thread-load parameters and optimizes thread priorities to prevent CPU starvation on core audio threads.

### 3.3 `FeatureLoadShedding.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/performance/FeatureLoadShedding.kt`
*   **Architectural Role**: Automatically disables non-critical diagnostic observers under heavy processing loads.

### 3.4 `LightweightModeController.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/performance/LightweightModeController.kt`
*   **Architectural Role**: Manages the minimal operational mode, scaling back all background modules to dedicate CPU time to core routing when resources are extremely limited.

### 3.5 `ThermalMitigationPolicy.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/performance/ThermalMitigationPolicy.kt`
*   **Architectural Role**: Handles thermal mitigation, scaling back resource usage (e.g., dropping capture sample rates) when the device begins overheating.

---

# 4. Submodule: `monitoring` (Active Hardware Observers)

The `monitoring` package tracks device state changes, listens to connected peripherals, and monitors network changes.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/
├── HeadsetStateMonitor.kt
├── BluetoothRouteMonitor.kt
├── AudioFocusMonitor.kt
├── PlaybackStateMonitor.kt
├── DeviceThermalMonitor.kt
├── RuntimeMemoryMonitor.kt
├── ProcessHealthMonitor.kt
└── NetworkStateMonitor.kt
```

### 4.1 `HeadsetStateMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/HeadsetStateMonitor.kt`
*   **Architectural Role**: Directly monitors physical headphone jack insert states by registering native system listeners.

### 4.2 `BluetoothRouteMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/BluetoothRouteMonitor.kt`
*   **Architectural Role**: Monitors Bluetooth audio profile state changes, registering listeners for A2DP, SCO, and HFP connections.

### 4.3 `AudioFocusMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/AudioFocusMonitor.kt`
*   **Architectural Role**: Tracks active focus owners across the entire system, notifying other modules of changes.

### 4.4 `PlaybackStateMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/PlaybackStateMonitor.kt`
*   **Architectural Role**: Tracks active media playback states across all processes.

### 4.5 `DeviceThermalMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/DeviceThermalMonitor.kt`
*   **Architectural Role**: Monitors device temperatures by polling SoC thermal sensors and notifying other modules when limits are exceeded.

### 4.6 `RuntimeMemoryMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/RuntimeMemoryMonitor.kt`
*   **Architectural Role**: Tracks system-wide RAM metrics and raises alerts if available memory drops below critical thresholds.

### 4.7 `ProcessHealthMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/ProcessHealthMonitor.kt`
*   **Architectural Role**: Watches process health, tracking memory leaks and process crashes.

### 4.8 `NetworkStateMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/monitoring/NetworkStateMonitor.kt`
*   **Architectural Role**: Monitors network changes and checks internet connectivity before updates are run.

---

# 5. Submodule: `metrics` (Hardware Telemetries)

The `metrics` package logs hardware metrics and measures system performance.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/metrics/
├── AudioLatencyMetrics.kt
├── RouteSwitchMetrics.kt
├── CrashMetrics.kt
├── CapturePerformanceTracker.kt
└── BatteryImpactMonitor.kt
```

### 5.1 `AudioLatencyMetrics.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/metrics/AudioLatencyMetrics.kt`
*   **Architectural Role**: Measures and logs latency delays across the JNI capture-and-playback pipeline.

### 5.2 `RouteSwitchMetrics.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/metrics/RouteSwitchMetrics.kt`
*   **Architectural Role**: Logs route transition success rates and durations.

### 5.3 `CrashMetrics.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/metrics/CrashMetrics.kt`
*   **Architectural Role**: Tracks and logs process-level crashes.

### 5.4 `CapturePerformanceTracker.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/metrics/CapturePerformanceTracker.kt`
*   **Architectural Role**: Tracks and logs audio packet drop and stream jitter metrics.

### 5.5 `BatteryImpactMonitor.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/metrics/BatteryImpactMonitor.kt`
*   **Architectural Role**: Monitors battery status and approximates power usage under load.

---

# 6. Submodule: `oem` (Platform Workarounds)

The `oem` package implements device-specific timing adjustments, AudioManager patches, and HAL reset workarounds.

```text
core/services/src/main/kotlin/com/vyzorix/audiorouter/services/oem/
├── NokiaAudioWorkarounds.kt
├── UnisocPlatformTweaks.kt
├── VendorRouteResetter.kt
└── DeviceQuirkRegistry.kt
```

### 6.1 `NokiaAudioWorkarounds.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/oem/NokiaAudioWorkarounds.kt`
*   **Architectural Role**: Bypasses Nokia audio limitations. It implements retry routines for `AudioManager` calls, ensuring commands execute successfully even if blocked by background restrictions.

### 6.2 `UnisocPlatformTweaks.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/oem/UnisocPlatformTweaks.kt`
*   **Architectural Role**: Implements chip-specific adjustments. It tunes thread parameters and schedules timing gaps for the Unisoc SC9863A SoC.

### 6.3 `VendorRouteResetter.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/oem/VendorRouteResetter.kt`
*   **Architectural Role**: Exposes HAL reset routines, forcing a re-probe of the physical routing tables using specific intents.

### 6.4 `DeviceQuirkRegistry.kt`
*   **Path**: `core/services/src/main/kotlin/com/vyzorix/audiorouter/services/oem/DeviceQuirkRegistry.kt`
*   **Architectural Role**: Maintains a central registry of device-specific behaviors, helping apply the correct workarounds automatically during runtime.
