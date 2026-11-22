package com.murtdah.fit.domain

enum class Rung(val label: String) {
    BASE("Base"),
    SLOW_TEMPO("Slower tempo: 4 s down, 1 to 2 s pause"),
    HARDER_VARIATION("Harder variation"),
    EXTRA_SET("Extra set"),
    MORE_LOAD("More load"),
}

data class ProgressState(
    val exerciseId: String,
    val rung: Rung = Rung.BASE,
    val loadKg: Double? = null,
    val targetReps: Int = 0,
    val stalledAtTop: Int = 0,
)

data class SetResult(val reps: Int, val rir: Int, val loadKg: Double?)

data class ProgressionOutcome(val next: ProgressState, val message: String, val equipmentLimit: Boolean)

object Progression {
    const val MAX_OWNED_KG = 5.0
    const val EQUIPMENT_LIMIT_MESSAGE =
        "Your equipment is the limit. Adjustable dumbbells (up to 20 to 25 kg) or resistance bands will unlock progress."

    fun ladder(exercise: Exercise, loadKg: Double?): List<Rung> = buildList {
        add(Rung.BASE)
        add(Rung.SLOW_TEMPO)
        if (exercise.harderVariation != null) add(Rung.HARDER_VARIATION)
        add(Rung.EXTRA_SET)
        if (exercise.usesDumbbells && loadKg != null && loadKg < MAX_OWNED_KG) add(Rung.MORE_LOAD)
    }

    fun hitTop(sets: List<SetResult>, repsMax: Int): Boolean =
        sets.isNotEmpty() && sets.all { it.reps >= repsMax && it.rir in 1..4 }

    fun apply(
        exercise: Exercise,
        prescription: Prescription,
        state: ProgressState,
        sets: List<SetResult>,
    ): ProgressionOutcome {
        val load = state.loadKg ?: prescription.loadKg
        val ladder = ladder(exercise, load)
        val idx = ladder.indexOf(state.rung).coerceAtLeast(0)
        val repsMax = prescription.repsMax
        val repsMin = prescription.repsMin

        if (prescription.timed || repsMax == 0) {
            return ProgressionOutcome(state, "Timed work. Keep good form.", false)
        }

        if (!hitTop(sets, repsMax)) {
            val best = sets.minOfOrNull { it.reps } ?: repsMin
            val target = (best + 1).coerceIn(repsMin, repsMax)
            val anyFailure = sets.any { it.rir == 0 }
            val msg = if (anyFailure) {
                "You hit 0 reps in reserve. Next time stop 1 to 2 reps short of failure. Target $target reps."
            } else {
                "Aim for $target reps on every set next time."
            }
            return ProgressionOutcome(state.copy(targetReps = target, stalledAtTop = 0), msg, false)
        }

        if (idx >= ladder.lastIndex) {
            val stalled = state.stalledAtTop + 1
            val limit = stalled >= 2
            val msg = if (limit) EQUIPMENT_LIMIT_MESSAGE else "Top of the ladder. Keep the reps clean."
            return ProgressionOutcome(state.copy(stalledAtTop = stalled, targetReps = repsMax), msg, limit)
        }

        val nextRung = ladder[idx + 1]
        val nextLoad = if (nextRung == Rung.MORE_LOAD) MAX_OWNED_KG else load
        val next = state.copy(rung = nextRung, loadKg = nextLoad, targetReps = repsMin, stalledAtTop = 0)
        return ProgressionOutcome(next, "Top of the range on every set. Next step: ${nextRung.label}.", false)
    }

    fun adjust(prescription: Prescription, state: ProgressState?, exercise: Exercise): Prescription {
        if (state == null) return prescription
        val rungs = Rung.entries
        val at = rungs.indexOf(state.rung)
        var p = prescription
        if (at >= rungs.indexOf(Rung.SLOW_TEMPO)) p = p.copy(tempoDownSec = 4, note = listOfNotNull(p.note, "4 s down, 1 to 2 s pause").joinToString(". "))
        if (at >= rungs.indexOf(Rung.HARDER_VARIATION) && exercise.harderVariation != null) {
            p = p.copy(note = listOfNotNull(p.note, exercise.harderVariation).joinToString(". "))
        }
        if (at >= rungs.indexOf(Rung.EXTRA_SET)) p = p.copy(sets = p.sets + 1)
        if (state.loadKg != null && p.loadKg != null) p = p.copy(loadKg = state.loadKg)
        return p
    }
}

enum class DeloadAdvice { NONE, DELOAD_WEEK, RESUME_AT_80 }

object Deload {
    fun advise(consecutiveTrainedWeeks: Int, consecutiveMissedWeeks: Int): DeloadAdvice = when {
        consecutiveMissedWeeks >= 2 -> DeloadAdvice.RESUME_AT_80
        consecutiveTrainedWeeks >= 4 -> DeloadAdvice.DELOAD_WEEK
        else -> DeloadAdvice.NONE
    }

    fun message(advice: DeloadAdvice): String? = when (advice) {
        DeloadAdvice.NONE -> null
        DeloadAdvice.DELOAD_WEEK -> "4 weeks of steady training. Take a lighter week: one set fewer on every exercise."
        DeloadAdvice.RESUME_AT_80 -> "You missed 2 weeks. Restart at about 80% volume: one set fewer this week."
    }
}
