// DaemonStatusAggregator — Layer C of the ADR-0007 three-layer health
// stack.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md lines 605–611:
//     core/services/foreground/DaemonStatusAggregator.kt
//       "Layer C (ADR-0007): central aggregator collecting from Layer B
//        signals every 10s; produces immutable DaemonStatus model for
//        the dashboard. Reads: LivenessProbe, PipelineHealthChecker,
//        MemoryPressureSignal, ThermalSignal, ProjectionTokenSignal,
//        WebSocketConnectionSignal, SafeModeSignal. NO recovery logic —
//        RecoveryCoordinator subscribes to the output. Runs on
//        AppDispatchers.IO."
//
// Responsibilities:
//   1. Hold an ordered list of registered [SignalSource]s.
//   2. On every 10s tick, ask each source for its `current()`, fold the
//      values into an immutable [DaemonStatus] snapshot, and publish.
//   3. Compose the snapshot's [RiskLevel] from the worst-severity
//      band across sources, weighted by importance class (CRIT signals
//      always win; multiple WARNs escalate to HIGH).
//   4. NEVER take action. The aggregator publishes; subscribers (the
//      dashboard, RecoveryCoordinator, telemetry) act.
//
// Threading: the tick loop runs on Dispatchers.IO. Signal sources MUST
// be non-blocking; the aggregator does not parallelise the reads
// because the total set is small (≤10) and the latency budget is
// 10s/tick.

package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.common.model.DaemonStatus
import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import com.vyzorix.audiorouter.services.foreground.signals.SignalSource
import com.vyzorix.audiorouter.services.foreground.signals.SignalValue
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Source of context the aggregator needs in order to fill out
 * [DaemonStatus] fields that aren't signals. Implementations are pushed
 * by the wiring layer (e.g. [PersistentAudioService]).
 */
public interface DaemonStatusContextProvider {
    public fun daemonState(): DaemonState
    public fun routeState(): RouteState
    public fun captureState(): CaptureState
    public fun lastCommandAtEpochMs(): Long?
    public fun websocketConnected(): Boolean
}

/** Snapshot of a single aggregator tick. Includes the signals it read. */
public data class AggregatorTick(
    public val status: DaemonStatus,
    public val signals: Map<String, SignalValue>,
    public val tickEpochMs: Long,
)

/** Diagnostic snapshot. */
public data class DaemonStatusAggregatorSnapshot(
    public val ticks: Long,
    public val lastTickEpochMs: Long,
    public val lastRiskLevel: RiskLevel,
    public val registeredSignalIds: List<String>,
)

/**
 * Aggregator. Single-instance per service.
 *
 * @param sources ordered list of registered signal sources; the
 *   aggregator collects from each every tick. The list MAY be empty at
 *   construction time and later mutated via [addSource] /
 *   [removeSource].
 * @param tickIntervalMs base cadence. The aggregator slows to
 *   [thermalThrottleIntervalMs] when ThermalSignal is in WARN or worse.
 */
public class DaemonStatusAggregator(
    private val scope: CoroutineScope,
    private val contextProvider: DaemonStatusContextProvider,
    sources: List<SignalSource> = emptyList(),
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    private val thermalThrottleIntervalMs: Long = DEFAULT_THROTTLED_INTERVAL_MS,
    private val startEpochMs: Long = System.currentTimeMillis(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val sourceList: MutableList<SignalSource> = sources.toMutableList()
    private val running: AtomicBoolean = AtomicBoolean(false)
    private val ticks: AtomicLong = AtomicLong(0L)
    private val lastTickEpochMs: AtomicLong = AtomicLong(0L)
    private val lastRiskLevel: AtomicReference<RiskLevel> = AtomicReference(RiskLevel.STABLE)
    private val _status: MutableStateFlow<AggregatorTick> = MutableStateFlow(
        AggregatorTick(
            status = emptyStatus(),
            signals = emptyMap(),
            tickEpochMs = clock(),
        ),
    )
    private var job: Job? = null

    /** Flow that emits one [AggregatorTick] per refresh cycle. */
    public val statusFlow: StateFlow<AggregatorTick> = _status.asStateFlow()

    /** Register a new source. Safe to call after start. */
    public fun addSource(source: SignalSource) {
        synchronized(sourceList) { sourceList.add(source) }
    }

    /** Remove a source by id. */
    public fun removeSource(id: String) {
        synchronized(sourceList) {
            sourceList.removeAll { it.id == id }
        }
    }

    /** Start the tick loop. Idempotent. */
    public fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.IO) {
            DaemonLogger.get().info(TAG, "aggregator.start tickIntervalMs=$tickIntervalMs")
            while (running.get()) {
                val tick = collectOnce()
                _status.value = tick
                ticks.incrementAndGet()
                lastTickEpochMs.set(tick.tickEpochMs)
                lastRiskLevel.set(tick.status.riskLevel)
                val interval = nextInterval(tick.signals)
                delay(interval)
            }
            DaemonLogger.get().info(TAG, "aggregator.exited ticks=${ticks.get()}")
        }
    }

    /** Stop the tick loop. */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        job?.cancel()
        job = null
    }

    /** One-shot collection — useful for tests and for the dashboard to
     *  pull a fresh snapshot off-cycle. */
    public fun collectOnce(): AggregatorTick {
        val readSignals: Map<String, SignalValue> = synchronized(sourceList) {
            val snapshot = sourceList.toList()
            snapshot.associate { source ->
                val value = try {
                    source.current()
                } catch (t: Throwable) {
                    DaemonLogger.get().error(
                        TAG,
                        "aggregator.signal.threw id=${source.id} err=${t.javaClass.simpleName}",
                    )
                    SignalValue.unknown(
                        label = "${source.id} threw",
                        details = t.javaClass.simpleName,
                        readEpochMs = clock(),
                    )
                }
                source.id to value
            }
        }
        val now = clock()
        val risk = computeRisk(readSignals.values)
        val memMb = readSignals[MEMORY_ID]?.let { extractMemoryMb(it) } ?: 0
        val thermalC = readSignals[THERMAL_ID]?.let { extractThermalC(it) } ?: -1f
        val status = DaemonStatus(
            daemonState = contextProvider.daemonState(),
            routeState = contextProvider.routeState(),
            captureState = contextProvider.captureState(),
            riskLevel = risk,
            uptimeMs = now - startEpochMs,
            memoryMb = memMb,
            thermalC = thermalC,
            websocketConnected = contextProvider.websocketConnected(),
            lastCommandAtMs = contextProvider.lastCommandAtEpochMs(),
            notes = noteSignals(readSignals.values),
        )
        return AggregatorTick(
            status = status,
            signals = readSignals,
            tickEpochMs = now,
        )
    }

    /** Diagnostic snapshot. */
    public fun snapshot(): DaemonStatusAggregatorSnapshot {
        val ids = synchronized(sourceList) { sourceList.map { it.id } }
        return DaemonStatusAggregatorSnapshot(
            ticks = ticks.get(),
            lastTickEpochMs = lastTickEpochMs.get(),
            lastRiskLevel = lastRiskLevel.get(),
            registeredSignalIds = ids,
        )
    }

    private fun nextInterval(signals: Map<String, SignalValue>): Long {
        val thermal = signals[THERMAL_ID] ?: return tickIntervalMs
        return when (thermal.severity) {
            SignalSeverity.WARN, SignalSeverity.CRIT -> thermalThrottleIntervalMs
            else -> tickIntervalMs
        }
    }

    private fun computeRisk(values: Collection<SignalValue>): RiskLevel {
        if (values.isEmpty()) return RiskLevel.STABLE
        var crits = 0
        var warns = 0
        for (value in values) {
            when (value.severity) {
                SignalSeverity.CRIT -> crits++
                SignalSeverity.WARN -> warns++
                SignalSeverity.OK, SignalSeverity.UNKNOWN -> Unit
            }
        }
        return when {
            crits >= 1 -> RiskLevel.CRITICAL
            warns >= 2 -> RiskLevel.HIGH
            warns == 1 -> RiskLevel.ELEVATED
            else -> RiskLevel.STABLE
        }
    }

    private fun emptyStatus(): DaemonStatus = DaemonStatus(
        daemonState = DaemonState.INITIALIZING,
        routeState = RouteState.UNKNOWN,
        captureState = CaptureState.IDLE,
        riskLevel = RiskLevel.STABLE,
        uptimeMs = 0L,
        memoryMb = 0,
        thermalC = -1f,
        websocketConnected = false,
        lastCommandAtMs = null,
        notes = listOf("aggregator not yet ticked"),
    )

    private fun noteSignals(values: Collection<SignalValue>): List<String> =
        values.filter { it.severity == SignalSeverity.WARN || it.severity == SignalSeverity.CRIT }
            .map { "${it.severity}: ${it.label}" }

    /** Best-effort parse of the "{N}MB free" label produced by MemoryPressureSignal. */
    private fun extractMemoryMb(value: SignalValue): Int {
        val match = MEMORY_MB_REGEX.find(value.label) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    /** Thermal signal does not carry a temperature reading; report -1 as
     *  per the DaemonStatus contract ("-1 = unknown"). */
    @Suppress("UNUSED_PARAMETER")
    private fun extractThermalC(value: SignalValue): Float = -1f

    public companion object {
        public const val DEFAULT_TICK_INTERVAL_MS: Long = 10_000L
        public const val DEFAULT_THROTTLED_INTERVAL_MS: Long = 30_000L
        public const val MEMORY_ID: String = "memory_pressure"
        public const val THERMAL_ID: String = "thermal"
        private val MEMORY_MB_REGEX = Regex("(\\d+)MB free")
        private const val TAG: String = "DaemonStatusAggregator"
    }
}
