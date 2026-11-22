package com.murtdah.fit.domain

import kotlin.math.roundToInt

data class Step(
    val exerciseId: String,
    val name: String,
    val phase: Phase,
    val setNumber: Int,
    val totalSets: Int,
    val repsMin: Int,
    val repsMax: Int,
    val seconds: Int,
    val perSide: Boolean,
    val loadKg: Double?,
    val loadText: String,
    val tempoDownSec: Int,
    val formCue: String,
    val note: String?,
    val restAfterSec: Int,
    val warmup: Boolean,
    val strength: Boolean,
    val optional: Boolean,
    val round: Int = 0,
) {
    val timed: Boolean get() = seconds > 0

    fun targetText(): String {
        val side = if (perSide) " per side" else ""
        return when {
            timed -> "$seconds s$side"
            repsMin == repsMax -> "$repsMax reps$side"
            else -> "$repsMin to $repsMax reps$side"
        }
    }
}

data class Adjustments(
    val dropOneSet: Boolean = false,
    val painAreas: Set<BodyArea> = emptySet(),
    val upperBodyOnly: Boolean = false,
    val recoveryOnly: Boolean = false,
    val skipHops: Boolean = false,
)

data class RestDefaults(
    val strengthSec: Int = 70,
    val mobilitySec: Int = 20,
    val betweenPhasesSec: Int = 105,
    val overrides: Map<String, Int> = emptyMap(),
)

object SessionPlanner {
    const val RAMP_REST_SEC = 30

    fun phasesFor(type: DayType): List<Phase> = when (type) {
        DayType.WALK_CORE, DayType.MOBILITY -> listOf(Phase.WARM_UP, Phase.MAIN, Phase.POSTURE, Phase.COOLDOWN)
        else -> listOf(Phase.WARM_UP, Phase.ACTIVATION, Phase.MAIN, Phase.POSTURE, Phase.COOLDOWN)
    }

    fun swap(p: Prescription, areas: Set<BodyArea>): Prescription {
        val ex = Library[p.exerciseId]
        val target = areas.firstNotNullOfOrNull { ex.swaps[it] } ?: return p
        return swapTo(p, target)
    }

    fun swapTo(p: Prescription, targetId: String): Prescription {
        val target = Library[targetId]
        val keepLoad = target.usesDumbbells
        return p.copy(
            exerciseId = targetId,
            loadKg = if (keepLoad) p.loadKg ?: 3.0 else null,
            dumbbellCount = if (keepLoad) maxOf(p.dumbbellCount, 1) else 0,
            perSide = if (p.timed) p.perSide else p.perSide && target.id in setOf("single_leg_rdl", "split_squat", "reverse_lunge", "bulgarian_split_squat", "one_arm_row"),
        )
    }

    private fun fixSwapChain(p: Prescription, areas: Set<BodyArea>): Prescription {
        var cur = p
        repeat(3) {
            val next = swap(cur, areas)
            if (next.exerciseId == cur.exerciseId) return cur
            cur = next
        }
        return cur
    }

    fun build(
        type: DayType,
        adj: Adjustments = Adjustments(),
        progress: Map<String, ProgressState> = emptyMap(),
        rest: RestDefaults = RestDefaults(),
    ): List<Step> {
        if (adj.recoveryOnly) {
            val blocks = listOf(
                Phase.POSTURE to ProgramSeed.posture,
                Phase.COOLDOWN to ProgramSeed.cooldown.filter { it.exerciseId == "diaphragmatic_breathing" },
            )
            return expand(blocks, adj.painAreas, rest)
        }

        val effectiveType = if (adj.upperBodyOnly && type.lowerBody) DayType.UPPER_A else type
        val plan = ProgramSeed.plans.getValue(effectiveType)

        val warm = ProgramSeed.warmUp.let { list ->
            if (adj.skipHops || BodyArea.KNEES in adj.painAreas || BodyArea.FEET in adj.painAreas) {
                list.map { if (it.exerciseId == "low_impact_hops") swapTo(it, "march_in_place") else it }
            } else list
        }

        val activation = when {
            !effectiveType.strength -> emptyList()
            effectiveType.lowerBody -> ProgramSeed.activationLower
            else -> ProgramSeed.activationUpper
        }

        var main = plan.main.map { p ->
            val ex = Library[p.exerciseId]
            Progression.adjust(p, progress[p.exerciseId], ex)
        }
        if (adj.dropOneSet && plan.circuitRounds == 0) {
            main = main.map { if (it.sets > 1) it.copy(sets = it.sets - 1) else it }
        }

        val blocks = mutableListOf<Pair<Phase, List<Prescription>>>()
        for (phase in phasesFor(effectiveType)) {
            when (phase) {
                Phase.WARM_UP -> blocks += phase to warm
                Phase.ACTIVATION -> if (activation.isNotEmpty()) blocks += phase to activation
                Phase.MAIN -> blocks += phase to main
                Phase.POSTURE -> blocks += phase to ProgramSeed.posture
                Phase.COOLDOWN -> blocks += phase to ProgramSeed.cooldown
            }
        }

        val rounds = if (adj.dropOneSet && plan.circuitRounds > 0) maxOf(plan.circuitRounds - 1, 1) else plan.circuitRounds
        return expand(blocks, adj.painAreas, rest, circuitRounds = rounds, rampUp = effectiveType.strength && plan.circuitRounds == 0)
    }

    private fun restFor(p: Prescription, ex: Exercise, rest: RestDefaults): Int =
        rest.overrides[p.exerciseId] ?: p.restSec ?: when (ex.kind) {
            Kind.STRENGTH -> rest.strengthSec
            Kind.BREATHING -> 0
            Kind.HOLD -> 45
            else -> rest.mobilitySec
        }

    private fun expand(
        blocks: List<Pair<Phase, List<Prescription>>>,
        pain: Set<BodyArea>,
        rest: RestDefaults,
        circuitRounds: Int = 0,
        rampUp: Boolean = false,
    ): List<Step> {
        val out = mutableListOf<Step>()
        blocks.forEachIndexed { bi, (phase, rawItems) ->
            val items = rawItems.map { fixSwapChain(it, pain) }
            val lastBlock = bi == blocks.lastIndex
            val start = out.size

            if (phase == Phase.MAIN && circuitRounds > 0) {
                for (r in 1..circuitRounds) {
                    items.forEachIndexed { ii, p ->
                        val ex = Library[p.exerciseId]
                        val lastInRound = ii == items.lastIndex
                        val restSec = when {
                            lastInRound && r == circuitRounds -> rest.betweenPhasesSec
                            lastInRound -> 120
                            else -> p.restSec ?: 15
                        }
                        out += step(p, ex, phase, r, circuitRounds, restSec, warmup = false, round = r)
                    }
                }
                return@forEachIndexed
            }

            if (phase == Phase.MAIN && rampUp) {
                val first = items.firstOrNull { it.loadKg != null && !it.timed && Library[it.exerciseId].kind == Kind.STRENGTH }
                if (first != null) {
                    val ex = Library[first.exerciseId]
                    val r1 = (first.repsMax * 0.5).roundToInt().coerceAtLeast(3)
                    val r2 = (first.repsMax * 0.7).roundToInt().coerceAtLeast(4)
                    val light = first.copy(repsMin = r1, repsMax = r1, loadKg = if (first.loadKg!! > 3.0) 3.0 else null, sets = 2)
                    val working = first.copy(repsMin = r2, repsMax = r2, sets = 2)
                    out += step(light, ex, phase, 1, 2, RAMP_REST_SEC, warmup = true, label = "Ramp-up: ")
                    out += step(working, ex, phase, 2, 2, RAMP_REST_SEC, warmup = true, label = "Ramp-up: ")
                }
            }

            items.forEach { p ->
                val ex = Library[p.exerciseId]
                val r = restFor(p, ex, rest)
                for (s in 1..p.sets) {
                    out += step(p, ex, phase, s, p.sets, r, warmup = phase != Phase.MAIN)
                }
            }

            if (out.size > start) {
                val last = out.last()
                out[out.lastIndex] = last.copy(restAfterSec = if (lastBlock || last.restAfterSec == 0 && Library[last.exerciseId].kind == Kind.BREATHING) 0 else rest.betweenPhasesSec)
            }
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(restAfterSec = 0)
        return out
    }

    private fun step(
        p: Prescription, ex: Exercise, phase: Phase, set: Int, total: Int, restSec: Int,
        warmup: Boolean, round: Int = 0, label: String = "",
    ) = Step(
        exerciseId = p.exerciseId,
        name = label + ex.name,
        phase = phase,
        setNumber = set,
        totalSets = total,
        repsMin = p.repsMin,
        repsMax = p.repsMax,
        seconds = p.seconds,
        perSide = p.perSide,
        loadKg = p.loadKg,
        loadText = p.loadText(),
        tempoDownSec = p.tempoDownSec,
        formCue = ex.formCue,
        note = p.note,
        restAfterSec = restSec,
        warmup = warmup,
        strength = ex.kind == Kind.STRENGTH && !warmup,
        optional = p.optional,
        round = round,
    )

    fun estimateSeconds(steps: List<Step>): Int = steps.sumOf { s ->
        val work = if (s.timed) s.seconds * (if (s.perSide) 2 else 1) else {
            val perRep = 3 + s.tempoDownSec
            s.repsMax * perRep * (if (s.perSide) 2 else 1)
        }
        work + s.restAfterSec
    }

    fun replaceExercise(steps: List<Step>, fromIndex: Int, newExerciseId: String): List<Step> {
        val oldId = steps.getOrNull(fromIndex)?.exerciseId ?: return steps
        val ex = Library[newExerciseId]
        val keepLoad = ex.usesDumbbells
        return steps.mapIndexed { i, s ->
            if (i >= fromIndex && s.exerciseId == oldId && s.phase == steps[fromIndex].phase) {
                val load = if (keepLoad) s.loadKg ?: 3.0 else null
                s.copy(
                    exerciseId = newExerciseId,
                    name = if (s.name.startsWith("Ramp-up: ")) "Ramp-up: ${ex.name}" else ex.name,
                    formCue = ex.formCue,
                    loadKg = load,
                    loadText = load?.let { "${formatKg(it)} kg" } ?: "Bodyweight",
                    strength = ex.kind == Kind.STRENGTH && !s.warmup,
                )
            } else s
        }
    }

    fun applyPain(steps: List<Step>, fromIndex: Int, area: BodyArea): List<Step> {
        var out = steps
        var i = fromIndex
        while (i < out.size) {
            val target = Library[out[i].exerciseId].swaps[area]
            if (target != null) out = replaceExercise(out, i, target)
            i++
        }
        return out
    }
}
