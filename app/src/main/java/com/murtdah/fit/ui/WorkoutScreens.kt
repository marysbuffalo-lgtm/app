package com.murtdah.fit.ui

import android.app.Activity
import android.os.BatteryManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.murtdah.fit.app
import com.murtdah.fit.domain.Adjustments
import com.murtdah.fit.domain.BodyArea
import com.murtdah.fit.domain.DayType
import com.murtdah.fit.domain.Feeling
import com.murtdah.fit.domain.Fix
import com.murtdah.fit.domain.Library
import com.murtdah.fit.domain.Light
import com.murtdah.fit.domain.MuscleGroup
import com.murtdah.fit.domain.ProgramSeed
import com.murtdah.fit.domain.ReadinessInput
import com.murtdah.fit.domain.ReadinessResult
import com.murtdah.fit.domain.ReadinessRules
import com.murtdah.fit.domain.SessionEvent
import com.murtdah.fit.domain.SessionPlanner
import com.murtdah.fit.domain.SessionState
import com.murtdah.fit.domain.SleepBand
import com.murtdah.fit.domain.Step
import com.murtdah.fit.domain.formatKg
import com.murtdah.fit.service.LiveSession
import com.murtdah.fit.service.SessionController
import com.murtdah.fit.service.WorkoutService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val SETUP_ITEMS = listOf(
    "space" to "Space cleared, about 2 m by 2 m, non-slip floor",
    "gear" to "Dumbbells (2 x 3 kg, 2 x 5 kg), slider, towel, wall space, water bottle",
    "shoes" to "Shoes or grippy socks, no loose rugs",
    "bathroom" to "Bathroom done",
)

private data class LoggedState(
    val minutesSinceMeal: Int?,
    val mealKcal: Int?,
    val water2h: Int,
    val lastSoreGroup: MuscleGroup?,
    val minutesToClose: Int,
    val steps: Int,
    val stepsGoal: Int,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadinessScreen(onStarted: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repo
    val scope = rememberCoroutineScope()
    val type = repo.prefs.dayTypeFor(repo.today().dayOfWeek)
    var refresh by remember { mutableIntStateOf(0) }
    val logged by produceState<LoggedState?>(null, refresh) {
        val last = repo.dao.lastMeal()
        val now = System.currentTimeMillis()
        val day = repo.day()
        val lastSession = repo.dao.allSessions().lastOrNull { it.completed && !it.recovery }
        val lastGroup = lastSession?.let { if (runCatching { DayType.valueOf(it.dayType) }.getOrNull()?.lowerBody == true) MuscleGroup.LOWER else MuscleGroup.UPPER }
        value = LoggedState(
            minutesSinceMeal = last?.let { ((now - it.time) / 60_000).toInt() },
            mealKcal = last?.kcal,
            water2h = repo.dao.drinkMlSince(now - 2 * 3_600_000L),
            lastSoreGroup = lastGroup,
            minutesToClose = com.murtdah.fit.domain.EatingWindow.minutesUntilClose(LocalTime.now(), repo.prefs.reminders.eatingClose),
            steps = day.steps,
            stepsGoal = ProgramSeed.plans.getValue(type).stepsGoal,
        )
    }

    var sleep by remember { mutableStateOf<SleepBand?>(null) }
    var pain by remember { mutableStateOf(setOf<BodyArea>()) }
    var soreness by remember { mutableIntStateOf(2) }
    var feeling by remember { mutableStateOf<Feeling?>(null) }
    var redFlag by remember { mutableStateOf<Boolean?>(null) }
    var applied by remember { mutableStateOf(setOf<String>()) }
    var setup by remember { mutableStateOf(repo.prefs.setupChecklist) }
    var askReason by remember { mutableStateOf(false) }

    val battery = remember {
        val bm = context.getSystemService(BatteryManager::class.java)
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }
    val media = remember { context.app.media.check() }

    val l = logged
    val result: ReadinessResult? = if (l != null && sleep != null && feeling != null && redFlag != null) {
        val soreSame = l.lastSoreGroup != null && ((type.lowerBody && l.lastSoreGroup == MuscleGroup.LOWER) || (!type.lowerBody && l.lastSoreGroup == MuscleGroup.UPPER))
        ReadinessRules.evaluate(ReadinessInput(l.minutesSinceMeal, l.mealKcal, l.water2h, sleep!!, pain, soreness, soreSame, feeling!!, redFlag!!, type))
    } else null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ready to train?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(ProgramSeed.plans.getValue(type).title, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (l != null) SectionCard("From your log") {
            Text(l.minutesSinceMeal?.let { "Last meal: $it min ago (${l.mealKcal} kcal)" } ?: "No meal logged yet")
            Text("Water in the last 2 hours: ${l.water2h} ml. Sip about 150 ml between exercises.")
            Text(if (l.minutesToClose > 0) "Eating window closes in ${hm(l.minutesToClose * 60)}. Leave room for whey or yogurt after." else "Eating window is closed.")
            Text("Steps so far: ${l.steps}, ${(l.stepsGoal - l.steps).coerceAtLeast(0)} to go.")
        }

        SectionCard("Sleep last night") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SleepBand.entries.forEach { s -> FilterChip(selected = sleep == s, onClick = { sleep = s }, label = { Text(s.label) }) }
            }
        }
        SectionCard("Pain or niggles") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pain.isEmpty(), onClick = { pain = emptySet() }, label = { Text("None") })
                BodyArea.entries.forEach { a ->
                    FilterChip(selected = a in pain, onClick = { pain = if (a in pain) pain - a else pain + a }, label = { Text(a.label) })
                }
            }
        }
        SectionCard("Soreness from last session: $soreness / 10") {
            Slider(value = soreness.toFloat(), onValueChange = { soreness = it.toInt() }, valueRange = 0f..10f, steps = 9)
        }
        SectionCard("Feeling") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Feeling.entries.forEach { f -> FilterChip(selected = feeling == f, onClick = { feeling = f }, label = { Text(f.label) }) }
            }
        }
        SectionCard("Breathing and heart check") {
            Text("Any chest pain, unusual shortness of breath, dizziness or palpitations right now?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = redFlag == false, onClick = { redFlag = false }, label = { Text("No") })
                FilterChip(selected = redFlag == true, onClick = { redFlag = true }, label = { Text("Yes") })
            }
        }

        SectionCard("Setup") {
            SETUP_ITEMS.forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = key in setup, onCheckedChange = {
                        setup = if (it) setup + key else setup - key
                        repo.prefs.setupChecklist = setup
                    })
                    Text(label)
                }
            }
            Text("Battery $battery%", color = if (battery > 20) Good else Bad)
            Text("Media check: ${media.found.size} GIFs found, ${media.missing.size} use a placeholder", style = MaterialTheme.typography.bodySmall)
            Text("Caffeine is optional: 100 to 150 mg of black coffee or tea at most, none after 14:00.", style = MaterialTheme.typography.bodySmall)
        }

        if (result != null) {
            val color = when (result.light) { Light.GREEN -> Good; Light.AMBER -> Warn; Light.RED -> Bad }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(color))
                    Text(result.light.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, color = color)
                }
                result.findings.forEach { f ->
                    Text(f.message, color = if (f.light == Light.RED) Bad else MaterialTheme.colorScheme.onSurface)
                    val fix = f.fix
                    if (fix != null && result.light == Light.AMBER) {
                        val key = fix.toString()
                        val label = when (fix) {
                            is Fix.Delay -> "Delay until ${LocalTime.now().plusMinutes(fix.minutes.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))}"
                            Fix.DrinkWater -> "+500 ml now"
                            Fix.DropOneSet -> "Drop one set per exercise"
                            is Fix.SwapFor -> "Swap affected exercises"
                            Fix.UpperBodyOnly -> "Switch to upper body"
                        }
                        if (key in applied) {
                            Text("Applied: $label", color = Good, style = MaterialTheme.typography.bodySmall)
                        } else {
                            SmallButton(label) {
                                when (fix) {
                                    is Fix.Delay -> {
                                        com.murtdah.fit.work.Scheduler.scheduleRaw(context, "WORKOUT_NOW", 9003, System.currentTimeMillis() + fix.minutes * 60_000L)
                                        onCancel()
                                    }
                                    Fix.DrinkWater -> scope.launch { repo.addDrink(500); refresh++ }
                                    else -> Unit
                                }
                                applied = applied + key
                            }
                        }
                    }
                }
            }

            val adj = Adjustments(
                dropOneSet = result.fixes.any { it is Fix.DropOneSet && it.toString() in applied },
                painAreas = pain,
                upperBodyOnly = result.fixes.any { it is Fix.UpperBodyOnly && it.toString() in applied },
            )
            val reasons = result.findings.joinToString("; ") { it.message }
            if (result.light == Light.RED) {
                if (redFlag == true) Text(ReadinessRules.RED_FLAG_MESSAGE, color = Bad, fontWeight = FontWeight.Bold)
                Text("Main session is off today. Posture block, breathing and a 15 minute walk instead. It counts as a recovery day.")
                BigButton("Start recovery session", color = Warn) { askReason = true }
            } else {
                val label = if (result.light == Light.AMBER) "Continue on amber" else "Start"
                BigButton(label) {
                    WorkoutService.start(context, type, adj, result.light.name, reasons)
                    onStarted()
                }
            }
        } else {
            Text("Answer the questions above to see your traffic light.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SmallButton("Back", onClick = onCancel)
    }

    if (askReason) {
        AlertDialog(
            onDismissRequest = { askReason = false },
            title = { Text("Reason for a recovery day") },
            text = {
                Column {
                    listOf("Unwell", "Short sleep", "Chest or breathing symptoms", "Pain", "Other").forEach { r ->
                        TextButton(onClick = {
                            askReason = false
                            scope.launch { repo.updateDay { it.copy(recoveryDay = true, skipReason = r) } }
                            WorkoutService.start(context, type, Adjustments(recoveryOnly = true, painAreas = pain), "RED", r)
                            onStarted()
                        }) { Text(r) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askReason = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ExerciseMedia(exerciseId: String, cue: String, modifier: Modifier = Modifier) {
    val media = LocalContext.current.app.media
    val uri = remember(exerciseId) { media.assetUri(exerciseId) }
    Box(
        modifier.fillMaxWidth().heightIn(min = 180.dp, max = 260.dp).clip(RoundedCornerShape(16.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = Library.all[exerciseId]?.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(240.dp))
            if (media.usesAlternate(exerciseId)) {
                Text(
                    "Similar movement shown. Follow the written cue.",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null, modifier = Modifier.size(48.dp))
                Text(cue, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun LiveWorkoutScreen(onExit: () -> Unit) {
    val live by SessionController.live.collectAsState()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }
    val l = live
    if (l == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Loading session")
            SmallButton("Back", onClick = onExit)
        }
        return
    }
    LiveWorkout(l, now, onEvent = { SessionController.send(it) }, onExit = onExit)
}

@Composable
fun LiveWorkout(l: LiveSession, now: Long, onEvent: (SessionEvent) -> Unit, onExit: () -> Unit) {
    val state = l.session.state
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val idx = l.session.currentIndex.coerceIn(0, (l.steps.size - 1).coerceAtLeast(0))
        val step = l.steps.getOrNull(idx)
        if (state !is SessionState.Done) {
            LinearProgressIndicator(progress = { l.session.progress }, modifier = Modifier.fillMaxWidth().height(8.dp).testTag("sessionProgress"))
            if (step != null) {
                val phaseSteps = l.steps.indices.filter { l.steps[it].phase == step.phase }
                val phaseDone = phaseSteps.count { it in l.session.completedSteps }
                Text("${step.phase.label}  $phaseDone / ${phaseSteps.size}", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { phaseDone / phaseSteps.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Warn)
            }
        }

        when (state) {
            SessionState.Intro, is SessionState.Resuming -> Intro(l, state is SessionState.Resuming, onEvent, onExit)
            is SessionState.Countdown -> {
                Text("Starting in", style = MaterialTheme.typography.titleLarge)
                Text("${((state.endsAt - now) / 1000 + 1).coerceAtLeast(1)}", fontSize = 96.sp, fontWeight = FontWeight.Bold)
                step?.let { Text("First: ${it.name}") }
            }
            is SessionState.Work -> WorkView(l, l.steps[state.index], state, now, onEvent)
            is SessionState.Log -> LogView(l.steps[state.index], onEvent)
            is SessionState.Rest -> RestView(l, state, now, onEvent)
            is SessionState.Paused -> {
                Text(if (l.autoPaused) "Paused for a call" else "Paused", style = MaterialTheme.typography.headlineMedium)
                step?.let { Text("Current: ${it.name}") }
                BigButton("Resume") { onEvent(SessionEvent.Resume) }
            }
            is SessionState.Done -> SummaryView(l, onExit)
        }

        if (state is SessionState.Work || state is SessionState.Rest || state is SessionState.Log) {
            Controls(l, onEvent, onExit)
        }
        l.lastBest?.let { if (state !is SessionState.Done) Text("Personal best: $it", color = Good) }
    }
}

@Composable
private fun Intro(l: LiveSession, resuming: Boolean, onEvent: (SessionEvent) -> Unit, onExit: () -> Unit) {
    val plan = ProgramSeed.plans.getValue(l.dayType)
    Text(if (l.adjustments.recoveryOnly) "Recovery session" else plan.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("About ${hm(SessionPlanner.estimateSeconds(l.steps))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionCard("Coming up") {
        l.steps.groupBy { it.phase }.forEach { (phase, list) ->
            Text(phase.label, fontWeight = FontWeight.SemiBold)
            list.distinctBy { it.exerciseId to it.name }.forEach { s ->
                Text("  ${s.name}: ${s.totalSets} x ${s.targetText()}${if (s.loadKg != null) ", ${s.loadText}" else ""}${if (s.optional) " (optional)" else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (l.adjustments.recoveryOnly) Text("Finish with a 15 minute easy walk.")
    }
    BigButton(if (resuming) "Resume" else "Ready") { onEvent(SessionEvent.Ready) }
    SmallButton("Cancel") {
        onEvent(SessionEvent.Quit)
        onExit()
    }
}

@Composable
private fun WorkView(l: LiveSession, step: Step, state: SessionState.Work, now: Long, onEvent: (SessionEvent) -> Unit) {
    val context = LocalContext.current
    Text(step.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        if (step.round > 0) "Round ${step.setNumber} of ${step.totalSets}" else "Set ${step.setNumber} of ${step.totalSets}${if (step.warmup && step.phase == com.murtdah.fit.domain.Phase.MAIN) " (warm-up, not counted)" else ""}",
        style = MaterialTheme.typography.titleMedium,
    )
    if (step.exerciseId == "diaphragmatic_breathing") {
        BreathingCircle()
    } else {
        ExerciseMedia(step.exerciseId, step.formCue)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(step.targetText(), style = MaterialTheme.typography.titleLarge, color = Good)
        Text(step.loadText, style = MaterialTheme.typography.titleLarge)
    }
    if (step.tempoDownSec > 0) Text("Tempo: ${step.tempoDownSec} s down", color = Warn)
    Text(step.formCue)
    step.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    l.previous[step.exerciseId]?.let { if (step.strength) Text(it, style = MaterialTheme.typography.bodySmall) }
    if (step.exerciseId == "low_impact_hops" || step.exerciseId == "half_squats") {
        SmallButton("Knees hurt: swap to marching") { SessionController.swap("march_in_place") }
    }
    if (step.tempoDownSec > 0) {
        var metro by remember { mutableStateOf(context.app.repo.prefs.metronomeOn) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = metro, onCheckedChange = { metro = it; context.app.repo.prefs.metronomeOn = it })
            Text("  Tempo metronome")
        }
    }
    if (state.endsAt != null) {
        val total = (state.endsAt - state.startedAt).coerceAtLeast(1)
        val left = (state.endsAt - now).coerceAtLeast(0)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Ring(progress = left / total.toFloat(), size = 160.dp, stroke = 14.dp) {
                Text("${(left + 999) / 1000}", fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (step.perSide) Text("Switch sides at halfway", Modifier.fillMaxWidth())
        SmallButton("Done early", Modifier.fillMaxWidth()) { onEvent(SessionEvent.SetDone) }
    } else {
        BigButton("Set done", Modifier.testTag("setDone")) { onEvent(SessionEvent.SetDone) }
    }
}

@Composable
fun BreathingCircle() {
    val t = rememberInfiniteTransition(label = "breath")
    val scale by t.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 10_000
                0.4f at 0
                1f at 4_000
                0.4f at 10_000
            },
            RepeatMode.Restart,
        ),
        label = "scale",
    )
    val color = Good
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            drawCircle(color.copy(alpha = 0.25f), radius = size.minDimension / 2 * scale)
            drawCircle(color, radius = size.minDimension / 2 * scale, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
        }
        Text(if (scale > 0.95f) "Out" else "Breathe", style = MaterialTheme.typography.titleMedium)
    }
    Text("4 s in through the nose, belly expands. 6 s out through the mouth.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogView(step: Step, onEvent: (SessionEvent) -> Unit) {
    var reps by remember(step) { mutableIntStateOf(step.repsMax) }
    var load by remember(step) { mutableStateOf(step.loadKg) }
    Text(step.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("How many more reps could you have done?", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..4).forEach { rir ->
            BigButton("$rir", Modifier.size(width = 64.dp, height = 64.dp).testTag("rir$rir")) { onEvent(SessionEvent.Logged(reps, rir, load)) }
        }
    }
    Text("0 means you could not do another rep. Aim for 1 to 2. Never train to failure.", style = MaterialTheme.typography.bodySmall)
    SectionCard("Adjust what you did (optional)") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("-") { reps = (reps - 1).coerceAtLeast(0) }
            Text("$reps reps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("repsValue"))
            SmallButton("+") { reps++ }
        }
        if (load != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3.0, 5.0).forEach { kg -> FilterChip(selected = load == kg, onClick = { load = kg }, label = { Text("${formatKg(kg)} kg") }) }
            }
        }
    }
}

@Composable
private fun RestView(l: LiveSession, state: SessionState.Rest, now: Long, onEvent: (SessionEvent) -> Unit) {
    val next = l.steps[state.nextIndex]
    val left = (state.endsAt - now).coerceAtLeast(0)
    Text("Rest", style = MaterialTheme.typography.headlineMedium)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Ring(progress = left / state.totalMs.toFloat(), size = 180.dp, stroke = 14.dp, color = MaterialTheme.colorScheme.secondary) {
            Text("${(left + 999) / 1000}", fontSize = 56.sp, fontWeight = FontWeight.Bold)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SmallButton("+15 s", Modifier.weight(1f)) { onEvent(SessionEvent.AddRest) }
        SmallButton("Skip rest", Modifier.weight(1f)) { onEvent(SessionEvent.SkipRest) }
    }
    Text("Next: ${next.name}", style = MaterialTheme.typography.titleMedium)
    Text("Set ${next.setNumber} of ${next.totalSets}, ${next.targetText()}${if (next.loadKg != null) ", ${next.loadText}" else ""}")
    ExerciseMedia(next.exerciseId, next.formCue, Modifier.heightIn(max = 160.dp))
    Text("Sip about 150 ml of water.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(l: LiveSession, onEvent: (SessionEvent) -> Unit, onExit: () -> Unit) {
    var swapOpen by remember { mutableStateOf(false) }
    var quitOpen by remember { mutableStateOf(false) }
    val step = l.steps.getOrNull(l.session.currentIndex)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Pause") { onEvent(SessionEvent.Pause) }
        SmallButton("Back") { onEvent(SessionEvent.Back) }
        SmallButton("Skip exercise") { onEvent(SessionEvent.SkipExercise) }
        SmallButton("Swap") { swapOpen = true }
        SmallButton("Knees hurt") { SessionController.flagPain(BodyArea.KNEES) }
        SmallButton("Shoulder hurts") { SessionController.flagPain(BodyArea.SHOULDERS) }
        SmallButton("Quit") { quitOpen = true }
    }
    if (swapOpen && step != null) {
        val ex = Library[step.exerciseId]
        val options = (ex.alternatives + ex.swaps.values).distinct().filter { it != ex.id }
        AlertDialog(
            onDismissRequest = { swapOpen = false },
            title = { Text("Swap ${ex.name}") },
            text = {
                Column {
                    if (options.isEmpty()) Text("No safe alternative is defined for this one. Skip it instead if needed.")
                    options.forEach { id ->
                        TextButton(onClick = { SessionController.swap(id); swapOpen = false }) { Text(Library[id].name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { swapOpen = false }) { Text("Close") } },
        )
    }
    if (quitOpen) {
        AlertDialog(
            onDismissRequest = { quitOpen = false },
            title = { Text("Stop here?") },
            text = { Text("Your sets so far are saved. You can resume within 3 hours from the Today screen.") },
            confirmButton = { TextButton(onClick = { quitOpen = false; onEvent(SessionEvent.Quit) }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { quitOpen = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun SummaryView(l: LiveSession, onExit: () -> Unit) {
    val context = LocalContext.current
    val repo = context.app.repo
    val s = l.summary
    val totals by produceState<com.murtdah.fit.data.DayTotals?>(null) { value = repo.totalsNow() }
    val day by produceState<com.murtdah.fit.data.DayLog?>(null) { value = repo.day() }
    Text(if (s?.completed == true) "Session complete" else "Session stopped", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (s == null) {
        Text("Saving...")
    } else {
        SectionCard {
            Text("Time: ${hm(s.totalSeconds)}")
            Text("Volume: ${formatKg(Math.round(s.volumeKg * 10) / 10.0)} kg lifted")
            if (s.personalBests.isEmpty()) Text("No personal bests this time.") else Text("Personal bests: ${s.personalBests.distinct().joinToString()}", color = Good)
            Text("Steps so far: ${day?.steps ?: 0}")
            Text("Water today: ${totals?.waterMl ?: 0} ml")
            if (s.completed) Text("Protein now if the window is open: whey or yogurt.")
            else Text("Partial progress is saved. Resume within 3 hours from Today.")
        }
    }
    BigButton("Back to Today", onClick = onExit)
}
