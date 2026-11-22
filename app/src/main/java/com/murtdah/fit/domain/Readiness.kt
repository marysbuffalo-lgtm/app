package com.murtdah.fit.domain

enum class Light { GREEN, AMBER, RED }

enum class SleepBand(val label: String) { UNDER_5("Under 5 h"), FIVE_TO_6_5("5 to 6.5 h"), OVER_6_5("6.5 h or more") }

enum class Feeling(val label: String) { NORMAL("Normal"), TIRED("Tired"), UNWELL("Unwell") }

sealed interface Fix {
    data class Delay(val minutes: Int) : Fix
    data object DrinkWater : Fix
    data object DropOneSet : Fix
    data class SwapFor(val areas: Set<BodyArea>) : Fix
    data object UpperBodyOnly : Fix
}

data class Finding(val light: Light, val message: String, val fix: Fix? = null)

data class ReadinessInput(
    val minutesSinceLastMeal: Int?,
    val lastMealKcal: Int?,
    val waterLast2hMl: Int,
    val sleep: SleepBand,
    val pain: Set<BodyArea>,
    val soreness: Int,
    val sorenessSameGroup: Boolean,
    val feeling: Feeling,
    val redFlagSymptoms: Boolean,
    val dayType: DayType,
)

data class ReadinessResult(val light: Light, val findings: List<Finding>) {
    val fixes: List<Fix> get() = findings.mapNotNull { it.fix }
}

object ReadinessRules {
    const val LARGE_MEAL_KCAL = 800
    const val LIGHT_SNACK_KCAL = 300
    const val RED_FLAG_MESSAGE = "Do not train. If it continues or is severe, seek medical care."

    fun requiredGapMinutes(mealKcal: Int): Int = when {
        mealKcal > LARGE_MEAL_KCAL -> 120
        mealKcal >= LIGHT_SNACK_KCAL -> 60
        else -> 30
    }

    fun evaluate(input: ReadinessInput): ReadinessResult {
        val findings = mutableListOf<Finding>()

        if (input.redFlagSymptoms) {
            findings += Finding(Light.RED, RED_FLAG_MESSAGE)
        }
        if (input.feeling == Feeling.UNWELL) {
            findings += Finding(Light.RED, "Unwell days are recovery days. Rest and fluids.")
        }
        when (input.sleep) {
            SleepBand.UNDER_5 ->
                if (input.dayType.strength && input.dayType.lowerBody) {
                    findings += Finding(Light.RED, "Under 5 hours of sleep. Heavy leg work today raises injury risk.")
                } else {
                    findings += Finding(Light.AMBER, "Under 5 hours of sleep. Cut one set per exercise.", Fix.DropOneSet)
                }
            SleepBand.FIVE_TO_6_5 -> findings += Finding(Light.AMBER, "Short sleep. Cut one set per exercise.", Fix.DropOneSet)
            SleepBand.OVER_6_5 -> Unit
        }
        if (input.feeling == Feeling.TIRED && input.sleep == SleepBand.OVER_6_5) {
            findings += Finding(Light.AMBER, "Feeling tired. Cut one set per exercise and see how it goes.", Fix.DropOneSet)
        }

        val minutes = input.minutesSinceLastMeal
        val kcal = input.lastMealKcal
        if (minutes != null && kcal != null) {
            val need = requiredGapMinutes(kcal)
            if (minutes < need) {
                val wait = need - minutes
                findings += Finding(Light.AMBER, "Last meal was $minutes min ago ($kcal kcal). Waiting $wait min will feel better.", Fix.Delay(wait))
            }
        }

        if (input.waterLast2hMl < 500) {
            findings += Finding(Light.AMBER, "Only ${input.waterLast2hMl} ml of water in the last 2 hours. Drink 500 ml now.", Fix.DrinkWater)
        }

        if (input.pain.isNotEmpty()) {
            val names = input.pain.joinToString { it.label.lowercase() }
            findings += Finding(Light.AMBER, "Pain flagged: $names. Affected exercises will be swapped.", Fix.SwapFor(input.pain))
        }

        if (input.soreness >= 7 && input.sorenessSameGroup && input.dayType.strength) {
            if (input.dayType.lowerBody) {
                findings += Finding(Light.AMBER, "Very sore in the same muscles. Switch to upper body today.", Fix.UpperBodyOnly)
            } else {
                findings += Finding(Light.AMBER, "Very sore in the same muscles. Lighter loading today.", Fix.DropOneSet)
            }
        }

        val light = when {
            findings.any { it.light == Light.RED } -> Light.RED
            findings.any { it.light == Light.AMBER } -> Light.AMBER
            else -> Light.GREEN
        }
        return ReadinessResult(light, findings.distinctBy { it.message })
    }
}
