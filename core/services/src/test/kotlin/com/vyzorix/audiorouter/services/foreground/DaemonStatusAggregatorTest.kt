package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import com.vyzorix.audiorouter.services.foreground.signals.SignalSource
import com.vyzorix.audiorouter.services.foreground.signals.SignalValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DaemonStatusAggregatorTest {

    private class FakeSignal(
        override val id: String,
        private val value: SignalValue,
    ) : SignalSource {
        override fun current(): SignalValue = value
    }

    private val ctx = object : DaemonStatusContextProvider {
        override fun daemonState(): DaemonState = DaemonState.RUNNING
        override fun routeState(): RouteState = RouteState.SPEAKER_FORCED
        override fun captureState(): CaptureState = CaptureState.ACTIVE
        override fun lastCommandAtEpochMs(): Long? = null
        override fun websocketConnected(): Boolean = true
    }

    private fun mkAggregator(sources: List<SignalSource>): DaemonStatusAggregator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return DaemonStatusAggregator(scope = scope, contextProvider = ctx, sources = sources)
    }

    @Test fun `empty sources collects STABLE risk`() {
        val a = mkAggregator(emptyList())
        val tick = a.collectOnce()
        assertEquals(RiskLevel.STABLE, tick.status.riskLevel)
    }

    @Test fun `all OK signals compose STABLE`() {
        val a = mkAggregator(listOf(
            FakeSignal("a", SignalValue(SignalSeverity.OK, "a", "", 0L)),
            FakeSignal("b", SignalValue(SignalSeverity.OK, "b", "", 0L)),
        ))
        assertEquals(RiskLevel.STABLE, a.collectOnce().status.riskLevel)
    }

    @Test fun `one WARN promotes ELEVATED`() {
        val a = mkAggregator(listOf(
            FakeSignal("a", SignalValue(SignalSeverity.WARN, "a", "", 0L)),
            FakeSignal("b", SignalValue(SignalSeverity.OK, "b", "", 0L)),
        ))
        assertEquals(RiskLevel.ELEVATED, a.collectOnce().status.riskLevel)
    }

    @Test fun `two WARNs promote HIGH`() {
        val a = mkAggregator(listOf(
            FakeSignal("a", SignalValue(SignalSeverity.WARN, "a", "", 0L)),
            FakeSignal("b", SignalValue(SignalSeverity.WARN, "b", "", 0L)),
        ))
        assertEquals(RiskLevel.HIGH, a.collectOnce().status.riskLevel)
    }

    @Test fun `any CRIT promotes CRITICAL`() {
        val a = mkAggregator(listOf(
            FakeSignal("a", SignalValue(SignalSeverity.OK, "a", "", 0L)),
            FakeSignal("b", SignalValue(SignalSeverity.CRIT, "b", "", 0L)),
        ))
        assertEquals(RiskLevel.CRITICAL, a.collectOnce().status.riskLevel)
    }

    @Test fun `signal exception is captured as UNKNOWN`() {
        val crashing = object : SignalSource {
            override val id: String = "crashy"
            override fun current(): SignalValue = throw IllegalStateException("oops")
        }
        val a = mkAggregator(listOf(crashing))
        val tick = a.collectOnce()
        assertEquals(SignalSeverity.UNKNOWN, tick.signals["crashy"]!!.severity)
    }

    @Test fun `addSource registers a new signal`() {
        val a = mkAggregator(emptyList())
        a.addSource(FakeSignal("x", SignalValue(SignalSeverity.OK, "x", "", 0L)))
        assertEquals(1, a.collectOnce().signals.size)
    }

    @Test fun `notes capture WARN and CRIT only`() {
        val a = mkAggregator(listOf(
            FakeSignal("ok", SignalValue(SignalSeverity.OK, "ok", "", 0L)),
            FakeSignal("warn", SignalValue(SignalSeverity.WARN, "warning_one", "", 0L)),
            FakeSignal("crit", SignalValue(SignalSeverity.CRIT, "critical_two", "", 0L)),
        ))
        val notes = a.collectOnce().status.notes
        assertEquals(2, notes.size)
    }
}
