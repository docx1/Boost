package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.HrActivityCalculator.HrZone
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.RECOVERY_WINDOW_MAX_MS
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.recoveryWindowMs
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Boost-endurance (2026-09-28). A long ride is zone 2 to 4 with almost no steps, broken by descents
 * and stops. These pin that it becomes one ENDURANCE state after 30 minutes, survives the breaks,
 * ends after 20 quiet minutes, and that its recovery window grows with its length.
 */
class EnduranceDetectorTest {

    private val t0 = 1_800_000_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    /** Steps the detector once a minute from [fromMin] until [toMin] inclusive at one zone and step count. */
    private fun run(start: EnduranceDetector.State, fromMin: Int, toMin: Int, zone: HrZone?, steps: Int? = 10): EnduranceDetector.State {
        var s = start
        for (m in fromMin..toMin) s = EnduranceDetector.step(s, min(m), zone, steps)
        return s
    }

    @Test fun `thirty minutes of zone 2 with few steps becomes endurance, not sooner`() {
        val at29 = run(EnduranceDetector.State(), 0, 29, HrZone.ZONE_2_LIGHT)
        assertThat(at29.active).isFalse()
        val at30 = EnduranceDetector.step(at29, min(30), HrZone.ZONE_2_LIGHT, 10)
        assertThat(at30.active).isTrue()
        assertThat(at30.activeSinceMs).isEqualTo(min(0))
    }

    @Test fun `zone 1 or walking does not qualify`() {
        assertThat(run(EnduranceDetector.State(), 0, 60, HrZone.ZONE_1_VERY_LIGHT).active).isFalse()
        assertThat(run(EnduranceDetector.State(), 0, 60, HrZone.ZONE_3_MODERATE, steps = 400).active).isFalse()
        assertThat(run(EnduranceDetector.State(), 0, 60, null).active).isFalse()
    }

    @Test fun `a dip of up to 10 minutes during entry does not reset the clock, a longer one does`() {
        // One-minute loop: the last qualifying cycle at 15 and the next at 26 are 11 minutes apart.
        var s = run(EnduranceDetector.State(), 0, 15, HrZone.ZONE_3_MODERATE)
        s = run(s, 16, 25, HrZone.ZONE_1_VERY_LIGHT)          // 10 min stop
        s = run(s, 26, 30, HrZone.ZONE_3_MODERATE)
        assertThat(s.active).isTrue()
        assertThat(s.activeSinceMs).isEqualTo(min(0))

        var r = run(EnduranceDetector.State(), 0, 15, HrZone.ZONE_3_MODERATE)
        r = run(r, 16, 27, HrZone.ZONE_1_VERY_LIGHT)          // 12 min stop
        r = run(r, 28, 40, HrZone.ZONE_3_MODERATE)
        assertThat(r.active).isFalse()
    }

    // ── Five-minute loop (DIP_TOLERANCE_MIN = 11) ──

    private val jitteredStepMs = 5 * 60_000L + 10_000L      // 5 min 10 s

    @Test fun `a five-minute loop with 10 s of jitter reaches endurance at 30 minutes`() {
        var s = EnduranceDetector.State()
        var n = 0
        while (!s.active) {
            s = EnduranceDetector.step(s, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
            n++
            assertThat(n).isAtMost(7)
        }
        // Cycles at 0, 5:10, ..., 31:00: the seventh is the first at least 30 min after the first.
        assertThat(n).isEqualTo(7)
        assertThat(s.activeSinceMs).isEqualTo(t0)
    }

    @Test fun `a five-minute loop with one missed cycle keeps the entry clock`() {
        var s = EnduranceDetector.State()
        for (n in 0..2) s = EnduranceDetector.step(s, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
        // Cycle 3 did not run; cycle 4 is 10 min 20 s after cycle 2.
        for (n in 4..6) s = EnduranceDetector.step(s, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
        assertThat(s.active).isTrue()
        assertThat(s.activeSinceMs).isEqualTo(t0)

        // A non-qualifying cycle (a burst of walking) in place of the missed one does the same.
        var w = EnduranceDetector.State()
        for (n in 0..2) w = EnduranceDetector.step(w, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
        w = EnduranceDetector.step(w, t0 + 3 * jitteredStepMs, HrZone.ZONE_2_LIGHT, 300)
        for (n in 4..6) w = EnduranceDetector.step(w, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
        assertThat(w.active).isTrue()
    }

    @Test fun `a five-minute loop with two non-qualifying cycles restarts the entry clock`() {
        var s = EnduranceDetector.State()
        for (n in 0..2) s = EnduranceDetector.step(s, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
        for (n in 3..4) s = EnduranceDetector.step(s, t0 + n * jitteredStepMs, HrZone.ZONE_1_VERY_LIGHT, 10)
        for (n in 5..9) s = EnduranceDetector.step(s, t0 + n * jitteredStepMs, HrZone.ZONE_2_LIGHT, 10)
        assertThat(s.active).isFalse()
        assertThat(s.candidateSinceMs).isEqualTo(t0 + 5 * jitteredStepMs)
    }

    @Test fun `once active it holds through a 15 minute stop and ends after 20 quiet minutes`() {
        var s = run(EnduranceDetector.State(), 0, 40, HrZone.ZONE_3_MODERATE)
        s = run(s, 41, 55, HrZone.ZONE_1_VERY_LIGHT, steps = 300)   // cafe stop, walking about
        assertThat(s.active).isTrue()
        s = run(s, 56, 90, HrZone.ZONE_2_LIGHT)
        assertThat(s.active).isTrue()
        assertThat(s.activeSinceMs).isEqualTo(min(0))
        s = run(s, 91, 109, HrZone.ZONE_1_VERY_LIGHT)
        assertThat(s.active).isTrue()
        s = EnduranceDetector.step(s, min(110), HrZone.ZONE_1_VERY_LIGHT, 10)
        assertThat(s.active).isFalse()
    }

    @Test fun `a long gap in stepping ends an active bout even if the next cycle qualifies`() {
        val s = run(EnduranceDetector.State(), 0, 60, HrZone.ZONE_3_MODERATE)
        val afterGap = EnduranceDetector.step(s, min(60 + 8 * 60), HrZone.ZONE_2_LIGHT, 10)
        assertThat(afterGap.active).isFalse()
        assertThat(afterGap.candidateSinceMs).isEqualTo(min(60 + 8 * 60))
    }

    @Test fun `recovery window is unchanged up to an hour and grows with the bout, capped at 12 h`() {
        val h = 3_600_000L
        assertThat(recoveryWindowMs(2.0, 1.0, 45)).isEqualTo(2 * h)
        assertThat(recoveryWindowMs(2.0, 1.0, 60)).isEqualTo(2 * h)
        assertThat(recoveryWindowMs(2.0, 1.0, 180)).isEqualTo(6 * h)
        assertThat(recoveryWindowMs(2.0, 1.0, 300)).isEqualTo(8 * h)       // length factor capped at 4
        assertThat(recoveryWindowMs(4.0, 1.5, 300)).isEqualTo(RECOVERY_WINDOW_MAX_MS)
        assertThat(recoveryWindowMs(2.0, 1.25, 30)).isEqualTo((2.5 * h).toLong())
    }

    // ── Dead step feed (2026-10-27) ──

    @Test fun `a dead step feed does not qualify, even at zone 3 for an hour`() {
        // Field replay: zone-2 walks and runs became endurance bouts when the feed read 0.
        assertThat(run(EnduranceDetector.State(), 0, 60, HrZone.ZONE_3_MODERATE, steps = null).active).isFalse()
        assertThat(EnduranceDetector.qualifies(HrZone.ZONE_2_LIGHT, null)).isFalse()
    }

    @Test fun `a feed that dies mid-ride ends the bout after 20 minutes`() {
        val riding = run(EnduranceDetector.State(), 0, 45, HrZone.ZONE_2_LIGHT)
        assertThat(riding.active).isTrue()
        assertThat(run(riding, 46, 64, HrZone.ZONE_2_LIGHT, steps = null).active).isTrue()
        assertThat(run(riding, 46, 65, HrZone.ZONE_2_LIGHT, steps = null).active).isFalse()
    }
}
