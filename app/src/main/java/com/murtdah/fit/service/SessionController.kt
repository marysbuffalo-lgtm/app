package com.murtdah.fit.service

import com.murtdah.fit.domain.Adjustments
import com.murtdah.fit.domain.BodyArea
import com.murtdah.fit.domain.DayType
import com.murtdah.fit.domain.Session
import com.murtdah.fit.domain.SessionEvent
import com.murtdah.fit.domain.SessionPlanner
import com.murtdah.fit.domain.SessionState
import com.murtdah.fit.domain.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Summary(
    val totalSeconds: Int,
    val volumeKg: Double,
    val personalBests: List<String>,
    val completed: Boolean,
)

data class LiveSession(
    val sessionId: Long,
    val dayType: DayType,
    val adjustments: Adjustments,
    val session: Session,
    val startedWall: Long,
    val swaps: List<Pair<Int, String>> = emptyList(),
    val previous: Map<String, String> = emptyMap(),
    val summary: Summary? = null,
    val autoPaused: Boolean = false,
    val lastBest: String? = null,
) {
    val steps: List<Step> get() = session.steps
}

object SessionController {
    private val _live = MutableStateFlow<LiveSession?>(null)
    val live: StateFlow<LiveSession?> = _live

    @Volatile var dispatcher: ((SessionEvent) -> Unit)? = null

    fun set(value: LiveSession?) {
        _live.value = value
    }

    fun send(event: SessionEvent) {
        dispatcher?.invoke(event)
    }

    fun update(block: (LiveSession) -> LiveSession) {
        _live.value = _live.value?.let(block)
    }

    fun swap(newExerciseId: String) = update { l ->
        val idx = l.session.currentIndex.coerceAtMost(l.steps.lastIndex)
        val steps = SessionPlanner.replaceExercise(l.steps, idx, newExerciseId)
        l.copy(session = l.session.copy(steps = steps), swaps = l.swaps + (idx to newExerciseId))
    }

    fun flagPain(area: BodyArea) = update { l ->
        val idx = l.session.currentIndex.coerceAtMost(l.steps.lastIndex)
        val before = l.steps
        val steps = SessionPlanner.applyPain(before, idx, area)
        val newSwaps = steps.indices.filter { steps[it].exerciseId != before[it].exerciseId }
            .map { it to steps[it].exerciseId }
        l.copy(session = l.session.copy(steps = steps), swaps = l.swaps + newSwaps, adjustments = l.adjustments.copy(painAreas = l.adjustments.painAreas + area))
    }

    fun encode(l: LiveSession): String {
        val s = l.session.state
        val inner = if (s is SessionState.Paused) s.inner else s
        val index = when (inner) {
            is SessionState.Work -> inner.index
            is SessionState.Log -> inner.index
            is SessionState.Rest -> inner.nextIndex
            else -> 0
        }
        val a = l.adjustments
        return listOf(
            l.sessionId, l.dayType.name, a.dropOneSet, a.painAreas.joinToString(",") { it.name }, a.upperBodyOnly, a.recoveryOnly, a.skipHops,
            index, l.startedWall, System.currentTimeMillis(), l.swaps.joinToString(",") { "${it.first}:${it.second}" },
            l.session.completedSteps.joinToString(","),
        ).joinToString("|")
    }

    data class Snapshot(
        val sessionId: Long, val dayType: DayType, val adjustments: Adjustments, val index: Int,
        val startedWall: Long, val savedAt: Long, val swaps: List<Pair<Int, String>>, val completed: Set<Int>,
    )

    fun decode(text: String?): Snapshot? = runCatching {
        val p = text!!.split("|")
        Snapshot(
            sessionId = p[0].toLong(),
            dayType = DayType.valueOf(p[1]),
            adjustments = Adjustments(
                dropOneSet = p[2].toBoolean(),
                painAreas = p[3].split(",").filter { it.isNotBlank() }.map { BodyArea.valueOf(it) }.toSet(),
                upperBodyOnly = p[4].toBoolean(),
                recoveryOnly = p[5].toBoolean(),
                skipHops = p[6].toBoolean(),
            ),
            index = p[7].toInt(),
            startedWall = p[8].toLong(),
            savedAt = p[9].toLong(),
            swaps = p[10].split(",").filter { it.contains(':') }.map { it.substringBefore(':').toInt() to it.substringAfter(':') },
            completed = p.getOrNull(11)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty(),
        )
    }.getOrNull()

    const val RESUME_WINDOW_MS = 3 * 60 * 60 * 1000L
}
