package com.murtdah.fit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlannerTest {
    @Test
    fun sundayHasAllFivePhasesInOrder() {
        val steps = SessionPlanner.build(DayType.LOWER_A)
        val phases = steps.map { it.phase }.distinct()
        assertEquals(listOf(Phase.WARM_UP, Phase.ACTIVATION, Phase.MAIN, Phase.POSTURE, Phase.COOLDOWN), phases)
        assertEquals("diaphragmatic_breathing", steps.last().exerciseId)
        assertEquals(0, steps.last().restAfterSec)
    }

    @Test
    fun rampUpSetsPrecedeFirstLoadedExerciseAndDoNotCount() {
        val main = SessionPlanner.build(DayType.LOWER_A).filter { it.phase == Phase.MAIN }
        val ramp = main.take(2)
        assertTrue(ramp.all { it.warmup && !it.strength && it.exerciseId == "tempo_squat" })
        assertEquals(8, ramp[0].repsMax)
        assertEquals(3.0, ramp[0].loadKg!!, 0.0)
        assertEquals(11, ramp[1].repsMax)
        assertEquals(5.0, ramp[1].loadKg!!, 0.0)
        assertTrue(ramp.all { it.restAfterSec == 30 })
        assertTrue(main[2].strength)
        assertEquals(4, main.count { it.exerciseId == "tempo_squat" && it.strength })
    }

    @Test
    fun coreAndMobilityDaysSkipActivationAndRampUp() {
        for (t in listOf(DayType.WALK_CORE, DayType.MOBILITY)) {
            val steps = SessionPlanner.build(t)
            assertFalse(steps.any { it.phase == Phase.ACTIVATION })
            assertFalse(steps.any { it.name.startsWith("Ramp-up") })
        }
    }

    @Test
    fun noOverheadPressingAnywhere() {
        val all = DayType.entries.flatMap { SessionPlanner.build(it) }
        assertFalse(all.any { it.name.contains("overhead", ignoreCase = true) || it.name.contains("shoulder press", ignoreCase = true) })
        assertFalse(Library.all.values.any { it.name.contains("overhead press", ignoreCase = true) })
    }

    @Test
    fun dropOneSetReducesMainVolume() {
        val normal = SessionPlanner.build(DayType.UPPER_A).count { it.phase == Phase.MAIN && it.strength }
        val dropped = SessionPlanner.build(DayType.UPPER_A, Adjustments(dropOneSet = true)).count { it.phase == Phase.MAIN && it.strength }
        assertEquals(normal - 6, dropped)
    }

    @Test
    fun dropOneSetOnCircuitRemovesOneRound() {
        val main = SessionPlanner.build(DayType.CIRCUIT, Adjustments(dropOneSet = true)).filter { it.phase == Phase.MAIN }
        assertEquals(15, main.size)
    }

    @Test
    fun kneePainSwapsSquatsAndLungesAndHops() {
        val steps = SessionPlanner.build(DayType.LOWER_B, Adjustments(painAreas = setOf(BodyArea.KNEES)))
        assertFalse(steps.any { it.exerciseId in setOf("reverse_lunge", "low_impact_hops", "half_squats", "deep_squats", "split_squat") })
        assertTrue(steps.any { it.exerciseId == "march_in_place" })
    }

    @Test
    fun shoulderPainSwapsPushUps() {
        val steps = SessionPlanner.build(DayType.UPPER_A, Adjustments(painAreas = setOf(BodyArea.SHOULDERS)))
        assertFalse(steps.any { it.exerciseId == "elevated_push_up" })
        assertTrue(steps.any { it.exerciseId == "wall_push_up" })
    }

    @Test
    fun recoveryIsPostureAndBreathingOnly() {
        val steps = SessionPlanner.build(DayType.LOWER_A, Adjustments(recoveryOnly = true))
        assertEquals(setOf(Phase.POSTURE, Phase.COOLDOWN), steps.map { it.phase }.toSet())
        assertFalse(steps.any { it.strength })
    }

    @Test
    fun upperOnlyReplacesLegDay() {
        val steps = SessionPlanner.build(DayType.LOWER_A, Adjustments(upperBodyOnly = true))
        assertTrue(steps.any { it.exerciseId == "floor_press" })
        assertFalse(steps.any { it.exerciseId == "tempo_squat" })
    }

    @Test
    fun saturdayCircuitIsFourRoundsOfTimedWork() {
        val main = SessionPlanner.build(DayType.CIRCUIT).filter { it.phase == Phase.MAIN }
        assertEquals(20, main.size)
        assertTrue(main.all { it.seconds == 45 })
        assertEquals(120, main[4].restAfterSec)
        assertEquals(15, main[0].restAfterSec)
    }

    @Test
    fun replaceExerciseOnlyTouchesRemainingSetsOfThatExercise() {
        val steps = SessionPlanner.build(DayType.LOWER_B)
        val first = steps.indexOfFirst { it.exerciseId == "reverse_lunge" }
        val out = SessionPlanner.replaceExercise(steps, first + 1, "glute_bridge")
        assertEquals("reverse_lunge", out[first].exerciseId)
        assertEquals("glute_bridge", out[first + 1].exerciseId)
        assertEquals(steps.size, out.size)
    }

    @Test
    fun hopsAreOptionalWithHonestNote() {
        val hops = SessionPlanner.build(DayType.LOWER_A).first { it.exerciseId == "low_impact_hops" }
        assertTrue(hops.optional)
        assertTrue(hops.note!!.contains("not well supported"))
    }
}

class SessionMachineTest {
    private fun run(session: Session, event: SessionEvent, now: Long, sink: MutableList<Effect>): Session {
        val t = SessionMachine.reduce(session, event, now)
        sink += t.effects
        return t.session
    }

    @Test
    fun fullSundaySessionRunsEndToEnd() {
        val steps = SessionPlanner.build(DayType.LOWER_A)
        var s = Session(steps)
        val fx = mutableListOf<Effect>()
        var now = 1_000L
        s = run(s, SessionEvent.Ready, now, fx)
        assertTrue(s.state is SessionState.Countdown)
        var guard = 0
        while (s.state !is SessionState.Done && guard++ < 20_000) {
            now += 250
            s = when (val st = s.state) {
                is SessionState.Work -> if (st.endsAt == null) run(s, SessionEvent.SetDone, now, fx) else run(s, SessionEvent.Tick, now, fx)
                is SessionState.Log -> run(s, SessionEvent.Logged(steps[st.index].repsMax, 2, steps[st.index].loadKg), now, fx)
                else -> run(s, SessionEvent.Tick, now, fx)
            }
        }
        assertEquals(SessionState.Done(true), s.state)
        val records = fx.filterIsInstance<Effect.Record>().map { it.set }
        assertEquals(steps.size, records.size)
        val strengthLogged = records.filter { it.rir != null }
        assertEquals(steps.count { it.strength }, strengthLogged.size)
        val cues = fx.filterIsInstance<Effect.Sound>().map { it.cue }
        assertTrue(Cue.START in cues && Cue.HALFWAY in cues && Cue.COMPLETE in cues && Cue.TICK in cues && Cue.REST_START in cues)
        assertEquals(1, cues.count { it == Cue.HALFWAY })
        assertEquals(1f, s.progress, 0.0001f)
    }

    @Test
    fun timedStepUsesElapsedTimeNotTickCount() {
        val steps = SessionPlanner.build(DayType.LOWER_A)
        var s = Session(steps, SessionState.Countdown(0L))
        val fx = mutableListOf<Effect>()
        s = run(s, SessionEvent.Tick, 10, fx)
        val work = s.state as SessionState.Work
        assertEquals(30_010L, work.endsAt)
        s = run(s, SessionEvent.Tick, 30_009, fx)
        assertTrue(s.state is SessionState.Work)
        s = run(s, SessionEvent.Tick, 30_010, fx)
        assertTrue(s.state is SessionState.Rest)
    }

    @Test
    fun pauseFreezesRemainingTimeAndResumeRestoresIt() {
        val steps = SessionPlanner.build(DayType.LOWER_A)
        val fx = mutableListOf<Effect>()
        var s = Session(steps, SessionState.Rest(1, endsAt = 20_000, totalMs = 20_000))
        s = run(s, SessionEvent.Pause, 5_000, fx)
        assertEquals(15_000L, (s.state as SessionState.Paused).remainingMs)
        s = run(s, SessionEvent.Resume, 100_000, fx)
        assertEquals(115_000L, (s.state as SessionState.Rest).endsAt)
    }

    @Test
    fun restControls() {
        val steps = SessionPlanner.build(DayType.LOWER_A)
        val fx = mutableListOf<Effect>()
        var s = Session(steps, SessionState.Rest(3, endsAt = 10_000, totalMs = 10_000))
        s = run(s, SessionEvent.AddRest, 1_000, fx)
        assertEquals(25_000L, (s.state as SessionState.Rest).endsAt)
        s = run(s, SessionEvent.SkipRest, 2_000, fx)
        assertEquals(3, (s.state as SessionState.Work).index)
    }

    @Test
    fun strengthSetAsksForRepsInReserveAndClampsIt() {
        val steps = SessionPlanner.build(DayType.UPPER_A)
        val i = steps.indexOfFirst { it.strength }
        val fx = mutableListOf<Effect>()
        var s = Session(steps, SessionState.Work(i, 0, null))
        s = run(s, SessionEvent.SetDone, 1, fx)
        assertEquals(SessionState.Log(i), s.state)
        s = run(s, SessionEvent.Logged(10, 9, 5.0), 2, fx)
        assertEquals(4, fx.filterIsInstance<Effect.Record>().single().set.rir)
    }

    @Test
    fun skipExerciseJumpsPastAllItsSets() {
        val steps = SessionPlanner.build(DayType.LOWER_A)
        val i = steps.indexOfFirst { it.exerciseId == "bulgarian_split_squat" }
        var s = Session(steps, SessionState.Work(i, 0, null))
        s = run(s, SessionEvent.SkipExercise, 1, mutableListOf())
        val next = (s.state as SessionState.Work).index
        assertTrue(steps[next].exerciseId != "bulgarian_split_squat")
        assertEquals(i + 3, next)
    }

    @Test
    fun quitEndsAsIncomplete() {
        var s = Session(SessionPlanner.build(DayType.LOWER_A), SessionState.Work(5, 0, null))
        s = run(s, SessionEvent.Quit, 1, mutableListOf())
        assertEquals(SessionState.Done(false), s.state)
    }

    @Test
    fun resumingStartsAtSavedIndex() {
        var s = Session(SessionPlanner.build(DayType.LOWER_A), SessionState.Resuming(12))
        s = run(s, SessionEvent.Ready, 0, mutableListOf())
        s = run(s, SessionEvent.Tick, SessionMachine.COUNTDOWN_MS, mutableListOf())
        assertEquals(12, (s.state as SessionState.Work).index)
    }
}
