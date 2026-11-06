package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The post-exercise transition tracking and recovery window ([stepRecoveryWindow]), the endurance
 * overlay on the activity classification ([applyEndurance]) and the pre-meal suppression
 * ([preMealSuppression]), driven cycle by cycle at a five-minute cadence. Defaults are the shipped
 * settings: a 2 h window, scale 0.5, a 20 min minimum bout.
 */
/** One bout of [state] from 0 to 30 min with recovery on, ended at 30 min. Shared by the recovery tests. */
internal fun endedBout(state: String, recoveryHours: Double, recoveryScale: Double): RecoveryTransition {
    fun at(ms: Long, s: String) = RecoveryInputs(ms, s, null, true, false, 0, recoveryHours, recoveryScale)
    val started = stepRecoveryWindow(RecoveryWindowState(), at(0L, state)).state
    return stepRecoveryWindow(started, at(30 * 60_000L, "normal"))
}

class RecoveryWindowTransitionTest {

    private val min = 60_000L
    private val hour = 60 * min

    private fun inputs(
        nowMs: Long,
        state: String,
        enduranceSinceMs: Long? = null,
        recoveryEnabled: Boolean = true,
        enduranceTracking: Boolean = true,
    ) = RecoveryInputs(
        nowMs = nowMs,
        activityState = state,
        enduranceActiveSinceMs = enduranceSinceMs,
        recoveryEnabled = recoveryEnabled,
        enduranceTracking = enduranceTracking,
        minDurationMin = 20,
        recoveryHours = 2.0,
        recoveryScale = 0.5,
    )

    /** Runs [state] every five minutes from [fromMin] (inclusive) to [toMin] (exclusive). */
    private fun RecoveryWindowState.run(
        fromMin: Int, toMin: Int, state: String,
        enduranceSinceMin: Int? = null, recoveryEnabled: Boolean = true,
    ): RecoveryWindowState {
        var s = this
        for (m in fromMin until toMin step 5) {
            s = stepRecoveryWindow(
                s, inputs(m * min, state, enduranceSinceMin?.let { it * min }, recoveryEnabled)
            ).state
        }
        return s
    }

    private fun RecoveryWindowState.at(m: Int, state: String, recoveryEnabled: Boolean = true) =
        stepRecoveryWindow(this, inputs(m * min, state, recoveryEnabled = recoveryEnabled))

    // ── Ordinary exercise, unchanged by the endurance work ──

    @Test fun `a 30 min walk opens a 2 h window at scale 0_5 from its end`() {
        val s = RecoveryWindowState().run(0, 30, "ACTIVE")
        val t = s.at(30, "normal")
        assertThat(t.endedAfterMin).isEqualTo(30)
        assertThat(t.opened).isEqualTo(RecoveryOpened(2 * hour, 0.0))
        assertThat(t.state.windowEndMs).isEqualTo(30 * min + 2 * hour)
        assertThat(t.state.scale).isEqualTo(0.5)
        assertThat(t.state.fromEndurance).isFalse()
    }

    @Test fun `resistance opens 3 h with a 10 mg_dL offset and scale 0_6`() {
        val t = RecoveryWindowState().run(0, 30, "RESISTANCE").at(30, "normal")
        assertThat(t.opened).isEqualTo(RecoveryOpened(3 * hour, 10.0))
        assertThat(t.state.scale).isWithin(1e-9).of(0.6)
    }

    @Test fun `a bout shorter than the minimum opens nothing`() {
        val t = RecoveryWindowState().run(0, 15, "ACTIVE").at(15, "normal")
        assertThat(t.endedAfterMin).isEqualTo(15)
        assertThat(t.opened).isNull()
        assertThat(t.state.windowEndMs).isEqualTo(0L)
    }

    @Test fun `with recovery off, ordinary exercise opens no window`() {
        val t = RecoveryWindowState().run(0, 60, "ACTIVE", recoveryEnabled = false)
            .at(60, "normal", recoveryEnabled = false)
        assertThat(t.opened).isNull()
        assertThat(t.state.windowEndMs).isEqualTo(0L)
    }

    @Test fun `with recovery and endurance tracking both off, nothing is tracked`() {
        val start = RecoveryWindowState()
        val t = stepRecoveryWindow(start, inputs(0, "ACTIVE", recoveryEnabled = false, enduranceTracking = false))
        assertThat(t.state).isEqualTo(start)
        assertThat(t.started).isFalse()
    }

    @Test fun `stress does not start a bout`() {
        val t = RecoveryWindowState().at(0, "STRESS")
        assertThat(t.started).isFalse()
        assertThat(t.state.wasExerciseActive).isFalse()
    }

    @Test fun `type changes within an ordinary bout take the latest type`() {
        val s = RecoveryWindowState().run(0, 30, "ACTIVE").run(30, 60, "VIGOROUS_AEROBIC")
        assertThat(s.boutState).isEqualTo("VIGOROUS_AEROBIC")
        val t = s.at(60, "normal")
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
    }

    // ── Endurance ──

    @Test fun `an endurance bout is dated from its first qualifying cycle and opens a window with recovery off`() {
        // Heart rate qualified from minute 0; the state was declared at minute 30; the ride ends at 180.
        val s = RecoveryWindowState().run(30, 180, "ENDURANCE", enduranceSinceMin = 0, recoveryEnabled = false)
        assertThat(s.exerciseStartMs).isEqualTo(0L)
        val t = s.at(180, "normal", recoveryEnabled = false)
        assertThat(t.endedAfterMin).isEqualTo(180)
        assertThat(t.opened!!.windowMs).isEqualTo(6 * hour)
        assertThat(t.state.fromEndurance).isTrue()
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
    }

    // Fix 1: ACTIVE used to overwrite ENDURANCE while the bout went on. The bout type still follows the
    // latest state; a separate flag records that ENDURANCE was seen.

    @Test fun `a ride that ends in walking keeps its endurance window with recovery off`() {
        // Ride from minute 0 (declared at 30) to 120, then 40 min of walking classified ACTIVE.
        val s = RecoveryWindowState()
            .run(30, 120, "ENDURANCE", enduranceSinceMin = 0, recoveryEnabled = false)
            .run(120, 160, "ACTIVE", recoveryEnabled = false)
        assertThat(s.boutState).isEqualTo("ACTIVE")
        assertThat(s.sawEndurance).isTrue()
        val t = s.at(160, "normal", recoveryEnabled = false)
        assertThat(t.opened).isNotNull()
        assertThat(t.state.fromEndurance).isTrue()
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
        // 160 min bout: 2 h times 160/60.
        assertThat(t.opened!!.windowMs).isEqualTo((2.0 * hour * 160 / 60).toLong())
    }

    @Test fun `a walk after a finished ride is a new bout of its own type`() {
        val s = RecoveryWindowState()
            .run(30, 120, "ENDURANCE", enduranceSinceMin = 0)
            .run(120, 130, "normal")
            .run(130, 160, "ACTIVE")
        assertThat(s.boutState).isEqualTo("ACTIVE")
        assertThat(s.sawEndurance).isFalse()
    }

    @Test fun `a ride that ends in a run keeps the vigorous window and the endurance origin`() {
        // Ride from 0 (declared at 30) to 150, then a 30 min run: a 3 h bout ending as VIGOROUS_AEROBIC.
        // 2 h x 1.25 x 3 = 7.5 h at 0.5 x min(0.8, 0.8) = 0.4, no offset.
        val s = RecoveryWindowState()
            .run(30, 150, "ENDURANCE", enduranceSinceMin = 0, recoveryEnabled = false)
            .run(150, 180, "VIGOROUS_AEROBIC", recoveryEnabled = false)
        val t = s.at(180, "normal", recoveryEnabled = false)
        assertThat(t.endedAfterMin).isEqualTo(180)
        assertThat(t.opened).isEqualTo(RecoveryOpened((7.5 * hour).toLong(), 0.0))
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
        assertThat(t.state.fromEndurance).isTrue()
    }

    @Test fun `a ride that ends in light aerobic work keeps the endurance window and scale`() {
        // LIGHT_AEROBIC alone would give 0.5 x the window and 1.4 x the scale.
        val s = RecoveryWindowState()
            .run(30, 150, "ENDURANCE", enduranceSinceMin = 0)
            .run(150, 180, "LIGHT_AEROBIC")
        val t = s.at(180, "normal")
        assertThat(t.opened).isEqualTo(RecoveryOpened(6 * hour, 0.0))
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
    }

    @Test fun `a ride that ends in weights keeps the resistance window and offset at the endurance scale`() {
        // 3 h bout ending as RESISTANCE: 2 h x 1.5 x 3 = 9 h, +10 mg/dL, scale 0.5 x min(1.2, 0.8) = 0.4.
        val s = RecoveryWindowState()
            .run(30, 150, "ENDURANCE", enduranceSinceMin = 0)
            .run(150, 180, "RESISTANCE")
        val t = s.at(180, "normal")
        assertThat(t.opened).isEqualTo(RecoveryOpened(9 * hour, 10.0))
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
        assertThat(t.state.fromEndurance).isTrue()
    }

    // Fix 2: a later bout used to replace the open window with its own.

    @Test fun `a short walk after a long ride does not shorten or weaken the ride's window`() {
        // Four-hour ride (heart rate from 0, ends at 240): 8 h window to minute 720, scale 0.4.
        val afterRide = RecoveryWindowState()
            .run(30, 240, "ENDURANCE", enduranceSinceMin = 0)
            .run(240, 300, "normal")
        assertThat(afterRide.windowEndMs).isEqualTo(720 * min)
        // A 30 min walk from 300 to 330 would on its own open 2 h, to 450.
        val t = afterRide.run(300, 330, "ACTIVE").at(330, "normal")
        assertThat(t.opened).isNotNull()
        assertThat(t.state.windowEndMs).isEqualTo(720 * min)
        assertThat(t.state.fromEndurance).isTrue()
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
    }

    @Test fun `the endurance origin holds with recovery off after a later walk`() {
        val afterRide = RecoveryWindowState()
            .run(30, 240, "ENDURANCE", enduranceSinceMin = 0, recoveryEnabled = false)
            .run(240, 300, "normal", recoveryEnabled = false)
        val t = afterRide.run(300, 330, "ACTIVE", recoveryEnabled = false).at(330, "normal", recoveryEnabled = false)
        assertThat(t.state.windowEndMs).isEqualTo(720 * min)
        assertThat(t.state.fromEndurance).isTrue()
    }

    @Test fun `a later bout extends an open window and keeps the stronger scale`() {
        // Vigorous 0 to 30: 150 min window to 180, scale 0.4. Walk 60 to 120: 2 h, to 240, scale 0.5.
        val s = RecoveryWindowState().run(0, 30, "VIGOROUS_AEROBIC").run(30, 60, "normal")
        assertThat(s.windowEndMs).isEqualTo(180 * min)
        val t = s.run(60, 120, "ACTIVE").at(120, "normal")
        assertThat(t.state.windowEndMs).isEqualTo(240 * min)
        assertThat(t.state.scale).isWithin(1e-9).of(0.4)
        assertThat(t.state.fromEndurance).isFalse()
    }

    @Test fun `after the earlier window has closed a new bout takes its own window and scale`() {
        val s = RecoveryWindowState().run(0, 30, "VIGOROUS_AEROBIC").run(30, 200, "normal")
        val t = s.run(200, 230, "ACTIVE").at(230, "normal")
        assertThat(t.state.windowEndMs).isEqualTo(230 * min + 2 * hour)
        assertThat(t.state.scale).isEqualTo(0.5)
    }

    @Test fun `endurance overlay with profile 100 goes to the endurance percentage and 150`() {
        val c = BoostActivityClassification("RESTING", 100, 100.0, 100.0, 100.0, "")
        val e = applyEndurance(c, profilePercent = 100, endurancePct = 60.0, tempTargetSet = false)
        assertThat(e.state).isEqualTo("ENDURANCE")
        assertThat(e.profileSwitch).isEqualTo(60)
        assertThat(e.targetBg).isEqualTo(150.0)
    }

    @Test fun `endurance overlay keeps a raised 160 target and leaves a temp target alone`() {
        val c = BoostActivityClassification("RESISTANCE", 100, 160.0, 160.0, 160.0, "")
        assertThat(applyEndurance(c, 100, 60.0, false).targetBg).isEqualTo(160.0)
        val tt = BoostActivityClassification("normal", 100, 90.0, 90.0, 90.0, "")
        assertThat(applyEndurance(tt, 100, 60.0, true).targetBg).isEqualTo(90.0)
    }

    @Test fun `endurance overlay keeps a user profile percentage`() {
        val c = BoostActivityClassification("normal", 90, 100.0, 100.0, 100.0, "")
        assertThat(applyEndurance(c, profilePercent = 90, endurancePct = 60.0, tempTargetSet = false).profileSwitch).isEqualTo(90)
    }

    // Fix 3: endurance used to raise the profile above this cycle's classification.

    @Test fun `endurance never raises the profile above a lower activity percentage`() {
        // Activity Percent 50 while walking at the start of a ride; Endurance Profile Percent 60.
        val c = BoostActivityClassification("ACTIVE", 50, 150.0, 150.0, 150.0, "")
        assertThat(applyEndurance(c, profilePercent = 100, endurancePct = 60.0, tempTargetSet = false).profileSwitch).isEqualTo(50)
        assertThat(applyEndurance(c, profilePercent = 100, endurancePct = 60.0, tempTargetSet = true).profileSwitch).isEqualTo(50)
    }

    @Test fun `endurance still lowers a 130 inactivity raise`() {
        val c = BoostActivityClassification("INACTIVE", 130, 100.0, 100.0, 100.0, "")
        assertThat(applyEndurance(c, profilePercent = 100, endurancePct = 60.0, tempTargetSet = false).profileSwitch).isEqualTo(60)
    }

    @Test fun `endurance keeps a profile high target above 150`() {
        val c = BoostActivityClassification("normal", 100, 100.0, 180.0, 120.0, "")
        val e = applyEndurance(c, profilePercent = 100, endurancePct = 60.0, tempTargetSet = false)
        assertThat(e.maxBg).isEqualTo(180.0)
        assertThat(e.minBg).isEqualTo(150.0)
        assertThat(e.targetBg).isEqualTo(150.0)
    }

    // ── Pre-meal ──

    @Test fun `pre-meal is suppressed by ordinary exercise and by recovery, and runs otherwise`() {
        assertThat(preMealSuppression("ACTIVE", inRecovery = false)).isEqualTo("exercise")
        assertThat(preMealSuppression("STRESS", inRecovery = false)).isEqualTo("exercise")
        assertThat(preMealSuppression("normal", inRecovery = true)).isEqualTo("recovery")
        assertThat(preMealSuppression("normal", inRecovery = false)).isNull()
    }

    // Fix 4: the pre-meal target lowering did not count ENDURANCE as exercise.

    @Test fun `pre-meal is suppressed during an endurance bout`() {
        assertThat(preMealSuppression("ENDURANCE", inRecovery = false)).isEqualTo("exercise")
    }

    @Test fun `with recovery off, the endurance recovery target is not below the profile target`() {
        assertThat(recoveryTempTargetMgdl(100.0, 0.0, profileTargetMgdl = 120.0, recoveryEnabled = false)).isEqualTo(120.0)
        assertThat(recoveryTempTargetMgdl(144.0, 0.0, profileTargetMgdl = 120.0, recoveryEnabled = false)).isEqualTo(144.0)
    }

    @Test fun `with recovery on, the recovery target is the setting the user chose`() {
        assertThat(recoveryTempTargetMgdl(100.0, 10.0, profileTargetMgdl = 120.0, recoveryEnabled = true)).isEqualTo(110.0)
    }
}
