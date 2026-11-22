package com.murtdah.fit.domain

import java.time.DayOfWeek
import java.time.LocalTime

enum class ReminderType(val priority: Int, val ignoresQuietHours: Boolean = false) {
    WEIGH_IN(10, ignoresQuietHours = true),
    EATING_CLOSE(9),
    WORKOUT_NOW(8),
    WORKOUT_SOON(7),
    FIRST_MEAL(6),
    EATING_WARNING(6),
    PROTEIN_CHECK(5),
    STEPS(4),
    LATE_FOOD_CHECK(4),
    WATER(3),
    BEDTIME(2, ignoresQuietHours = true),
}

data class PlannedReminder(val type: ReminderType, val time: LocalTime, val id: Int)

data class ReminderSettings(
    val waterStart: LocalTime = LocalTime.of(12, 0),
    val waterEnd: LocalTime = LocalTime.of(20, 30),
    val waterEveryMin: Int = 90,
    val firstMeal: LocalTime = LocalTime.of(11, 45),
    val workoutTime: LocalTime = LocalTime.of(17, 0),
    val stepCheckpoints: List<LocalTime> = listOf(LocalTime.of(14, 0), LocalTime.of(17, 0), LocalTime.of(20, 0)),
    val eatingWarning: LocalTime = LocalTime.of(19, 30),
    val eatingClose: LocalTime = LocalTime.of(20, 0),
    val lateFoodCheck: LocalTime = LocalTime.of(20, 30),
    val proteinCheck: LocalTime = LocalTime.of(16, 0),
    val weighIn: LocalTime = LocalTime.of(8, 0),
    val bedtime: LocalTime = LocalTime.of(23, 30),
    val quietStart: LocalTime = LocalTime.of(23, 30),
    val quietEnd: LocalTime = LocalTime.of(8, 0),
    val dailyCap: Int = 8,
    val batchWindowMin: Int = 10,
)

data class FireContext(
    val now: LocalTime,
    val waterMl: Int,
    val waterTargetMl: Int,
    val proteinG: Int,
    val steps: Int,
    val loggedLateFood: Boolean,
    val trainedToday: Boolean,
    val firedToday: Int,
)

object ReminderPlanner {
    fun inQuietHours(t: LocalTime, s: ReminderSettings): Boolean =
        if (s.quietStart > s.quietEnd) t > s.quietStart || t < s.quietEnd
        else t > s.quietStart && t < s.quietEnd

    fun plan(day: DayOfWeek, isTrainingDay: Boolean, s: ReminderSettings): List<PlannedReminder> {
        val raw = mutableListOf<Pair<ReminderType, LocalTime>>()
        var w = s.waterStart
        while (!w.isAfter(s.waterEnd)) {
            raw += ReminderType.WATER to w
            val n = w.plusMinutes(s.waterEveryMin.toLong())
            if (n.isBefore(w)) break
            w = n
        }
        raw += ReminderType.FIRST_MEAL to s.firstMeal
        if (isTrainingDay) {
            raw += ReminderType.WORKOUT_SOON to s.workoutTime.minusMinutes(45)
            raw += ReminderType.WORKOUT_NOW to s.workoutTime
        }
        s.stepCheckpoints.forEach { raw += ReminderType.STEPS to it }
        raw += ReminderType.EATING_WARNING to s.eatingWarning
        raw += ReminderType.EATING_CLOSE to s.eatingClose
        raw += ReminderType.LATE_FOOD_CHECK to s.lateFoodCheck
        raw += ReminderType.PROTEIN_CHECK to s.proteinCheck
        if (day == DayOfWeek.FRIDAY) raw += ReminderType.WEIGH_IN to s.weighIn
        raw += ReminderType.BEDTIME to s.bedtime

        val allowed = raw.filter { (type, t) -> type.ignoresQuietHours || !inQuietHours(t, s) }
            .sortedBy { it.second }

        val batched = mutableListOf<Pair<ReminderType, LocalTime>>()
        for (item in allowed) {
            val clash = batched.indexOfFirst { prev ->
                kotlin.math.abs(prev.second.toSecondOfDay() - item.second.toSecondOfDay()) < s.batchWindowMin * 60 &&
                    prev.first == ReminderType.WATER && item.first != ReminderType.WATER
            }
            if (clash >= 0) {
                batched[clash] = item
            } else if (item.first == ReminderType.WATER && batched.any { kotlin.math.abs(it.second.toSecondOfDay() - item.second.toSecondOfDay()) < s.batchWindowMin * 60 }) {
                continue
            } else {
                batched += item
            }
        }
        return batched.mapIndexed { i, (type, t) -> PlannedReminder(type, t, 1000 + i) }
    }

    fun shouldFire(type: ReminderType, ctx: FireContext, s: ReminderSettings = ReminderSettings()): Boolean {
        val essential = type.priority >= ReminderType.EATING_CLOSE.priority
        if (ctx.firedToday >= s.dailyCap && !essential) return false
        return when (type) {
            ReminderType.WATER -> {
                val expected = WaterPace.expectedMl(ctx.now, ctx.waterTargetMl)
                ctx.waterMl < expected - 150
            }
            ReminderType.PROTEIN_CHECK -> ctx.proteinG < 70
            ReminderType.STEPS -> StepPace.status(ctx.now, ctx.steps).nudge
            ReminderType.LATE_FOOD_CHECK -> ctx.loggedLateFood
            ReminderType.WORKOUT_SOON, ReminderType.WORKOUT_NOW -> !ctx.trainedToday
            else -> true
        }
    }
}

object WorkoutDelay {
    fun suggestedDelayMinutes(minutesSinceMeal: Int?, mealKcal: Int?): Int {
        if (minutesSinceMeal == null || mealKcal == null) return 0
        if (mealKcal <= ReadinessRules.LARGE_MEAL_KCAL || minutesSinceMeal >= 90) return 0
        return (ReadinessRules.requiredGapMinutes(mealKcal) - minutesSinceMeal).coerceIn(0, 120)
    }
}

data class WeekStats(
    val trainingPlanned: Int,
    val trainingDone: Int,
    val recoveryDays: Int,
    val stepDaysHit: Int,
    val waterDaysHit: Int,
    val proteinDaysHit: Int,
    val lateEatingDays: Int,
    val sodiumDaysUnderCap: Int,
    val daysLogged: Int,
    val skipReasons: List<String>,
)

object WeeklyReview {
    fun focus(s: WeekStats): String {
        val d = s.daysLogged.coerceAtLeast(1)
        val candidates = listOf(
            (s.waterDaysHit.toDouble() / d) to "Water: hit the target on at least 5 days. Fill a 1 L bottle before 12:00 and again at 16:00.",
            (s.proteinDaysHit.toDouble() / d) to "Protein: open every eating window with whey or yogurt before anything sweet.",
            ((d - s.lateEatingDays).toDouble() / d) to "Eating window: last bite by 20:00 on every day. Set dinner for 19:00.",
            (s.sodiumDaysUnderCap.toDouble() / d) to "Sodium: cut fast food and instant noodles to bring most days under 2,300 mg.",
            (s.stepDaysHit.toDouble() / d) to "Steps: take a 15 minute walk after your largest meal every day.",
            ((s.trainingDone + s.recoveryDays).toDouble() / s.trainingPlanned.coerceAtLeast(1)) to "Training: decide the exact time of each session the night before.",
        )
        return candidates.minBy { it.first }.second
    }

    fun skipPattern(reasons: List<String>): String? {
        if (reasons.size < 2) return null
        val top = reasons.groupingBy { it }.eachCount().maxBy { it.value }
        return "${top.value} of ${reasons.size} skipped sessions: ${top.key.lowercase()}."
    }
}
