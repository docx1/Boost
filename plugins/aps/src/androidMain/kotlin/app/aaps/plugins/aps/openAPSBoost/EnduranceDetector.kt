package app.aaps.plugins.aps.openAPSBoost

/**
 * Sustained low-step aerobic work: long rides, rowing, paddling (2026-09-28, Boost-endurance).
 *
 * The step-based activity path cannot see cycling, which produces almost no steps, and the heart-rate
 * path classifies one cycle at a time, so a ride flickers between RESISTANCE (zone 3 to 4), RESTING
 * (zone 2, then the inactivity branch) and back as the rider climbs, descends and stops. Each flicker
 * ends an exercise bout. This detector holds one state across the whole effort instead.
 *
 * A cycle qualifies when heart rate is in zone 2 or above (HRR >= 30%) and fewer than
 * [LOW_STEPS_15MIN] steps landed in the last 15 minutes. Endurance begins once qualifying cycles have
 * run for [ENTRY_MIN] minutes, a gap of up to [DIP_TOLERANCE_MIN] between qualifying cycles not
 * resetting the clock. The 11 minutes are a modelling choice: at a five-minute loop they cover one
 * missed or non-qualifying cycle plus timing jitter (two cycles 5 min 10 s apart broke the earlier
 * 5 minute tolerance), and two in a row restart the entry clock; at a one-minute loop a dip of up to
 * 10 minutes rides through. It ends
 * only after [EXIT_MIN] continuous minutes without a qualifying cycle, so a descent or a short stop
 * does not end the ride. Missing heart rate does not qualify, so a watch that goes dark ends the
 * state after [EXIT_MIN]. Missing step data does not qualify either (2026-10-27): a dead step feed
 * reads as zero steps, so walking and running at zone 2 qualified; replayed with steps forced to 0
 * over 24 to 132 days a person, a permanently dead feed would have added up to 1.5 false bouts a
 * week. Without steps a ride cannot be told from a walk, so, as for the
 * inactivity and lie-in gates, a dark feed changes nothing; a feed that dies mid-ride ends the bout
 * after [EXIT_MIN]. Time-based throughout, so it behaves the same at any loop cadence.
 *
 * Known overlap: a weights session with steady heart rate and little walking can qualify after 30
 * minutes and receive the endurance profile reduction, where resistance guidance keeps the profile.
 */
object EnduranceDetector {

    const val ENTRY_MIN = 30
    const val DIP_TOLERANCE_MIN = 11
    const val EXIT_MIN = 20
    const val LOW_STEPS_15MIN = 100

    private const val MIN_MS = 60_000L

    data class State(
        val candidateSinceMs: Long? = null,
        val lastQualifyingMs: Long? = null,
        val active: Boolean = false,
        val activeSinceMs: Long? = null
    )

    /** [steps15Min] is null when no step feed is live. */
    fun qualifies(zone: HrActivityCalculator.HrZone?, steps15Min: Int?): Boolean =
        zone != null && zone >= HrActivityCalculator.HrZone.ZONE_2_LIGHT && steps15Min != null && steps15Min < LOW_STEPS_15MIN

    fun step(previous: State, nowMs: Long, zone: HrActivityCalculator.HrZone?, steps15Min: Int?): State {
        // A gap of EXIT_MIN or more since the last qualifying cycle ends an active bout, whatever this
        // cycle shows. The caller steps every cycle, so such a gap means missed cycles, for example a
        // loop that did not run or a plugin restart.
        val prev = previous.lastQualifyingMs
            ?.takeIf { previous.active && nowMs - it >= EXIT_MIN * MIN_MS }
            ?.let { State() } ?: previous
        if (qualifies(zone, steps15Min)) {
            val gapOk = prev.lastQualifyingMs != null && nowMs - prev.lastQualifyingMs <= DIP_TOLERANCE_MIN * MIN_MS
            val since = if (prev.active) prev.candidateSinceMs ?: nowMs else if (gapOk) prev.candidateSinceMs ?: nowMs else nowMs
            val becomesActive = prev.active || nowMs - since >= ENTRY_MIN * MIN_MS
            return State(
                candidateSinceMs = since,
                lastQualifyingMs = nowMs,
                active = becomesActive,
                activeSinceMs = if (becomesActive) prev.activeSinceMs ?: since else null
            )
        }
        val last = prev.lastQualifyingMs ?: return State()
        return if (prev.active) {
            if (nowMs - last >= EXIT_MIN * MIN_MS) State() else prev
        } else {
            if (nowMs - last > DIP_TOLERANCE_MIN * MIN_MS) State() else prev
        }
    }
}
