package com.murtdah.fit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {
    private val squat = Library["tempo_squat"]
    private val squatRx = ProgramSeed.plans.getValue(DayType.LOWER_A).main.first { it.exerciseId == "tempo_squat" }

    private fun sets(reps: Int, rir: Int, n: Int = 4, kg: Double? = 5.0) = List(n) { SetResult(reps, rir, kg) }

    @Test
    fun belowTopOfRangeAddsOneRep() {
        val out = Progression.apply(squat, squatRx, ProgressState("tempo_squat"), sets(12, 2))
        assertEquals(Rung.BASE, out.next.rung)
        assertEquals(13, out.next.targetReps)
        assertFalse(out.equipmentLimit)
    }

    @Test
    fun oneShortSetBlocksProgression() {
        val s = sets(15, 2, 3) + SetResult(13, 2, 5.0)
        val out = Progression.apply(squat, squatRx, ProgressState("tempo_squat"), s)
        assertEquals(Rung.BASE, out.next.rung)
    }

    @Test
    fun topOfRangeOnEverySetAdvancesToSlowerTempo() {
        val out = Progression.apply(squat, squatRx, ProgressState("tempo_squat"), sets(15, 1))
        assertEquals(Rung.SLOW_TEMPO, out.next.rung)
        assertEquals(12, out.next.targetReps)
    }

    @Test
    fun zeroRepsInReserveDoesNotCountAsTopAndWarns() {
        val out = Progression.apply(squat, squatRx, ProgressState("tempo_squat"), sets(15, 0))
        assertEquals(Rung.BASE, out.next.rung)
        assertTrue(out.message.contains("failure"))
    }

    @Test
    fun ladderOrderIsTempoVariationSetThenLoad() {
        val lateral = Library["lateral_raise"]
        assertEquals(listOf(Rung.BASE, Rung.SLOW_TEMPO, Rung.HARDER_VARIATION, Rung.EXTRA_SET, Rung.MORE_LOAD), Progression.ladder(lateral, 3.0))
    }

    @Test
    fun noMoreLoadRungWhenAlreadyAtHeaviestDumbbell() {
        assertFalse(Rung.MORE_LOAD in Progression.ladder(squat, 5.0))
    }

    @Test
    fun equipmentLimitCardAfterTwoStalledSessionsAtTop() {
        val top = ProgressState("tempo_squat", rung = Rung.EXTRA_SET, loadKg = 5.0)
        val first = Progression.apply(squat, squatRx, top, sets(15, 1, 5))
        assertFalse(first.equipmentLimit)
        val second = Progression.apply(squat, squatRx, first.next, sets(15, 1, 5))
        assertTrue(second.equipmentLimit)
        assertEquals(Progression.EQUIPMENT_LIMIT_MESSAGE, second.message)
    }

    @Test
    fun threeKgMovesToFiveKgAtLoadRung() {
        val lateral = Library["lateral_raise"]
        val rx = ProgramSeed.plans.getValue(DayType.UPPER_A).main.first { it.exerciseId == "lateral_raise" }
        val state = ProgressState("lateral_raise", rung = Rung.EXTRA_SET, loadKg = 3.0)
        val out = Progression.apply(lateral, rx, state, sets(15, 2, 4, 3.0))
        assertEquals(Rung.MORE_LOAD, out.next.rung)
        assertEquals(5.0, out.next.loadKg!!, 0.0)
    }

    @Test
    fun adjustAddsExtraSetAndTempo() {
        val adjusted = Progression.adjust(squatRx, ProgressState("tempo_squat", rung = Rung.EXTRA_SET), squat)
        assertEquals(squatRx.sets + 1, adjusted.sets)
        assertEquals(4, adjusted.tempoDownSec)
    }

    @Test
    fun deloadAdvice() {
        assertEquals(DeloadAdvice.DELOAD_WEEK, Deload.advise(4, 0))
        assertEquals(DeloadAdvice.RESUME_AT_80, Deload.advise(0, 2))
        assertEquals(DeloadAdvice.NONE, Deload.advise(2, 1))
    }
}
