package com.murtdah.fit.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

object DayClock {
    const val ROLLOVER_HOUR = 4

    fun logicalDate(instant: Instant, zone: ZoneId): LocalDate =
        instant.atZone(zone).minusHours(ROLLOVER_HOUR.toLong()).toLocalDate()

    fun dayStart(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.atTime(ROLLOVER_HOUR, 0).atZone(zone)

    fun dayEnd(date: LocalDate, zone: ZoneId): ZonedDateTime = dayStart(date.plusDays(1), zone)

    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
        val d = if (time.hour < ROLLOVER_HOUR) date.plusDays(1) else date
        return LocalDateTime.of(d, time).atZone(zone)
    }
}

object EatingWindow {
    val OPEN: LocalTime = LocalTime.of(12, 0)
    val CLOSE: LocalTime = LocalTime.of(20, 0)

    fun isLate(time: LocalTime, close: LocalTime = CLOSE): Boolean =
        !time.isBefore(close) || time.hour < DayClock.ROLLOVER_HOUR

    fun minutesUntilClose(now: LocalTime, close: LocalTime = CLOSE): Int {
        if (isLate(now, close)) return 0
        return ((close.toSecondOfDay() - now.toSecondOfDay()) / 60)
    }

    fun statusText(now: LocalTime, close: LocalTime = CLOSE): String {
        val mins = minutesUntilClose(now, close)
        return when {
            isLate(now, close) -> "Eating window closed. Water and sleep now."
            now.isBefore(OPEN) -> "Window opens at 12:00"
            else -> "Closes in ${mins / 60} h ${mins % 60} min"
        }
    }
}

data class PaceCheckpoint(val time: LocalTime, val steps: Int)

data class PaceStatus(val expected: Int, val behindBy: Int, val nudge: Boolean)

object StepPace {
    val defaultCheckpoints = listOf(
        PaceCheckpoint(LocalTime.of(8, 0), 0),
        PaceCheckpoint(LocalTime.of(14, 0), 3_000),
        PaceCheckpoint(LocalTime.of(17, 0), 6_000),
        PaceCheckpoint(LocalTime.of(20, 0), 8_000),
    )

    fun expectedAt(now: LocalTime, checkpoints: List<PaceCheckpoint> = defaultCheckpoints): Int {
        val sorted = checkpoints.sortedBy { it.time }
        if (now <= sorted.first().time) return sorted.first().steps
        if (now >= sorted.last().time) return sorted.last().steps
        val i = sorted.indexOfLast { it.time <= now }
        val a = sorted[i]
        val b = sorted[i + 1]
        val span = (b.time.toSecondOfDay() - a.time.toSecondOfDay()).toDouble()
        val frac = (now.toSecondOfDay() - a.time.toSecondOfDay()) / span
        return (a.steps + (b.steps - a.steps) * frac).roundToInt()
    }

    fun status(now: LocalTime, steps: Int, checkpoints: List<PaceCheckpoint> = defaultCheckpoints, tolerance: Int = 500): PaceStatus {
        val expected = expectedAt(now, checkpoints)
        val behind = (expected - steps).coerceAtLeast(0)
        return PaceStatus(expected, behind, behind > tolerance)
    }

    fun walkMinutesFor(steps: Int): Int = ((steps / 100.0)).roundToInt().coerceAtLeast(5)
}

object WaterPace {
    fun expectedMl(now: LocalTime, targetMl: Int, start: LocalTime = LocalTime.of(10, 0), end: LocalTime = LocalTime.of(20, 30)): Int {
        if (now <= start) return 0
        if (now >= end) return targetMl
        val frac = (now.toSecondOfDay() - start.toSecondOfDay()).toDouble() / (end.toSecondOfDay() - start.toSecondOfDay())
        return (targetMl * frac).roundToInt()
    }

    fun litres(ml: Int): String = String.format(java.util.Locale.US, "%.1f L", ml / 1000.0)
}

object BodyMath {
    fun fatMassKg(weightKg: Double, bodyFatPct: Double): Double = weightKg * bodyFatPct / 100.0

    fun estimatedBurn(steps: Int, baseKcal: Int = 2_000, kcalPerStep: Double = 0.065): Int =
        (baseKcal + steps * kcalPerStep).roundToInt()

    data class Balance(val eaten: Int, val burned: Int, val net: Int, val errorKcal: Int)

    fun balance(eatenKcal: Int, watchBurn: Int?, steps: Int, baseKcal: Int = 2_000, kcalPerStep: Double = 0.065): Balance {
        val burn = watchBurn ?: estimatedBurn(steps, baseKcal, kcalPerStep)
        return Balance(eatenKcal, burn, eatenKcal - burn, (burn * 0.10).roundToInt())
    }

    fun weeksToLose(kg: Double, perWeekKg: Double): Int = if (perWeekKg <= 0) 0 else kotlin.math.ceil(kg / perWeekKg).toInt()
}

enum class Verdict(val text: String) {
    REAL_FAT_LOSS("Real fat loss"),
    WATER_CHANGE("Mostly a water change"),
    FLAT("Flat week"),
    FAT_GAIN("Fat up this week"),
    NOT_ENOUGH_DATA("Not enough fasted data yet"),
}

data class VerdictResult(val verdict: Verdict, val weightChangeKg: Double?, val fatChangeKg: Double?, val waterCaution: Boolean, val detail: String)

object WeeklyVerdict {
    fun average(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    fun evaluate(
        thisWeekFastedKg: List<Double>,
        lastWeekFastedKg: List<Double>,
        thisScanFatKg: Double?,
        lastScanFatKg: Double?,
        bodyWaterPct: Double?,
    ): VerdictResult {
        val caution = bodyWaterPct != null && bodyWaterPct < 47.0
        val a = average(thisWeekFastedKg)
        val b = average(lastWeekFastedKg)
        if (a == null || b == null || thisWeekFastedKg.size < 3 || lastWeekFastedKg.size < 3) {
            return VerdictResult(Verdict.NOT_ENOUGH_DATA, null, null, caution, "Log at least 3 fasted weigh-ins per week to get a verdict.")
        }
        val dw = a - b
        val df = if (thisScanFatKg != null && lastScanFatKg != null) thisScanFatKg - lastScanFatKg else null
        val verdict = when {
            df != null && df <= -0.3 && dw < 0 -> Verdict.REAL_FAT_LOSS
            df != null && df >= 0.3 -> Verdict.FAT_GAIN
            abs(dw) < 0.2 -> Verdict.FLAT
            dw < 0 && (df == null || abs(df) < 0.3) -> Verdict.WATER_CHANGE
            dw < 0 -> Verdict.REAL_FAT_LOSS
            else -> Verdict.FLAT
        }
        val detail = buildString {
            append(String.format(java.util.Locale.US, "7 day average %+.1f kg", dw))
            if (df != null) append(String.format(java.util.Locale.US, ", fat mass %+.1f kg", df))
            append(".")
            if (caution) append(" Body water is under 47%. Dehydration distorts scale readings, so treat this scan with caution.")
        }
        return VerdictResult(verdict, dw, df, caution, detail)
    }
}
