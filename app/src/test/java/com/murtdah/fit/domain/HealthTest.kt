package com.murtdah.fit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DayClockTest {
    private val dubai = ZoneId.of("Asia/Dubai")

    @Test
    fun lateNightCountsTowardPreviousDay() {
        val t = ZonedDateTime.of(2026, 11, 22, 1, 30, 0, 0, dubai).toInstant()
        assertEquals(LocalDate.of(2026, 11, 21), DayClock.logicalDate(t, dubai))
    }

    @Test
    fun rolloverAtFourAm() {
        val before = ZonedDateTime.of(2026, 11, 22, 3, 59, 0, 0, dubai).toInstant()
        val after = ZonedDateTime.of(2026, 11, 22, 4, 0, 0, 0, dubai).toInstant()
        assertEquals(LocalDate.of(2026, 11, 21), DayClock.logicalDate(before, dubai))
        assertEquals(LocalDate.of(2026, 11, 22), DayClock.logicalDate(after, dubai))
    }

    @Test
    fun sameInstantDiffersByZone() {
        val t = ZonedDateTime.of(2026, 11, 22, 2, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        assertEquals(LocalDate.of(2026, 11, 22), DayClock.logicalDate(t, dubai))
        assertEquals(LocalDate.of(2026, 11, 21), DayClock.logicalDate(t, ZoneId.of("UTC")))
    }

    @Test
    fun earlyMorningTimeMapsToNextCalendarDay() {
        val d = LocalDate.of(2026, 11, 21)
        assertEquals(LocalDate.of(2026, 11, 22), DayClock.at(d, LocalTime.of(1, 0), dubai).toLocalDate())
        assertEquals(d, DayClock.at(d, LocalTime.of(20, 0), dubai).toLocalDate())
    }

    @Test
    fun eatingWindow() {
        assertTrue(EatingWindow.isLate(LocalTime.of(20, 0)))
        assertTrue(EatingWindow.isLate(LocalTime.of(1, 0)))
        assertFalse(EatingWindow.isLate(LocalTime.of(19, 59)))
        assertEquals(80, EatingWindow.minutesUntilClose(LocalTime.of(18, 40)))
        assertEquals("Closes in 1 h 20 min", EatingWindow.statusText(LocalTime.of(18, 40)))
    }
}

class StepPaceTest {
    @Test
    fun checkpoints() {
        assertEquals(3_000, StepPace.expectedAt(LocalTime.of(14, 0)))
        assertEquals(6_000, StepPace.expectedAt(LocalTime.of(17, 0)))
        assertEquals(8_000, StepPace.expectedAt(LocalTime.of(22, 0)))
        assertEquals(0, StepPace.expectedAt(LocalTime.of(7, 0)))
        assertEquals(4_500, StepPace.expectedAt(LocalTime.of(15, 30)))
    }

    @Test
    fun nudgeOnlyWhenClearlyBehind() {
        assertTrue(StepPace.status(LocalTime.of(17, 0), 4_000).nudge)
        assertFalse(StepPace.status(LocalTime.of(17, 0), 5_700).nudge)
        assertEquals(2_000, StepPace.status(LocalTime.of(17, 0), 4_000).behindBy)
        assertEquals(0, StepPace.status(LocalTime.of(17, 0), 9_000).behindBy)
    }
}

class BodyMathTest {
    @Test
    fun fatMass() = assertEquals(38.76, BodyMath.fatMassKg(114.0, 34.0), 0.001)

    @Test
    fun estimatedBurnMatchesBrief() = assertEquals(2_650, BodyMath.estimatedBurn(10_000))

    @Test
    fun balanceUsesWatchWhenPresentWithTenPercentBand() {
        val est = BodyMath.balance(2_100, null, 10_000)
        assertEquals(-550, est.net)
        assertEquals(265, est.errorKcal)
        val watch = BodyMath.balance(2_100, 3_000, 10_000)
        assertEquals(-900, watch.net)
        assertEquals(300, watch.errorKcal)
    }

    @Test
    fun projectionWeeks() {
        assertEquals(16, BodyMath.weeksToLose(16.0, 1.0))
        assertEquals(32, BodyMath.weeksToLose(16.0, 0.5))
    }

    @Test
    fun verdicts() {
        val last = listOf(114.0, 114.2, 113.9)
        val down = listOf(113.2, 113.0, 113.1)
        assertEquals(Verdict.REAL_FAT_LOSS, WeeklyVerdict.evaluate(down, last, 37.8, 38.6, 52.0).verdict)
        assertEquals(Verdict.WATER_CHANGE, WeeklyVerdict.evaluate(down, last, 38.5, 38.6, 52.0).verdict)
        assertEquals(Verdict.FLAT, WeeklyVerdict.evaluate(last, last, null, null, null).verdict)
        assertEquals(Verdict.NOT_ENOUGH_DATA, WeeklyVerdict.evaluate(listOf(113.0), last, null, null, null).verdict)
        val caution = WeeklyVerdict.evaluate(down, last, 37.8, 38.6, 45.0)
        assertTrue(caution.waterCaution)
        assertTrue(caution.detail.contains("Dehydration"))
    }

    @Test
    fun waterPace() {
        assertEquals(0, WaterPace.expectedMl(LocalTime.of(9, 0), 2_500))
        assertEquals(2_500, WaterPace.expectedMl(LocalTime.of(21, 0), 2_500))
        assertEquals(1_250, WaterPace.expectedMl(LocalTime.of(15, 15), 2_500))
    }
}

class ReminderPlannerTest {
    private val s = ReminderSettings()

    @Test
    fun waterEveryNinetyMinutesInsideTheWindow() {
        val plan = ReminderPlanner.plan(DayOfWeek.MONDAY, true, s)
        val water = plan.filter { it.type == ReminderType.WATER }.map { it.time }
        assertEquals(LocalTime.of(12, 0), water.first())
        assertTrue(water.all { it >= s.waterStart && it <= s.waterEnd })
        assertFalse(LocalTime.of(19, 30) in water)
    }

    @Test
    fun weighInOnlyOnFriday() {
        assertTrue(ReminderPlanner.plan(DayOfWeek.FRIDAY, true, s).any { it.type == ReminderType.WEIGH_IN && it.time == LocalTime.of(8, 0) })
        assertFalse(ReminderPlanner.plan(DayOfWeek.THURSDAY, true, s).any { it.type == ReminderType.WEIGH_IN })
    }

    @Test
    fun quietHoursBlockEverythingExceptExempt() {
        val loud = s.copy(firstMeal = LocalTime.of(7, 0))
        val plan = ReminderPlanner.plan(DayOfWeek.FRIDAY, true, loud)
        assertFalse(plan.any { it.type == ReminderType.FIRST_MEAL })
        assertTrue(plan.any { it.type == ReminderType.WEIGH_IN })
        assertTrue(plan.any { it.type == ReminderType.BEDTIME })
        assertTrue(ReminderPlanner.inQuietHours(LocalTime.of(2, 0), s))
        assertFalse(ReminderPlanner.inQuietHours(LocalTime.of(12, 0), s))
    }

    @Test
    fun workoutRemindersFortyFiveMinutesBefore() {
        val plan = ReminderPlanner.plan(DayOfWeek.SUNDAY, true, s)
        assertTrue(plan.any { it.type == ReminderType.WORKOUT_SOON && it.time == LocalTime.of(16, 15) })
        assertTrue(plan.any { it.type == ReminderType.WORKOUT_NOW && it.time == LocalTime.of(17, 0) })
        assertFalse(ReminderPlanner.plan(DayOfWeek.SUNDAY, false, s).any { it.type == ReminderType.WORKOUT_NOW })
    }

    @Test
    fun idsAreUnique() {
        val plan = ReminderPlanner.plan(DayOfWeek.FRIDAY, true, s)
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
    }

    private fun ctx(now: LocalTime = LocalTime.of(15, 0), water: Int = 0, protein: Int = 0, steps: Int = 0, late: Boolean = false, trained: Boolean = false, fired: Int = 0) =
        FireContext(now, water, 2_500, protein, steps, late, trained, fired)

    @Test
    fun conditionalRemindersSkipWhenOnTrack() {
        assertTrue(ReminderPlanner.shouldFire(ReminderType.WATER, ctx(water = 300)))
        assertFalse(ReminderPlanner.shouldFire(ReminderType.WATER, ctx(water = 2_000)))
        assertTrue(ReminderPlanner.shouldFire(ReminderType.PROTEIN_CHECK, ctx(protein = 50)))
        assertFalse(ReminderPlanner.shouldFire(ReminderType.PROTEIN_CHECK, ctx(protein = 80)))
        assertFalse(ReminderPlanner.shouldFire(ReminderType.WORKOUT_NOW, ctx(trained = true)))
        assertFalse(ReminderPlanner.shouldFire(ReminderType.LATE_FOOD_CHECK, ctx(late = false)))
        assertTrue(ReminderPlanner.shouldFire(ReminderType.STEPS, ctx(now = LocalTime.of(17, 0), steps = 3_000)))
    }

    @Test
    fun dailyCapOfEightExceptEssentials() {
        assertFalse(ReminderPlanner.shouldFire(ReminderType.FIRST_MEAL, ctx(fired = 8)))
        assertTrue(ReminderPlanner.shouldFire(ReminderType.EATING_CLOSE, ctx(fired = 8)))
        assertTrue(ReminderPlanner.shouldFire(ReminderType.WEIGH_IN, ctx(fired = 12)))
    }

    @Test
    fun delayAfterLargeMealOnly() {
        assertEquals(90, WorkoutDelay.suggestedDelayMinutes(30, 1_000))
        assertEquals(0, WorkoutDelay.suggestedDelayMinutes(30, 600))
        assertEquals(0, WorkoutDelay.suggestedDelayMinutes(100, 1_000))
    }

    @Test
    fun weeklyFocusPicksWeakestArea() {
        val stats = WeekStats(5, 4, 0, 6, 1, 6, 0, 6, 7, emptyList())
        assertTrue(WeeklyReview.focus(stats).startsWith("Water"))
        assertEquals("2 of 3 skipped sessions: ate heavy.", WeeklyReview.skipPattern(listOf("Ate heavy", "Tired", "Ate heavy")))
    }
}
