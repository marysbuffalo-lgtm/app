package com.murtdah.fit.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.murtdah.fit.app
import com.murtdah.fit.data.DayLog
import com.murtdah.fit.data.DayTotals
import com.murtdah.fit.data.StepReading
import com.murtdah.fit.data.StepSource
import com.murtdah.fit.domain.Deload
import com.murtdah.fit.domain.EatingWindow
import com.murtdah.fit.domain.ProgramSeed
import com.murtdah.fit.domain.WaterPace
import com.murtdah.fit.service.SessionController
import com.murtdah.fit.service.WorkoutService
import com.murtdah.fit.widget.Widgets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class TodayViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.app.repo
    val date = repo.today()
    val totals: StateFlow<DayTotals> = repo.totalsFlow(date).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayTotals())
    val day: StateFlow<DayLog?> = repo.dao.day(date.toString()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sessions = repo.dao.sessions(date.toString()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _steps = MutableStateFlow(StepReading(0, StepSource.NONE))
    val steps: StateFlow<StepReading> = _steps
    private val _now = MutableStateFlow(LocalTime.now())
    val now: StateFlow<LocalTime> = _now
    private val _deload = MutableStateFlow<String?>(null)
    val deload: StateFlow<String?> = _deload

    init {
        viewModelScope.launch {
            while (isActive) {
                _steps.value = runCatching { application.app.steps.read() }.getOrDefault(_steps.value)
                _now.value = LocalTime.now()
                delay(60_000)
            }
        }
        viewModelScope.launch { _deload.value = Deload.message(computeDeload()) }
    }

    private suspend fun computeDeload(): com.murtdah.fit.domain.DeloadAdvice {
        val all = repo.dao.allSessions().filter { it.completed && !it.recovery }
        if (all.isEmpty()) return com.murtdah.fit.domain.DeloadAdvice.NONE
        val weeks = all.map { repo.dateOf(it.startedAt).let { d -> d.toEpochDay() / 7 } }.toSortedSet()
        val thisWeek = date.toEpochDay() / 7
        var trained = 0
        var w = thisWeek - 1
        while (w in weeks) { trained++; w-- }
        val missed = (thisWeek - (weeks.last())).toInt() - 1
        return Deload.advise(trained, missed.coerceAtLeast(0))
    }

    fun addWater(ml: Int) = viewModelScope.launch {
        repo.addDrink(ml)
        Widgets.refresh(getApplication())
    }

    fun setPosture(done: Boolean) = viewModelScope.launch { repo.updateDay { it.copy(postureDone = done) } }

    fun resumable(): Boolean {
        val snap = SessionController.decode(repo.prefs.snapshot) ?: return false
        return System.currentTimeMillis() - snap.savedAt < SessionController.RESUME_WINDOW_MS
    }
}

@Composable
fun TodayScreen(onStart: () -> Unit, onResume: () -> Unit, vm: TodayViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = context.app.repo
    val totals by vm.totals.collectAsState()
    val day by vm.day.collectAsState()
    val steps by vm.steps.collectAsState()
    val now by vm.now.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val deload by vm.deload.collectAsState()
    val live by SessionController.live.collectAsState()
    val targets = repo.prefs.targets
    val type = repo.prefs.dayTypeFor(vm.date.dayOfWeek)
    val plan = ProgramSeed.plans.getValue(type)
    val waterTarget = repo.waterTargetMl(vm.date)
    val close = repo.prefs.reminders.eatingClose

    LaunchedEffect(Unit) { if (!repo.prefs.onboarded) repo.prefs.onboarded = true }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val dayName = vm.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        Text("$dayName", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard(plan.title) {
            val done = sessions.any { it.completed && !it.recovery }
            val recovered = sessions.any { it.recovery }
            Text(
                when {
                    done -> "Done today. Nice work."
                    recovered -> "Logged as a recovery day."
                    else -> "Warm-up, ${if (type.strength) "activation, " else ""}main session, posture block and cooldown."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            deload?.let { Text(it, color = Warn) }
            val active = live?.let { it.session.state !is com.murtdah.fit.domain.SessionState.Done } == true
            when {
                active -> BigButton("Return to workout", onClick = onResume)
                vm.resumable() && !done -> {
                    BigButton("Resume unfinished session", onClick = {
                        WorkoutService.resume(context)
                        onResume()
                    })
                    SmallButton("Start fresh instead", onClick = onStart)
                }
                else -> BigButton(if (done) "Train again" else "Start ${dayName}", onClick = onStart)
            }
        }

        SectionCard("Posture block") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = day?.postureDone == true, onCheckedChange = { vm.setPosture(it) })
                Text("10 minutes done today")
            }
            Text(ProgramSeed.POSTURE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Steps") {
            val goal = plan.stepsGoal
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Ring(
                    progress = steps.steps / goal.toFloat(),
                    size = 120.dp,
                    color = if (steps.steps >= targets.stepsMin) Good else Warn,
                    secondary = targets.stepsMin / goal.toFloat(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${steps.steps}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("of $goal", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column {
                    Text("Minimum ${targets.stepsMin}")
                    Text("Target $goal")
                    Text("Source: ${steps.source.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    steps.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
                }
            }
        }

        SectionCard("Water") {
            val expected = WaterPace.expectedMl(now, waterTarget)
            Metric("Water", totals.waterMl, waterTarget, "ml")
            Text("Pace: ${WaterPace.litres(expected)} expected by now", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(250, 330, 500).forEach { ml -> SmallButton("+$ml", Modifier.weight(1f)) { vm.addWater(ml) } }
            }
        }

        SectionCard("Food today") {
            Metric("Protein", totals.proteinG, targets.proteinG, "g")
            if (totals.proteinG < targets.proteinFloorG) Text("Floor is ${targets.proteinFloorG} g.", style = MaterialTheme.typography.bodySmall, color = Warn)
            Metric("Calories", totals.kcal, targets.kcal, "kcal", lowerIsBetter = true)
            Metric("Sodium", totals.sodiumMg, targets.sodiumMaxMg, "mg", lowerIsBetter = true)
            if (totals.sweetsFirst) Text("Sweet came before protein today.", color = Warn)
        }

        SectionCard("Eating window") {
            val late = EatingWindow.isLate(now, close)
            Text(EatingWindow.statusText(now, close), color = if (late) Bad else Good, fontWeight = FontWeight.SemiBold)
            val last = totals.lastBite?.let { repo.localTime(it).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "nothing logged"
            Text("Last bite: $last")
            if (totals.lateMeal) Text("Food was logged after ${close}.", color = Bad)
        }
    }
}
