package com.murtdah.fit.domain

import java.time.DayOfWeek

enum class Phase(val label: String) {
    WARM_UP("Warm-up"),
    ACTIVATION("Activation"),
    MAIN("Main session"),
    POSTURE("Posture block"),
    COOLDOWN("Cooldown"),
}

enum class Kind { STRENGTH, MOBILITY, POSTURE, HOLD, BREATHING }

enum class BodyArea(val label: String) {
    KNEES("Knees"),
    SHOULDERS("Shoulders"),
    LOWER_BACK("Lower back"),
    WRISTS("Wrists"),
    FEET("Feet"),
}

enum class MuscleGroup { LOWER, UPPER, CORE, FULL }

data class Exercise(
    val id: String,
    val name: String,
    val formCue: String,
    val muscles: String,
    val kind: Kind,
    val group: MuscleGroup,
    val swaps: Map<BodyArea, String> = emptyMap(),
    val alternatives: List<String> = emptyList(),
    val harderVariation: String? = null,
    val usesDumbbells: Boolean = false,
)

data class Prescription(
    val exerciseId: String,
    val sets: Int,
    val repsMin: Int = 0,
    val repsMax: Int = 0,
    val seconds: Int = 0,
    val perSide: Boolean = false,
    val loadKg: Double? = null,
    val dumbbellCount: Int = 0,
    val tempoDownSec: Int = 0,
    val restSec: Int? = null,
    val optional: Boolean = false,
    val note: String? = null,
) {
    val timed: Boolean get() = seconds > 0

    fun loadText(): String = when {
        loadKg == null -> "Bodyweight"
        dumbbellCount >= 2 -> "2 x ${formatKg(loadKg)} kg"
        else -> "${formatKg(loadKg)} kg"
    }

    fun targetText(): String {
        val side = if (perSide) " per side" else ""
        return when {
            timed -> "${seconds} s$side"
            repsMin == repsMax -> "$repsMax reps$side"
            else -> "$repsMin to $repsMax reps$side"
        }
    }
}

fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()

enum class DayType(val strength: Boolean, val lowerBody: Boolean) {
    LOWER_A(true, true),
    UPPER_A(true, false),
    WALK_CORE(false, false),
    LOWER_B(true, true),
    UPPER_B(true, false),
    MOBILITY(false, false),
    CIRCUIT(true, true),
}

data class DayPlan(
    val type: DayType,
    val title: String,
    val stepsGoal: Int,
    val main: List<Prescription>,
    val circuitRounds: Int = 0,
    val note: String? = null,
)

object Library {
    private val list = listOf(
        Exercise("arm_swings", "Arm swings", "Swing the arms across the chest, then open wide. Relaxed shoulders.", "Shoulders, chest", Kind.MOBILITY, MuscleGroup.UPPER),
        Exercise("torso_rotations", "Standing torso rotations", "Hips stay mostly square. Turn the ribs, smooth and controlled.", "Trunk, hips", Kind.MOBILITY, MuscleGroup.CORE),
        Exercise("high_knee_marches", "High-knee marches", "Brace the core, stand tall, lift each knee to hip height.", "Hips, core", Kind.MOBILITY, MuscleGroup.LOWER),
        Exercise(
            "half_squats", "Half squats", "Sit back about halfway, knees track over toes, 2 second pace.", "Quads, glutes", Kind.MOBILITY, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "march_in_place"),
        ),
        Exercise(
            "low_impact_hops", "Low-impact hops", "Feet barely leave the floor. Soft landings on the balls of the feet.", "Calves, ankles", Kind.MOBILITY, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "march_in_place", BodyArea.FEET to "march_in_place"),
            alternatives = listOf("march_in_place"),
        ),
        Exercise("march_in_place", "March in place", "Easy march, swing the arms, breathe steadily.", "Hips, calves", Kind.MOBILITY, MuscleGroup.LOWER),
        Exercise(
            "deep_squats", "Deep squat hold", "Only as deep as comfortable. Hold a wall or doorframe. 3 second pause, no bouncing. Skip if sharp knee pain.", "Hips, ankles", Kind.MOBILITY, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "glute_bridge"),
        ),
        Exercise("diaphragmatic_breathing", "Diaphragmatic breathing", "4 s in through the nose, belly expands. 6 s out through the mouth.", "Diaphragm", Kind.BREATHING, MuscleGroup.CORE),

        Exercise(
            "towel_pass_through", "Towel pass-throughs", "Hold the towel wide. Sweep it over and behind you. Stop if painful. This is not an overhead press.", "Shoulders, upper back", Kind.POSTURE, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "scapular_retraction"),
        ),
        Exercise(
            "t_raise", "T-raise", "Lie face down, thumbs up, lift straight arms out to the sides. Squeeze the shoulder blades.", "Mid back, rear shoulders", Kind.POSTURE, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "scapular_retraction"),
        ),
        Exercise(
            "bird_dog", "Bird dog", "Opposite arm and leg reach long. Hold 2 seconds. Keep the back flat like a table.", "Core, glutes, back", Kind.POSTURE, MuscleGroup.CORE,
            swaps = mapOf(BodyArea.WRISTS to "dead_bug"),
        ),
        Exercise(
            "wall_angel", "Wall angels", "Head, upper back and hips touch the wall. Slide the arms up and down slowly.", "Upper back, shoulders", Kind.POSTURE, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "chin_tuck"),
        ),
        Exercise("chin_tuck", "Chin tucks against the wall", "Head on the wall. Pull the chin straight back, no tilting. Hold 3 seconds.", "Deep neck flexors", Kind.POSTURE, MuscleGroup.UPPER),
        Exercise("dead_bug", "Dead bug", "Lower back stays pressed to the floor. Reach the opposite arm and leg away slowly.", "Deep core", Kind.POSTURE, MuscleGroup.CORE),

        Exercise("ankle_rocks", "Ankle rocks", "Half kneel, knee drifts over the toes, heel stays down.", "Ankles", Kind.MOBILITY, MuscleGroup.LOWER),
        Exercise("hip_hinge_drill", "Hip hinge, no weight", "Push the hips back, flat back, soft knees. Stand by squeezing the glutes.", "Hamstrings, glutes", Kind.MOBILITY, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.LOWER_BACK to "glute_bridge"),
        ),
        Exercise("scapular_push_up", "Scapular push-ups", "Arms straight. Let the chest sink between the shoulder blades, then push the floor away.", "Serratus, upper back", Kind.MOBILITY, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.WRISTS to "scapular_retraction", BodyArea.SHOULDERS to "scapular_retraction"),
        ),
        Exercise("scapular_retraction", "Scapular retractions", "Hands on the wall. Squeeze the shoulder blades together for 2 seconds.", "Mid back", Kind.MOBILITY, MuscleGroup.UPPER),
        Exercise("wall_push_up", "Wall push-up", "Hands on the wall at chest height, body straight, slow down and push away.", "Chest, triceps", Kind.STRENGTH, MuscleGroup.UPPER),

        Exercise(
            "tempo_squat", "Tempo goblet squat", "Hold the dumbbells at the shoulders, elbows down. 4 seconds down, knees over toes, stand tall.", "Quads, glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "glute_bridge", BodyArea.LOWER_BACK to "glute_bridge"),
            alternatives = listOf("glute_bridge", "romanian_deadlift"),
            harderVariation = "Squat with a 2 second pause at the bottom",
            usesDumbbells = true,
        ),
        Exercise(
            "bulgarian_split_squat", "Bulgarian split squat", "Rear foot on a chair or sofa. Drop the back knee straight down, front heel stays down.", "Quads, glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "glute_bridge"),
            alternatives = listOf("glute_bridge", "single_leg_rdl"),
            harderVariation = "Hold the 5 kg dumbbells",
            usesDumbbells = true,
        ),
        Exercise(
            "single_leg_rdl", "Single-leg Romanian deadlift", "Hinge on one leg, the free leg reaches back. Flat back, hips square.", "Hamstrings, glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.LOWER_BACK to "glute_bridge"),
            alternatives = listOf("glute_bridge"),
            harderVariation = "Pause 2 seconds at the bottom",
            usesDumbbells = true,
        ),
        Exercise(
            "slider_hamstring_curl", "Slider hamstring curl", "Heels on the slider, hips up. Slide out slowly, curl back in.", "Hamstrings", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.LOWER_BACK to "glute_bridge"),
            alternatives = listOf("glute_bridge"),
            harderVariation = "Single-leg slider curl",
        ),
        Exercise(
            "calf_raise", "Single-leg calf raise", "Hold a wall for balance. Full rise, slow lower.", "Calves", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.FEET to "glute_bridge"),
            harderVariation = "Raise from a step edge for a deeper stretch",
        ),
        Exercise(
            "plank", "Forearm plank", "Elbows under the shoulders, squeeze the glutes, ribs down.", "Core", Kind.HOLD, MuscleGroup.CORE,
            swaps = mapOf(BodyArea.SHOULDERS to "dead_bug", BodyArea.LOWER_BACK to "dead_bug"),
        ),
        Exercise(
            "romanian_deadlift", "Romanian deadlift", "Dumbbells slide down the thighs, hips back, flat back. 4 seconds down.", "Hamstrings, glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.LOWER_BACK to "glute_bridge"),
            alternatives = listOf("glute_bridge", "single_leg_rdl"),
            harderVariation = "Pause 2 seconds below the knee",
            usesDumbbells = true,
        ),
        Exercise(
            "reverse_lunge", "Reverse lunge", "Step back, drop the back knee straight down, push through the front heel.", "Quads, glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "glute_bridge"),
            alternatives = listOf("glute_bridge", "single_leg_rdl"),
            harderVariation = "Pause 2 seconds at the bottom",
            usesDumbbells = true,
        ),
        Exercise(
            "glute_bridge", "Glute bridge", "Dumbbell on the hips, drive through the heels, squeeze 2 seconds at the top.", "Glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            harderVariation = "Single-leg glute bridge",
            usesDumbbells = true,
        ),
        Exercise(
            "elevated_push_up", "Elevated push-up", "Hands on a sofa or table edge, body straight. 4 seconds down.", "Chest, triceps", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "wall_push_up", BodyArea.WRISTS to "floor_press"),
            alternatives = listOf("wall_push_up", "floor_press"),
            harderVariation = "Lower the hand height or move to the floor",
        ),
        Exercise(
            "floor_press", "Dumbbell floor press", "Lie on the floor, elbows at 45 degrees, press up, upper arms touch the floor lightly.", "Chest, triceps", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "wall_push_up"),
            alternatives = listOf("wall_push_up"),
            harderVariation = "Pause 2 seconds with the arms on the floor",
            usesDumbbells = true,
        ),
        Exercise(
            "one_arm_row", "One-arm row", "Hand and knee on a chair, flat back. Pull the dumbbell to the hip.", "Lats, mid back", Kind.STRENGTH, MuscleGroup.UPPER,
            harderVariation = "Pause 2 seconds at the top",
            usesDumbbells = true,
        ),
        Exercise(
            "lateral_raise", "Lateral raise", "Raise out to the sides to shoulder height only. Slight elbow bend.", "Side shoulders", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "scapular_retraction"),
            harderVariation = "3 second lowering",
            usesDumbbells = true,
        ),
        Exercise(
            "biceps_curl", "Biceps curl", "Elbows pinned to the sides, palms up, slow lowering.", "Biceps", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.WRISTS to "hammer_curl"),
            harderVariation = "3 second lowering",
            usesDumbbells = true,
        ),
        Exercise(
            "triceps_extension", "Lying triceps extension", "Lie down, upper arms still, bend at the elbows toward the forehead, extend.", "Triceps", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "close_grip_push_up", BodyArea.WRISTS to "close_grip_push_up"),
            harderVariation = "3 second lowering",
            usesDumbbells = true,
        ),
        Exercise(
            "bent_over_row", "Bent-over row", "Hinge forward, flat back, row both dumbbells to the ribs.", "Lats, mid back", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.LOWER_BACK to "one_arm_row"),
            alternatives = listOf("one_arm_row"),
            harderVariation = "Pause 2 seconds at the top",
            usesDumbbells = true,
        ),
        Exercise(
            "reverse_fly", "Reverse fly", "Hinge forward, arms slightly bent, open out wide, squeeze the shoulder blades.", "Rear shoulders, upper back", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.LOWER_BACK to "t_raise", BodyArea.SHOULDERS to "scapular_retraction"),
            harderVariation = "Pause 2 seconds at the top",
            usesDumbbells = true,
        ),
        Exercise(
            "close_grip_push_up", "Close-grip push-up", "Hands under the shoulders, elbows tucked, body straight. Hands on a sofa if needed.", "Triceps, chest", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "wall_push_up", BodyArea.WRISTS to "floor_press"),
            alternatives = listOf("wall_push_up", "elevated_push_up"),
            harderVariation = "4 second lowering",
        ),
        Exercise(
            "single_arm_floor_press", "Single-arm floor press", "One dumbbell, other hand on the belly to stop twisting. Elbow at 45 degrees.", "Chest, triceps, core", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "wall_push_up"),
            alternatives = listOf("floor_press"),
            harderVariation = "Pause 2 seconds at the bottom",
            usesDumbbells = true,
        ),
        Exercise(
            "hammer_curl", "Hammer curl", "Palms face each other, elbows still, slow lowering.", "Biceps, forearms", Kind.STRENGTH, MuscleGroup.UPPER,
            harderVariation = "3 second lowering",
            usesDumbbells = true,
        ),
        Exercise(
            "plank_shoulder_taps", "Plank with shoulder taps", "High plank, feet wide, tap the opposite shoulder without rocking the hips.", "Core, shoulders", Kind.STRENGTH, MuscleGroup.CORE,
            swaps = mapOf(BodyArea.WRISTS to "plank", BodyArea.SHOULDERS to "dead_bug"),
            alternatives = listOf("plank"),
        ),
        Exercise(
            "side_plank", "Side plank", "Elbow under the shoulder, body in one line. Knees down is fine.", "Obliques", Kind.HOLD, MuscleGroup.CORE,
            swaps = mapOf(BodyArea.SHOULDERS to "dead_bug"),
        ),
        Exercise("hollow_hold", "Hollow-body hold", "Lower back pressed down, shoulders and legs up. Bend the knees to make it easier.", "Core", Kind.HOLD, MuscleGroup.CORE,
            swaps = mapOf(BodyArea.LOWER_BACK to "dead_bug"),
        ),
        Exercise("glute_bridge_march", "Glute bridge march", "Hips stay high and level while the feet march.", "Glutes, core", Kind.STRENGTH, MuscleGroup.CORE,
            swaps = mapOf(BodyArea.LOWER_BACK to "dead_bug"),
        ),
        Exercise(
            "split_squat", "Split squat", "Both feet on the floor, back knee drops straight down.", "Quads, glutes", Kind.STRENGTH, MuscleGroup.LOWER,
            swaps = mapOf(BodyArea.KNEES to "glute_bridge"),
            alternatives = listOf("glute_bridge"),
        ),
        Exercise(
            "push_up", "Push-up", "Body straight, chest to a fist height off the floor. Knees or a sofa edge are fine.", "Chest, triceps", Kind.STRENGTH, MuscleGroup.UPPER,
            swaps = mapOf(BodyArea.SHOULDERS to "wall_push_up", BodyArea.WRISTS to "floor_press"),
            alternatives = listOf("elevated_push_up", "wall_push_up"),
        ),
    )

    val all: Map<String, Exercise> = list.associateBy { it.id }

    operator fun get(id: String): Exercise = all[id] ?: error("Unknown exercise $id")
}

object ProgramSeed {
    private fun p(
        id: String, sets: Int, min: Int = 0, max: Int = min, sec: Int = 0, side: Boolean = false,
        kg: Double? = null, dbs: Int = 0, tempo: Int = 0, rest: Int? = null, optional: Boolean = false, note: String? = null,
    ) = Prescription(id, sets, min, max, sec, side, kg, dbs, tempo, rest, optional, note)

    val warmUp = listOf(
        p("arm_swings", 1, sec = 30),
        p("torso_rotations", 2, 20),
        p("high_knee_marches", 2, sec = 30),
        p("half_squats", 2, 15),
        p("low_impact_hops", 3, sec = 20, optional = true, note = "Optional. Hopping is simple low-impact activity. Claims that it drains the lymph are not well supported. Keep it for warm-up and fun, not for fat loss."),
    )

    val activationLower = listOf(
        p("ankle_rocks", 1, 10, side = true),
        p("hip_hinge_drill", 1, 10),
        p("glute_bridge", 1, 12, note = "2 second squeeze at the top"),
        p("split_squat", 1, 5, side = true),
    )

    val activationUpper = listOf(
        p("scapular_push_up", 1, 10),
        p("wall_angel", 1, 10),
        p("scapular_retraction", 1, 10),
        p("wall_push_up", 1, 5),
    )

    val posture = listOf(
        p("towel_pass_through", 2, 15, note = "Stop if painful."),
        p("t_raise", 3, 12, note = "No weight, or 3 kg once easy."),
        p("bird_dog", 3, 8, side = true, note = "2 second hold"),
        p("wall_angel", 2, 10),
        p("chin_tuck", 2, 10, note = "3 second hold"),
        p("dead_bug", 2, 8, side = true),
    )

    val cooldown = listOf(
        p("deep_squats", 2, 10, note = "3 second pause at the bottom"),
        p("diaphragmatic_breathing", 1, sec = 100, rest = 0, note = "10 breaths"),
    )

    val plans: Map<DayType, DayPlan> = mapOf(
        DayType.LOWER_A to DayPlan(
            DayType.LOWER_A, "Lower A (squat pattern)", 10_000,
            listOf(
                p("tempo_squat", 4, 12, 15, kg = 5.0, dbs = 2, tempo = 4),
                p("bulgarian_split_squat", 3, 8, 12, side = true),
                p("single_leg_rdl", 3, 10, side = true, kg = 5.0, dbs = 1),
                p("slider_hamstring_curl", 3, 8, 12),
                p("calf_raise", 3, 15, side = true),
                p("plank", 3, sec = 40),
            ),
        ),
        DayType.UPPER_A to DayPlan(
            DayType.UPPER_A, "Upper A (push and pull)", 8_000,
            listOf(
                p("elevated_push_up", 3, 10, 15, tempo = 4),
                p("floor_press", 3, 12, 15, kg = 5.0, dbs = 2),
                p("one_arm_row", 3, 12, side = true, kg = 5.0, dbs = 1),
                p("lateral_raise", 3, 15, kg = 3.0, dbs = 2, note = "Shoulder height only"),
                p("biceps_curl", 3, 12, kg = 5.0, dbs = 2),
                p("triceps_extension", 3, 12, kg = 3.0, dbs = 2),
            ),
        ),
        DayType.WALK_CORE to DayPlan(
            DayType.WALK_CORE, "Walk day plus core", 10_000,
            listOf(
                p("dead_bug", 3, 8, side = true),
                p("bird_dog", 3, 8, side = true),
                p("side_plank", 2, sec = 30, side = true),
                p("hollow_hold", 3, sec = 20),
                p("glute_bridge_march", 3, 10, side = true),
            ),
            note = "Walk goal today: 10,000 steps. Split it into 2 or 3 walks, one after your largest meal.",
        ),
        DayType.LOWER_B to DayPlan(
            DayType.LOWER_B, "Lower B (hinge pattern)", 10_000,
            listOf(
                p("romanian_deadlift", 3, 12, kg = 5.0, dbs = 2, tempo = 4),
                p("single_leg_rdl", 3, 10, side = true, kg = 5.0, dbs = 1),
                p("reverse_lunge", 3, 8, side = true, kg = 5.0, dbs = 2),
                p("glute_bridge", 3, 15, kg = 5.0, dbs = 1),
                p("slider_hamstring_curl", 3, 10),
                p("calf_raise", 2, 15, side = true),
            ),
        ),
        DayType.UPPER_B to DayPlan(
            DayType.UPPER_B, "Upper B (pull emphasis)", 8_000,
            listOf(
                p("bent_over_row", 4, 12, kg = 5.0, dbs = 2),
                p("reverse_fly", 3, 15, kg = 3.0, dbs = 2),
                p("close_grip_push_up", 3, 10, 15),
                p("single_arm_floor_press", 3, 10, side = true, kg = 5.0, dbs = 1),
                p("hammer_curl", 3, 12, kg = 5.0, dbs = 2),
                p("plank_shoulder_taps", 3, 20, note = "20 taps in total"),
            ),
        ),
        DayType.MOBILITY to DayPlan(
            DayType.MOBILITY, "Light mobility", 8_000,
            listOf(
                p("torso_rotations", 2, 10),
                p("wall_angel", 2, 10),
                p("glute_bridge", 2, 12),
                p("bird_dog", 2, 6, side = true),
                p("march_in_place", 2, sec = 30),
            ),
            note = "Friday: weigh in fasted, then do the Feelfit scan, before water or food.",
        ),
        DayType.CIRCUIT to DayPlan(
            DayType.CIRCUIT, "Full-body circuit plus long walk", 12_000,
            listOf(
                p("split_squat", 1, sec = 45, rest = 15),
                p("push_up", 1, sec = 45, rest = 15),
                p("one_arm_row", 1, sec = 45, kg = 5.0, dbs = 1, rest = 15, note = "Switch arms halfway"),
                p("glute_bridge", 1, sec = 45, kg = 5.0, dbs = 1, rest = 15),
                p("plank", 1, sec = 45, rest = 120),
            ),
            circuitRounds = 4,
            note = "Long walk today: aim for 12,000 steps.",
        ),
    )

    val defaultWeek: Map<DayOfWeek, DayType> = mapOf(
        DayOfWeek.SUNDAY to DayType.LOWER_A,
        DayOfWeek.MONDAY to DayType.UPPER_A,
        DayOfWeek.TUESDAY to DayType.WALK_CORE,
        DayOfWeek.WEDNESDAY to DayType.LOWER_B,
        DayOfWeek.THURSDAY to DayType.UPPER_B,
        DayOfWeek.FRIDAY to DayType.MOBILITY,
        DayOfWeek.SATURDAY to DayType.CIRCUIT,
    )

    const val POSTURE_NOTE = "Posture work reduces slouching and can make you look 1 to 2 cm taller. It does not add bone length. Claims like +6 cm are exaggerated. At almost 18, growth plates are mostly closed."
}
