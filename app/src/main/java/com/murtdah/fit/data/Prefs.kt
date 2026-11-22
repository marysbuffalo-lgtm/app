package com.murtdah.fit.data

import android.content.Context
import android.content.SharedPreferences
import com.murtdah.fit.domain.DayType
import com.murtdah.fit.domain.ProgramSeed
import com.murtdah.fit.domain.ReminderSettings
import com.murtdah.fit.domain.RestDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.LocalTime

data class Targets(
    val kcal: Int = 2_100,
    val proteinG: Int = 170,
    val proteinFloorG: Int = 150,
    val fatG: Int = 65,
    val carbsG: Int = 210,
    val sodiumMaxMg: Int = 2_300,
    val fiberG: Int = 30,
    val waterMl: Int = 2_500,
    val waterRampToMl: Int = 3_500,
    val stepsMin: Int = 8_000,
    val stepsTarget: Int = 10_000,
) {
    companion object {
        const val MIN_KCAL = 2_000
        const val MIN_PROTEIN_FLOOR = 150
    }

    fun safe(): Targets = copy(
        kcal = kcal.coerceAtLeast(MIN_KCAL),
        proteinFloorG = proteinFloorG.coerceAtLeast(MIN_PROTEIN_FLOOR),
        proteinG = proteinG.coerceAtLeast(proteinFloorG.coerceAtLeast(MIN_PROTEIN_FLOOR)),
        waterMl = waterMl.coerceIn(1_000, 5_000),
        waterRampToMl = waterRampToMl.coerceIn(waterMl.coerceIn(1_000, 5_000), 5_000),
    )
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("murtdah_fit", Context.MODE_PRIVATE)
    private val version = MutableStateFlow(0)
    val changes: StateFlow<Int> = version

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        version.value++
    }

    var targets: Targets
        get() = Targets(
            kcal = sp.getInt("t_kcal", 2_100),
            proteinG = sp.getInt("t_protein", 170),
            proteinFloorG = sp.getInt("t_protein_floor", 150),
            fatG = sp.getInt("t_fat", 65),
            carbsG = sp.getInt("t_carbs", 210),
            sodiumMaxMg = sp.getInt("t_sodium", 2_300),
            fiberG = sp.getInt("t_fiber", 30),
            waterMl = sp.getInt("t_water", 2_500),
            waterRampToMl = sp.getInt("t_water_ramp", 3_500),
            stepsMin = sp.getInt("t_steps_min", 8_000),
            stepsTarget = sp.getInt("t_steps_target", 10_000),
        ).safe()
        set(v) {
            val t = v.safe()
            edit {
                putInt("t_kcal", t.kcal); putInt("t_protein", t.proteinG); putInt("t_protein_floor", t.proteinFloorG)
                putInt("t_fat", t.fatG); putInt("t_carbs", t.carbsG); putInt("t_sodium", t.sodiumMaxMg); putInt("t_fiber", t.fiberG)
                putInt("t_water", t.waterMl); putInt("t_water_ramp", t.waterRampToMl)
                putInt("t_steps_min", t.stepsMin); putInt("t_steps_target", t.stepsTarget)
            }
        }

    fun dayTypeFor(day: DayOfWeek): DayType =
        sp.getString("week_${day.name}", null)?.let { runCatching { DayType.valueOf(it) }.getOrNull() }
            ?: ProgramSeed.defaultWeek.getValue(day)

    fun setDayType(day: DayOfWeek, type: DayType) = edit { putString("week_${day.name}", type.name) }

    fun stepsGoalFor(day: DayOfWeek): Int = ProgramSeed.plans.getValue(dayTypeFor(day)).stepsGoal

    var reminders: ReminderSettings
        get() {
            val d = ReminderSettings()
            fun t(key: String, def: LocalTime) = sp.getString(key, null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: def
            return d.copy(
                firstMeal = t("r_first_meal", d.firstMeal),
                workoutTime = t("r_workout", d.workoutTime),
                eatingWarning = t("r_eat_warn", d.eatingWarning),
                eatingClose = t("r_eat_close", d.eatingClose),
                proteinCheck = t("r_protein", d.proteinCheck),
                weighIn = t("r_weigh", d.weighIn),
                bedtime = t("r_bed", d.bedtime),
                quietStart = t("r_quiet_start", d.quietStart),
                quietEnd = t("r_quiet_end", d.quietEnd),
                waterEveryMin = sp.getInt("r_water_every", d.waterEveryMin),
            )
        }
        set(v) = edit {
            putString("r_first_meal", v.firstMeal.toString()); putString("r_workout", v.workoutTime.toString())
            putString("r_eat_warn", v.eatingWarning.toString()); putString("r_eat_close", v.eatingClose.toString())
            putString("r_protein", v.proteinCheck.toString()); putString("r_weigh", v.weighIn.toString())
            putString("r_bed", v.bedtime.toString()); putString("r_quiet_start", v.quietStart.toString())
            putString("r_quiet_end", v.quietEnd.toString()); putInt("r_water_every", v.waterEveryMin)
        }

    var restDefaults: RestDefaults
        get() {
            val overrides = sp.getString("rest_overrides", "")!!.split(';').filter { it.contains('=') }
                .associate { it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 60) }
            return RestDefaults(
                strengthSec = sp.getInt("rest_strength", 70),
                mobilitySec = sp.getInt("rest_mobility", 20),
                betweenPhasesSec = sp.getInt("rest_phases", 105),
                overrides = overrides,
            )
        }
        set(v) = edit {
            putInt("rest_strength", v.strengthSec.coerceIn(15, 300)); putInt("rest_mobility", v.mobilitySec.coerceIn(0, 120))
            putInt("rest_phases", v.betweenPhasesSec.coerceIn(30, 300))
            putString("rest_overrides", v.overrides.entries.joinToString(";") { "${it.key}=${it.value}" })
        }

    var soundOn: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = edit { putBoolean("sound", v) }
    var voiceOn: Boolean
        get() = sp.getBoolean("voice", false)
        set(v) = edit { putBoolean("voice", v) }
    var vibrationOn: Boolean
        get() = sp.getBoolean("vibration", true)
        set(v) = edit { putBoolean("vibration", v) }
    var metronomeOn: Boolean
        get() = sp.getBoolean("metronome", false)
        set(v) = edit { putBoolean("metronome", v) }

    var weightInPounds: Boolean
        get() = sp.getBoolean("unit_lb", false)
        set(v) = edit { putBoolean("unit_lb", v) }

    var preferPhoneSensor: Boolean
        get() = sp.getBoolean("prefer_sensor", false)
        set(v) = edit { putBoolean("prefer_sensor", v) }

    var burnBaseKcal: Int
        get() = sp.getInt("burn_base", 2_000)
        set(v) = edit { putInt("burn_base", v.coerceIn(1_200, 4_000)) }
    var burnPerStep: Float
        get() = sp.getFloat("burn_step", 0.065f)
        set(v) = edit { putFloat("burn_step", v.coerceIn(0.02f, 0.15f)) }

    var setupChecklist: Set<String>
        get() = sp.getStringSet("setup", emptySet())!!
        set(v) = edit { putStringSet("setup", v) }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = edit { putBoolean("onboarded", v) }

    fun sensorBaseline(date: String): Float? = if (sp.getString("sensor_date", null) == date) sp.getFloat("sensor_base", 0f) else null
    fun setSensorBaseline(date: String, value: Float) = sp.edit().putString("sensor_date", date).putFloat("sensor_base", value).apply()
    var sensorLast: Float
        get() = sp.getFloat("sensor_last", 0f)
        set(v) { sp.edit().putFloat("sensor_last", v).apply() }

    var snapshot: String?
        get() = sp.getString("session_snapshot", null)
        set(v) { sp.edit().putString("session_snapshot", v).apply() }

    fun snoozeUntil(type: String): Long = sp.getLong("snooze_$type", 0)
    fun setSnooze(type: String, until: Long) = sp.edit().putLong("snooze_$type", until).apply()

    fun restoreDefaults() = edit {
        val keep = setOf("onboarded", "sensor_date", "sensor_base", "sensor_last", "session_snapshot", "setup")
        sp.all.keys.filter { it !in keep }.forEach { remove(it) }
    }
}
