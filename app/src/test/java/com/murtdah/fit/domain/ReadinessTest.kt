package com.murtdah.fit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    private fun input(
        minutes: Int? = 200, kcal: Int? = 600, water: Int = 600, sleep: SleepBand = SleepBand.OVER_6_5,
        pain: Set<BodyArea> = emptySet(), soreness: Int = 2, same: Boolean = false, feeling: Feeling = Feeling.NORMAL,
        red: Boolean = false, day: DayType = DayType.LOWER_A,
    ) = ReadinessInput(minutes, kcal, water, sleep, pain, soreness, same, feeling, red, day)

    @Test fun allGood() = assertEquals(Light.GREEN, ReadinessRules.evaluate(input()).light)

    @Test fun largeMealNeedsTwoHours() {
        val r = ReadinessRules.evaluate(input(minutes = 60, kcal = 1_100))
        assertEquals(Light.AMBER, r.light)
        assertEquals(Fix.Delay(60), r.fixes.first { it is Fix.Delay })
    }

    @Test fun mealGapThresholds() {
        assertEquals(120, ReadinessRules.requiredGapMinutes(801))
        assertEquals(60, ReadinessRules.requiredGapMinutes(800))
        assertEquals(60, ReadinessRules.requiredGapMinutes(300))
        assertEquals(30, ReadinessRules.requiredGapMinutes(299))
        assertEquals(Light.GREEN, ReadinessRules.evaluate(input(minutes = 35, kcal = 200)).light)
    }

    @Test fun lowWaterIsAmberWithDrinkFix() {
        val r = ReadinessRules.evaluate(input(water = 200))
        assertEquals(Light.AMBER, r.light)
        assertTrue(Fix.DrinkWater in r.fixes)
    }

    @Test fun shortSleepRedOnLowerStrengthAmberOnUpper() {
        assertEquals(Light.RED, ReadinessRules.evaluate(input(sleep = SleepBand.UNDER_5, day = DayType.LOWER_A)).light)
        assertEquals(Light.AMBER, ReadinessRules.evaluate(input(sleep = SleepBand.UNDER_5, day = DayType.UPPER_A)).light)
        val mid = ReadinessRules.evaluate(input(sleep = SleepBand.FIVE_TO_6_5))
        assertEquals(Light.AMBER, mid.light)
        assertTrue(Fix.DropOneSet in mid.fixes)
    }

    @Test fun redFlagsAlwaysRed() {
        val r = ReadinessRules.evaluate(input(red = true, day = DayType.MOBILITY))
        assertEquals(Light.RED, r.light)
        assertTrue(r.findings.any { it.message == ReadinessRules.RED_FLAG_MESSAGE })
        assertEquals(Light.RED, ReadinessRules.evaluate(input(feeling = Feeling.UNWELL)).light)
    }

    @Test fun painSwaps() {
        val r = ReadinessRules.evaluate(input(pain = setOf(BodyArea.KNEES)))
        assertEquals(Light.AMBER, r.light)
        assertTrue(r.fixes.contains(Fix.SwapFor(setOf(BodyArea.KNEES))))
    }

    @Test fun soreLegsOnLegDaySwitchToUpper() {
        val r = ReadinessRules.evaluate(input(soreness = 8, same = true, day = DayType.LOWER_B))
        assertTrue(Fix.UpperBodyOnly in r.fixes)
        val notSame = ReadinessRules.evaluate(input(soreness = 8, same = false))
        assertEquals(Light.GREEN, notSame.light)
    }

    @Test fun noMealLoggedIsNotABlock() {
        assertEquals(Light.GREEN, ReadinessRules.evaluate(input(minutes = null, kcal = null)).light)
    }
}
