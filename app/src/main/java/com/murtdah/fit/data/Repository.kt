package com.murtdah.fit.data

import android.content.Context
import androidx.room.Room
import com.murtdah.fit.domain.DayClock
import com.murtdah.fit.domain.EatingWindow
import com.murtdah.fit.domain.ProgressState
import com.murtdah.fit.domain.Rung
import com.murtdah.fit.domain.WeekStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class DayTotals(
    val kcal: Int = 0,
    val proteinG: Int = 0,
    val fatG: Int = 0,
    val carbsG: Int = 0,
    val sodiumMg: Int = 0,
    val fiberG: Int = 0,
    val waterMl: Int = 0,
    val lastBite: Long? = null,
    val lateMeal: Boolean = false,
    val sweetsFirst: Boolean = false,
)

class Repository(private val context: Context, val dao: FitDao, val prefs: Prefs) {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun today(): LocalDate = DayClock.logicalDate(Instant.now(), zone)
    fun dateOf(millis: Long): LocalDate = DayClock.logicalDate(Instant.ofEpochMilli(millis), zone)
    fun localTime(millis: Long): LocalTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()

    fun waterTargetMl(date: LocalDate = today()): Int {
        val t = prefs.targets
        val start = runCatching { LocalDate.parse(rampStart()) }.getOrDefault(date)
        val weeks = ChronoUnit.WEEKS.between(start, date).coerceAtLeast(0)
        return (t.waterMl + 250 * weeks.toInt()).coerceAtMost(t.waterRampToMl)
    }

    private fun rampStart(): String {
        val sp = context.getSharedPreferences("murtdah_fit", Context.MODE_PRIVATE)
        return sp.getString("water_ramp_start", null) ?: today().toString().also {
            sp.edit().putString("water_ramp_start", it).apply()
        }
    }

    fun totals(meals: List<Meal>, drinks: List<Drink>): DayTotals {
        val sorted = meals.sortedBy { it.time }
        val firstSweet = sorted.firstOrNull()?.let { first ->
            first.sweet && sorted.takeWhile { it.time <= first.time }.sumOf { it.proteinG } < 20
        } ?: false
        return DayTotals(
            kcal = meals.sumOf { it.kcal } + drinks.sumOf { it.kcal },
            proteinG = meals.sumOf { it.proteinG } + drinks.sumOf { it.proteinG },
            fatG = meals.sumOf { it.fatG },
            carbsG = meals.sumOf { it.carbsG },
            sodiumMg = meals.sumOf { it.sodiumMg },
            fiberG = meals.sumOf { it.fiberG },
            waterMl = drinks.sumOf { it.ml },
            lastBite = sorted.lastOrNull()?.time,
            lateMeal = meals.any { EatingWindow.isLate(localTime(it.time), prefs.reminders.eatingClose) },
            sweetsFirst = firstSweet,
        )
    }

    fun totalsFlow(date: LocalDate): Flow<DayTotals> =
        combine(dao.meals(date.toString()), dao.drinks(date.toString())) { m, d -> totals(m, d) }

    suspend fun totalsNow(date: LocalDate = today()): DayTotals =
        totals(dao.mealsNow(date.toString()), dao.drinksNow(date.toString()))

    suspend fun addDrink(ml: Int, kind: String = "water", kcal: Int = 0, protein: Int = 0) {
        val now = System.currentTimeMillis()
        dao.insertDrink(Drink(date = dateOf(now).toString(), time = now, ml = ml, kind = kind, kcal = kcal, proteinG = protein))
    }

    suspend fun addMeal(m: Meal) = dao.insertMeal(m.copy(date = dateOf(m.time).toString()))

    suspend fun day(date: LocalDate = today()): DayLog = dao.dayNow(date.toString()) ?: DayLog(date.toString())

    suspend fun updateDay(date: LocalDate = today(), block: (DayLog) -> DayLog) {
        dao.upsertDay(block(day(date)))
    }

    suspend fun progressMap(): Map<String, ProgressState> = dao.allProgress().associate {
        it.exerciseId to ProgressState(it.exerciseId, runCatching { Rung.valueOf(it.rung) }.getOrDefault(Rung.BASE), it.loadKg, it.targetReps, it.stalledAtTop)
    }

    suspend fun saveProgress(p: ProgressState, sessionId: Long) {
        dao.upsertProgress(ProgressRow(p.exerciseId, p.rung.name, p.loadKg, p.targetReps, p.stalledAtTop, sessionId))
    }

    suspend fun weekStats(end: LocalDate): WeekStats {
        val start = end.minusDays(6)
        val from = start.toString()
        val to = end.toString()
        val days = dao.daysBetween(from, to).associateBy { it.date }
        val meals = dao.mealsBetween(from, to).groupBy { it.date }
        val drinks = dao.drinksBetween(from, to).groupBy { it.date }
        val sessions = dao.sessionsBetween(from, to)
        val t = prefs.targets
        var steps = 0; var water = 0; var protein = 0; var late = 0; var sodium = 0; var logged = 0; var planned = 0
        for (i in 0..6) {
            val d = start.plusDays(i.toLong())
            val key = d.toString()
            val type = prefs.dayTypeFor(d.dayOfWeek)
            if (type.strength || type.name == "WALK_CORE") planned++
            val tot = totals(meals[key].orEmpty(), drinks[key].orEmpty())
            val hasData = meals[key] != null || drinks[key] != null || days[key] != null
            if (hasData) logged++
            if ((days[key]?.steps ?: 0) >= t.stepsMin) steps++
            if (tot.waterMl >= waterTargetMl(d)) water++
            if (meals[key] != null && tot.proteinG >= t.proteinFloorG) protein++
            if (tot.lateMeal) late++
            if (meals[key] != null && tot.sodiumMg <= t.sodiumMaxMg) sodium++
        }
        val reasons = days.values.mapNotNull { it.skipReason } + sessions.filter { it.recovery }.mapNotNull { it.readinessReasons.takeIf { r -> r.isNotBlank() } }
        return WeekStats(
            trainingPlanned = planned,
            trainingDone = sessions.count { it.completed && !it.recovery },
            recoveryDays = sessions.count { it.recovery } + days.values.count { it.recoveryDay },
            stepDaysHit = steps,
            waterDaysHit = water,
            proteinDaysHit = protein,
            lateEatingDays = late,
            sodiumDaysUnderCap = sodium,
            daysLogged = logged,
            skipReasons = reasons,
        )
    }

    companion object {
        fun create(context: Context): Repository {
            val db = Room.databaseBuilder(context, FitDatabase::class.java, "murtdah_fit.db").build()
            return Repository(context.applicationContext, db.dao(), Prefs(context))
        }
    }
}
