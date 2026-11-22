package com.murtdah.fit.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.murtdah.fit.MainActivity
import com.murtdah.fit.R
import com.murtdah.fit.app
import com.murtdah.fit.data.SetEntry
import com.murtdah.fit.data.WorkoutSession
import com.murtdah.fit.domain.Adjustments
import com.murtdah.fit.domain.Cue
import com.murtdah.fit.domain.DayType
import com.murtdah.fit.domain.Effect
import com.murtdah.fit.domain.Library
import com.murtdah.fit.domain.Phase
import com.murtdah.fit.domain.Prescription
import com.murtdah.fit.domain.Progression
import com.murtdah.fit.domain.Session
import com.murtdah.fit.domain.SessionEvent
import com.murtdah.fit.domain.SessionMachine
import com.murtdah.fit.domain.SessionPlanner
import com.murtdah.fit.domain.SessionState
import com.murtdah.fit.domain.SetResult
import com.murtdah.fit.domain.formatKg
import com.murtdah.fit.media.Haptics
import com.murtdah.fit.media.SoundPlayer
import com.murtdah.fit.media.Voice
import com.murtdah.fit.widget.Widgets
import com.murtdah.fit.work.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkoutService : LifecycleService() {
    private lateinit var sound: SoundPlayer
    private lateinit var haptics: Haptics
    private var voice: Voice? = null
    private var lastNotifText = ""
    private var lastMetronomeSecond = -1L
    private var lastSnapshotAt = 0L
    private val bests = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        sound = SoundPlayer(this)
        haptics = Haptics(this)
        if (app.repo.prefs.voiceOn) voice = Voice(this)
        SessionController.dispatcher = { e -> lifecycleScope.launch { dispatch(e) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch { begin(intent) }
            ACTION_RESUME -> lifecycleScope.launch { resumeSnapshot() }
            ACTION_PAUSE_TOGGLE -> lifecycleScope.launch {
                val st = SessionController.live.value?.session?.state
                dispatch(if (st is SessionState.Paused) SessionEvent.Resume else SessionEvent.Pause)
            }
            ACTION_NEXT -> lifecycleScope.launch {
                when (SessionController.live.value?.session?.state) {
                    is SessionState.Rest -> dispatch(SessionEvent.SkipRest)
                    is SessionState.Work -> dispatch(SessionEvent.SetDone)
                    else -> Unit
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val n = buildNotification("Workout", "Getting ready")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private suspend fun begin(intent: Intent) {
        if (SessionController.live.value?.let { it.session.state !is SessionState.Done } == true) return
        val repo = app.repo
        val type = DayType.valueOf(intent.getStringExtra(EXTRA_DAY)!!)
        val adj = decodeAdjustments(intent.getStringExtra(EXTRA_ADJ).orEmpty())
        val light = intent.getStringExtra(EXTRA_LIGHT) ?: "GREEN"
        val reasons = intent.getStringExtra(EXTRA_REASONS).orEmpty()
        val steps = withContext(Dispatchers.IO) { SessionPlanner.build(type, adj, repo.progressMap(), repo.prefs.restDefaults) }
        val now = System.currentTimeMillis()
        val id = withContext(Dispatchers.IO) {
            repo.dao.insertSession(
                WorkoutSession(date = repo.today().toString(), dayType = type.name, startedAt = now, recovery = adj.recoveryOnly, light = light, readinessReasons = reasons),
            )
        }
        bests.clear()
        val previous = withContext(Dispatchers.IO) { previousNumbers(steps.map { it.exerciseId }.distinct(), id) }
        SessionController.set(LiveSession(id, type, adj, Session(steps), now, previous = previous))
        runLoop()
    }

    private suspend fun resumeSnapshot() {
        val repo = app.repo
        val snap = SessionController.decode(repo.prefs.snapshot) ?: return stopSelf()
        var steps = withContext(Dispatchers.IO) { SessionPlanner.build(snap.dayType, snap.adjustments, repo.progressMap(), repo.prefs.restDefaults) }
        snap.swaps.forEach { (i, ex) -> if (i in steps.indices) steps = SessionPlanner.replaceExercise(steps, i, ex) }
        val previous = withContext(Dispatchers.IO) { previousNumbers(steps.map { it.exerciseId }.distinct(), snap.sessionId) }
        val index = snap.index.coerceIn(0, steps.lastIndex)
        SessionController.set(
            LiveSession(
                snap.sessionId, snap.dayType, snap.adjustments,
                Session(steps, SessionState.Resuming(index), completedSteps = snap.completed),
                snap.startedWall, snap.swaps, previous,
            ),
        )
        runLoop()
    }

    private suspend fun previousNumbers(ids: List<String>, exclude: Long): Map<String, String> =
        ids.associateWith { id ->
            val sets = app.repo.dao.recentSets(id, exclude)
            if (sets.isEmpty()) "" else {
                val last = sets.first()
                val load = last.loadKg?.let { " x ${formatKg(it)} kg" } ?: ""
                "Last time: ${last.reps} reps$load, ${last.rir ?: "-"} in reserve"
            }
        }.filterValues { it.isNotEmpty() }

    private fun runLoop() {
        lifecycleScope.launch {
            while (isActive) {
                val live = SessionController.live.value ?: break
                val st = live.session.state
                if (st is SessionState.Done) break
                checkCalls(live)
                metronome(live)
                dispatch(SessionEvent.Tick)
                delay(200)
            }
        }
    }

    private suspend fun checkCalls(live: LiveSession) {
        val inCall = sound.inCall()
        val st = live.session.state
        if (inCall && st !is SessionState.Paused && st !is SessionState.Intro && st !is SessionState.Resuming) {
            dispatch(SessionEvent.Pause)
            SessionController.update { it.copy(autoPaused = true) }
        } else if (!inCall && live.autoPaused && st is SessionState.Paused) {
            SessionController.update { it.copy(autoPaused = false) }
            dispatch(SessionEvent.Resume)
        }
    }

    private fun metronome(live: LiveSession) {
        val st = live.session.state as? SessionState.Work ?: return
        val step = live.steps.getOrNull(st.index) ?: return
        if (!app.repo.prefs.metronomeOn || step.tempoDownSec == 0 || step.timed) return
        val sec = (SystemClock.elapsedRealtime() - st.startedAt) / 1000
        if (sec != lastMetronomeSecond) {
            lastMetronomeSecond = sec
            if (app.repo.prefs.soundOn) sound.softTick()
        }
    }

    private suspend fun dispatch(event: SessionEvent) {
        val live = SessionController.live.value ?: return
        val now = SystemClock.elapsedRealtime()
        val t = SessionMachine.reduce(live.session, event, now)
        val stateChanged = t.session.state::class != live.session.state::class ||
            (t.session.state as? SessionState.Work)?.index != (live.session.state as? SessionState.Work)?.index
        SessionController.update { it.copy(session = t.session) }
        for (e in t.effects) {
            when (e) {
                is Effect.Sound -> cue(e.cue)
                is Effect.Record -> record(e.set)
            }
        }
        if (stateChanged) {
            if (app.repo.prefs.vibrationOn) haptics.tap(t.session.state is SessionState.Done)
            speak(t.session)
        }
        val wall = System.currentTimeMillis()
        if (stateChanged || wall - lastSnapshotAt > 5_000) {
            lastSnapshotAt = wall
            SessionController.live.value?.let { app.repo.prefs.snapshot = SessionController.encode(it) }
        }
        updateNotification()
        val done = t.session.state as? SessionState.Done
        if (done != null && live.session.state !is SessionState.Done) finish(done.completed)
    }

    private fun cue(c: Cue) {
        if (app.repo.prefs.soundOn) sound.play(c)
    }

    private fun speak(s: Session) {
        val v = voice ?: return
        val st = s.state
        if (st is SessionState.Work) {
            val step = s.steps[st.index]
            v.say("${step.name}. Set ${step.setNumber} of ${step.totalSets}. ${step.targetText()}")
        } else if (st is SessionState.Done && st.completed) {
            v.say("Session complete. Good work.")
        }
    }

    private suspend fun record(set: com.murtdah.fit.domain.LoggedSet) {
        val live = SessionController.live.value ?: return
        val step = live.steps.getOrNull(set.index) ?: return
        val repo = app.repo
        withContext(Dispatchers.IO) {
            if (step.strength && set.rir != null) {
                val score = set.reps * (set.loadKg ?: 1.0)
                val best = repo.dao.bestScore(set.exerciseId, live.sessionId)
                if (best != null && score > best) {
                    bests += step.name
                    withContext(Dispatchers.Main) {
                        cue(Cue.PB)
                        SessionController.update { it.copy(lastBest = step.name) }
                    }
                }
            }
            repo.dao.insertSet(
                SetEntry(
                    sessionId = live.sessionId, date = repo.today().toString(), exerciseId = set.exerciseId, stepIndex = set.index,
                    reps = set.reps, rir = if (step.strength) set.rir else null, loadKg = set.loadKg, seconds = set.seconds,
                    time = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun finish(completed: Boolean) {
        val live = SessionController.live.value ?: return
        val repo = app.repo
        val sets = withContext(Dispatchers.IO) { repo.dao.setsFor(live.sessionId) }
        val strengthSets = sets.filter { it.rir != null }
        val volume = strengthSets.sumOf { it.reps * (it.loadKg ?: 0.0) }
        withContext(Dispatchers.IO) {
            repo.dao.finishSession(live.sessionId, System.currentTimeMillis(), completed, volume, bests.size)
            val postureIdx = live.steps.indices.filter { live.steps[it].phase == Phase.POSTURE }
            if (postureIdx.isNotEmpty() && live.session.completedSteps.containsAll(postureIdx)) {
                repo.updateDay { it.copy(postureDone = true, recoveryDay = it.recoveryDay || live.adjustments.recoveryOnly) }
            }
            if (completed) {
                val progress = repo.progressMap()
                strengthSets.groupBy { it.exerciseId }.forEach { (exId, list) ->
                    val step = live.steps.firstOrNull { it.exerciseId == exId && it.strength } ?: return@forEach
                    val ex = Library[exId]
                    val p = Prescription(exId, step.totalSets, step.repsMin, step.repsMax, step.seconds, step.perSide, step.loadKg)
                    val state = progress[exId] ?: com.murtdah.fit.domain.ProgressState(exId, loadKg = step.loadKg)
                    val out = Progression.apply(ex, p, state, list.map { SetResult(it.reps, it.rir ?: 2, it.loadKg) })
                    repo.saveProgress(out.next, live.sessionId)
                }
                repo.prefs.snapshot = null
            }
        }
        val seconds = ((System.currentTimeMillis() - live.startedWall) / 1000).toInt()
        SessionController.update { it.copy(summary = Summary(seconds, volume, bests.toList(), completed)) }
        Widgets.refresh(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val live = SessionController.live.value ?: return
        val st = live.session.state
        val now = SystemClock.elapsedRealtime()
        fun secs(ms: Long) = "${(ms / 1000).coerceAtLeast(0)} s"
        val (title, text) = when (st) {
            is SessionState.Work -> {
                val s = live.steps[st.index]
                s.name to ("Set ${s.setNumber} of ${s.totalSets}  " + (st.endsAt?.let { secs(it - now) } ?: s.targetText()))
            }
            is SessionState.Rest -> "Rest ${secs(st.endsAt - now)}" to "Next: ${live.steps[st.nextIndex].name}"
            is SessionState.Log -> live.steps[st.index].name to "Log reps in reserve"
            is SessionState.Paused -> "Paused" to (if (live.autoPaused) "Paused for a call" else "Tap to return")
            is SessionState.Countdown -> "Starting" to secs(st.endsAt - now)
            is SessionState.Done -> "Session saved" to ""
            else -> "Workout" to "Ready when you are"
        }
        val key = title + text
        if (key == lastNotifText) return
        lastNotifText = key
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
        ) {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "workout")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = SessionController.live.value?.session?.state is SessionState.Paused
        return NotificationCompat.Builder(this, Notifications.CH_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", self(ACTION_PAUSE_TOGGLE, 1))
            .addAction(0, "Next", self(ACTION_NEXT, 2))
            .build()
    }

    private fun self(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, WorkoutService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        SessionController.dispatcher = null
        sound.release()
        voice?.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_START = "start"
        const val ACTION_RESUME = "resume"
        const val ACTION_PAUSE_TOGGLE = "pause"
        const val ACTION_NEXT = "next"
        const val ACTION_STOP = "stop"
        const val EXTRA_DAY = "day"
        const val EXTRA_ADJ = "adj"
        const val EXTRA_LIGHT = "light"
        const val EXTRA_REASONS = "reasons"

        fun encodeAdjustments(a: Adjustments) = listOf(a.dropOneSet, a.painAreas.joinToString(",") { it.name }, a.upperBodyOnly, a.recoveryOnly, a.skipHops).joinToString("|")

        fun decodeAdjustments(s: String): Adjustments = runCatching {
            val p = s.split("|")
            Adjustments(
                p[0].toBoolean(),
                p[1].split(",").filter { it.isNotBlank() }.map { com.murtdah.fit.domain.BodyArea.valueOf(it) }.toSet(),
                p[2].toBoolean(), p[3].toBoolean(), p[4].toBoolean(),
            )
        }.getOrDefault(Adjustments())

        fun start(context: Context, type: DayType, adj: Adjustments, light: String, reasons: String) {
            SessionController.set(null)
            val i = Intent(context, WorkoutService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_DAY, type.name).putExtra(EXTRA_ADJ, encodeAdjustments(adj))
                .putExtra(EXTRA_LIGHT, light).putExtra(EXTRA_REASONS, reasons)
            ContextCompat.startForegroundService(context, i)
        }

        fun resume(context: Context) {
            SessionController.set(null)
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).setAction(ACTION_RESUME))
        }
    }
}
