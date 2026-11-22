package com.murtdah.fit.domain

enum class Cue { START, SET_DONE, REST_START, TICK, GO, NEXT, HALFWAY, COMPLETE, PB }

sealed interface SessionState {
    data object Intro : SessionState
    data class Countdown(val endsAt: Long, val startIndex: Int = 0) : SessionState
    data class Work(val index: Int, val startedAt: Long, val endsAt: Long?) : SessionState
    data class Log(val index: Int) : SessionState
    data class Rest(val nextIndex: Int, val endsAt: Long, val totalMs: Long) : SessionState
    data class Paused(val inner: SessionState, val remainingMs: Long?) : SessionState
    data class Done(val completed: Boolean) : SessionState
    data class Resuming(val index: Int) : SessionState
}

sealed interface SessionEvent {
    data object Ready : SessionEvent
    data object Tick : SessionEvent
    data object SetDone : SessionEvent
    data class Logged(val reps: Int, val rir: Int, val loadKg: Double?) : SessionEvent
    data object AddRest : SessionEvent
    data object SkipRest : SessionEvent
    data object Pause : SessionEvent
    data object Resume : SessionEvent
    data object Back : SessionEvent
    data object SkipExercise : SessionEvent
    data object Quit : SessionEvent
}

data class LoggedSet(val index: Int, val exerciseId: String, val reps: Int, val rir: Int?, val loadKg: Double?, val seconds: Int)

sealed interface Effect {
    data class Sound(val cue: Cue) : Effect
    data class Record(val set: LoggedSet) : Effect
}

data class Session(
    val steps: List<Step>,
    val state: SessionState = SessionState.Intro,
    val lastNow: Long = 0L,
    val halfwayPlayed: Boolean = false,
    val completedSteps: Set<Int> = emptySet(),
) {
    val currentIndex: Int
        get() = when (val s = state) {
            is SessionState.Work -> s.index
            is SessionState.Log -> s.index
            is SessionState.Rest -> s.nextIndex
            is SessionState.Paused -> Session(steps, s.inner).currentIndex
            is SessionState.Done -> steps.size
            is SessionState.Resuming -> s.index
            is SessionState.Countdown -> s.startIndex
            else -> 0
        }

    val progress: Float get() = if (steps.isEmpty()) 1f else completedSteps.size.toFloat() / steps.size
}

data class Transition(val session: Session, val effects: List<Effect>)

object SessionMachine {
    const val COUNTDOWN_MS = 5_000L
    const val ADD_REST_MS = 15_000L

    fun reduce(session: Session, event: SessionEvent, now: Long): Transition {
        val fx = mutableListOf<Effect>()
        val next = when (val st = session.state) {
            SessionState.Intro -> when (event) {
                SessionEvent.Ready -> session.copy(state = SessionState.Countdown(now + COUNTDOWN_MS)).also { fx += Effect.Sound(Cue.START) }
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Resuming -> when (event) {
                SessionEvent.Ready -> session.copy(state = SessionState.Countdown(now + COUNTDOWN_MS, st.index)).also { fx += Effect.Sound(Cue.START) }
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Countdown -> when (event) {
                SessionEvent.Tick -> {
                    beeps(session.lastNow, now, st.endsAt, fx)
                    if (now >= st.endsAt) startWork(session, st.startIndex, now, fx) else session
                }
                SessionEvent.Pause -> session.copy(state = SessionState.Paused(st, st.endsAt - now))
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Work -> when (event) {
                SessionEvent.Tick -> {
                    if (st.endsAt != null) {
                        beeps(session.lastNow, now, st.endsAt, fx)
                        if (now >= st.endsAt) finishSet(session, st.index, now, fx, timedSeconds = session.steps[st.index].seconds) else session
                    } else session
                }
                SessionEvent.SetDone -> {
                    val step = session.steps[st.index]
                    if (step.timed) {
                        val secs = ((now - st.startedAt) / 1000).toInt()
                        finishSet(session, st.index, now, fx, timedSeconds = secs)
                    } else if (step.strength) {
                        fx += Effect.Sound(Cue.SET_DONE)
                        session.copy(state = SessionState.Log(st.index))
                    } else {
                        fx += Effect.Record(LoggedSet(st.index, step.exerciseId, step.repsMax, null, step.loadKg, 0))
                        finishSet(session, st.index, now, fx, timedSeconds = null, record = false)
                    }
                }
                SessionEvent.Pause -> session.copy(state = SessionState.Paused(st, st.endsAt?.let { it - now }))
                SessionEvent.Back -> startWork(session, (st.index - 1).coerceAtLeast(0), now, fx)
                SessionEvent.SkipExercise -> skip(session, st.index, now, fx)
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Log -> when (event) {
                is SessionEvent.Logged -> {
                    val step = session.steps[st.index]
                    fx += Effect.Record(LoggedSet(st.index, step.exerciseId, event.reps, event.rir.coerceIn(0, 4), event.loadKg, 0))
                    advance(session.copy(completedSteps = session.completedSteps + st.index), st.index, now, fx)
                }
                SessionEvent.Back -> startWork(session, st.index, now, fx)
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Rest -> when (event) {
                SessionEvent.Tick -> {
                    beeps(session.lastNow, now, st.endsAt, fx)
                    if (now >= st.endsAt) startWork(session, st.nextIndex, now, fx) else session
                }
                SessionEvent.AddRest -> session.copy(state = st.copy(endsAt = st.endsAt + ADD_REST_MS, totalMs = st.totalMs + ADD_REST_MS))
                SessionEvent.SkipRest -> startWork(session, st.nextIndex, now, fx)
                SessionEvent.Pause -> session.copy(state = SessionState.Paused(st, st.endsAt - now))
                SessionEvent.Back -> startWork(session, (st.nextIndex - 1).coerceAtLeast(0), now, fx)
                SessionEvent.SkipExercise -> skip(session, st.nextIndex, now, fx)
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Paused -> when (event) {
                SessionEvent.Resume -> session.copy(state = resume(st, now))
                SessionEvent.Quit -> session.copy(state = SessionState.Done(false))
                else -> session
            }

            is SessionState.Done -> session
        }
        return Transition(next.copy(lastNow = now), fx)
    }

    private fun resume(p: SessionState.Paused, now: Long): SessionState {
        val rem = p.remainingMs
        return when (val inner = p.inner) {
            is SessionState.Countdown -> inner.copy(endsAt = now + (rem ?: 0))
            is SessionState.Work -> {
                val elapsed = if (inner.endsAt != null && rem != null) (inner.endsAt - inner.startedAt) - rem else 0
                inner.copy(startedAt = now - elapsed, endsAt = rem?.let { now + it })
            }
            is SessionState.Rest -> inner.copy(endsAt = now + (rem ?: 0))
            else -> inner
        }
    }

    private fun beeps(prev: Long, now: Long, endsAt: Long, fx: MutableList<Effect>) {
        if (prev == 0L) return
        val before = endsAt - prev
        val after = endsAt - now
        for (mark in longArrayOf(3000, 2000, 1000)) {
            if (before > mark && after <= mark && after > 0) fx += Effect.Sound(Cue.TICK)
        }
    }

    private fun startWork(session: Session, index: Int, now: Long, fx: MutableList<Effect>): Session {
        if (index >= session.steps.size) {
            fx += Effect.Sound(Cue.COMPLETE)
            return session.copy(state = SessionState.Done(true))
        }
        val step = session.steps[index]
        fx += Effect.Sound(Cue.GO)
        val dur = if (step.timed) step.seconds * 1000L * (if (step.perSide) 2 else 1) else null
        return session.copy(state = SessionState.Work(index, now, dur?.let { now + it }))
    }

    private fun finishSet(
        session: Session, index: Int, now: Long, fx: MutableList<Effect>, timedSeconds: Int?, record: Boolean = true,
    ): Session {
        val step = session.steps[index]
        if (record && timedSeconds != null) {
            fx += Effect.Record(LoggedSet(index, step.exerciseId, 0, null, step.loadKg, timedSeconds))
        }
        fx += Effect.Sound(Cue.SET_DONE)
        return advance(session.copy(completedSteps = session.completedSteps + index), index, now, fx)
    }

    private fun advance(session: Session, index: Int, now: Long, fx: MutableList<Effect>): Session {
        val nextIndex = index + 1
        if (nextIndex >= session.steps.size) {
            fx += Effect.Sound(Cue.COMPLETE)
            return session.copy(state = SessionState.Done(true))
        }
        var s = session
        if (!s.halfwayPlayed && nextIndex >= s.steps.size / 2) {
            fx += Effect.Sound(Cue.HALFWAY)
            s = s.copy(halfwayPlayed = true)
        }
        val rest = s.steps[index].restAfterSec
        if (rest <= 0) return startWork(s, nextIndex, now, fx)
        fx += Effect.Sound(Cue.REST_START)
        if (s.steps[nextIndex].exerciseId != s.steps[index].exerciseId) fx += Effect.Sound(Cue.NEXT)
        return s.copy(state = SessionState.Rest(nextIndex, now + rest * 1000L, rest * 1000L))
    }

    private fun skip(session: Session, index: Int, now: Long, fx: MutableList<Effect>): Session {
        val id = session.steps.getOrNull(index)?.exerciseId ?: return session
        var target = index
        while (target < session.steps.size && session.steps[target].exerciseId == id) target++
        fx += Effect.Sound(Cue.NEXT)
        val skipped = (index until target).toSet()
        return startWork(session.copy(completedSteps = session.completedSteps + skipped), target, now, fx)
    }
}
