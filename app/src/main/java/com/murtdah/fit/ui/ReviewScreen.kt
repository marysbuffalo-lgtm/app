package com.murtdah.fit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.murtdah.fit.app
import com.murtdah.fit.domain.WeekStats
import com.murtdah.fit.domain.WeeklyReview
import com.murtdah.fit.domain.WeeklyVerdict
import java.time.DayOfWeek

@Composable
fun ReviewScreen() {
    val context = LocalContext.current
    val repo = context.app.repo
    val today = repo.today()
    val end = if (today.dayOfWeek == DayOfWeek.SATURDAY) today else today.minusDays(((today.dayOfWeek.value + 1) % 7).toLong())
    val stats by produceState<WeekStats?>(null) { value = repo.weekStats(end) }
    val trend by produceState<String?>(null) {
        val a = repo.dao.fastedBetween(end.minusDays(6).toString(), end.toString()).map { it.kg }
        val b = repo.dao.fastedBetween(end.minusDays(13).toString(), end.minusDays(7).toString()).map { it.kg }
        val two = repo.dao.lastTwoScans()
        value = WeeklyVerdict.evaluate(a, b, two.getOrNull(0)?.fatMassKg, two.getOrNull(1)?.fatMassKg, two.getOrNull(0)?.bodyWaterPct).let { "${it.verdict.text}. ${it.detail}" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Weekly review", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Week ending Saturday $end", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val s = stats
        if (s == null) {
            Text("Loading...")
            return@Column
        }
        SectionCard("Adherence") {
            Text("Training sessions: ${s.trainingDone} of ${s.trainingPlanned} (plus ${s.recoveryDays} recovery days, which do not count against you)")
            Text("Step minimum hit: ${s.stepDaysHit} of 7 days")
            Text("Water target hit: ${s.waterDaysHit} of 7 days")
            Text("Protein floor hit: ${s.proteinDaysHit} of 7 days")
            Text("Ate after the window: ${s.lateEatingDays} days", color = if (s.lateEatingDays > 1) Warn else MaterialTheme.colorScheme.onSurface)
            Text("Sodium under the cap: ${s.sodiumDaysUnderCap} of 7 days")
            Text("Days with any log: ${s.daysLogged} of 7", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Fasted weight trend") { Text(trend ?: "Loading...") }
        WeeklyReview.skipPattern(s.skipReasons)?.let { SectionCard("Pattern") { Text(it) } }
        SectionCard("Focus for next week") { Text(WeeklyReview.focus(s), style = MaterialTheme.typography.titleMedium) }
    }
}
