package com.murtdah.fit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.murtdah.fit.app
import com.murtdah.fit.data.DayTotals
import com.murtdah.fit.data.Scan
import com.murtdah.fit.data.WeightEntry
import com.murtdah.fit.domain.BodyMath
import com.murtdah.fit.domain.WeeklyVerdict
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.util.Locale

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

@Composable
fun BodyScreen() {
    val context = LocalContext.current
    val repo = context.app.repo
    val scope = rememberCoroutineScope()
    val lb = repo.prefs.weightInPounds
    fun show(kg: Double) = if (lb) "${f1(kg * 2.20462)} lb" else "${f1(kg)} kg"
    fun toKg(v: Double) = if (lb) v / 2.20462 else v

    val weights by remember { repo.dao.weights() }.collectAsState(emptyList())
    val scans by remember { repo.dao.scans() }.collectAsState(emptyList())
    val date = remember { repo.today() }
    val totals by remember { repo.totalsFlow(date) }.collectAsState(DayTotals())
    var refresh by remember { mutableIntStateOf(0) }
    val day by produceState<com.murtdah.fit.data.DayLog?>(null, refresh) { value = repo.day() }

    var weight by remember { mutableStateOf("") }
    var fasted by remember { mutableStateOf(true) }
    val fields = remember { mutableStateOf(List(9) { "" }) }
    val labels = listOf("Weight kg", "Body fat %", "Skeletal muscle %", "Muscle mass kg", "Visceral fat", "Protein %", "Body water %", "BMR kcal", "Fat-free mass kg")
    var burn by remember { mutableStateOf("") }

    val verdict by produceState<com.murtdah.fit.domain.VerdictResult?>(null, weights, scans) {
        val end = date
        val thisWeek = repo.dao.fastedBetween(end.minusDays(6).toString(), end.toString()).map { it.kg }
        val lastWeek = repo.dao.fastedBetween(end.minusDays(13).toString(), end.minusDays(7).toString()).map { it.kg }
        val two = repo.dao.lastTwoScans()
        value = WeeklyVerdict.evaluate(thisWeek, lastWeek, two.getOrNull(0)?.fatMassKg, two.getOrNull(1)?.fatMassKg, two.getOrNull(0)?.bodyWaterPct)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Body and progress", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard("Daily weight") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(if (lb) "Weight lb" else "Weight kg", weight, Modifier.weight(1f), decimal = true) { weight = it }
                Checkbox(fasted, { fasted = it }); Text("Fasted")
            }
            Text("Only fasted readings (after the toilet, before food or water) are compared.", style = MaterialTheme.typography.bodySmall)
            SmallButton("Save weight") {
                val v = weight.toDoubleOrNull() ?: return@SmallButton
                val now = System.currentTimeMillis()
                scope.launch { repo.dao.insertWeight(WeightEntry(date = repo.dateOf(now).toString(), time = now, kg = toKg(v), fasted = fasted)); weight = "" }
            }
            LineChart(weights.filter { it.fasted }.map { if (lb) it.kg * 2.20462 else it.kg })
        }

        SectionCard("Weekly verdict") {
            val v = verdict
            if (v == null) Text("Calculating...") else {
                Text(v.verdict.text, style = MaterialTheme.typography.titleMedium, color = when (v.verdict) {
                    com.murtdah.fit.domain.Verdict.REAL_FAT_LOSS -> Good
                    com.murtdah.fit.domain.Verdict.FAT_GAIN -> Bad
                    else -> Warn
                })
                Text(v.detail)
            }
            val latest = weights.lastOrNull { it.fasted }?.kg ?: 114.0
            val latestScan = scans.lastOrNull()
            val bf = latestScan?.bodyFatPct ?: 34.0
            val fatKg = BodyMath.fatMassKg(latest, bf)
            val toLose = (fatKg - latest * 0.20).coerceAtLeast(0.0)
            Text(
                "Honest projection: about ${f1(toLose)} kg of fat separates you from roughly 20% body fat. " +
                    "At 0.5 to 1 kg a week that is ${BodyMath.weeksToLose(toLose, 1.0)} to ${BodyMath.weeksToLose(toLose, 0.5)} weeks. 10 kg in 2 months is not a realistic fat-loss target.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(if (date.dayOfWeek == DayOfWeek.FRIDAY) "Friday Feelfit scan (today)" else "Feelfit scan (Fridays)") {
            labels.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col, label ->
                        val i = row * 2 + col
                        NumberField(label, fields.value[i], Modifier.weight(1f), decimal = true) { v -> fields.value = fields.value.toMutableList().also { it[i] = v } }
                    }
                }
            }
            val w = fields.value[0].toDoubleOrNull()
            val bfp = fields.value[1].toDoubleOrNull()
            if (w != null && bfp != null) Text("Fat mass: ${f1(BodyMath.fatMassKg(w, bfp))} kg")
            fields.value[6].toDoubleOrNull()?.let { if (it < 47) Text("Body water under 47%. Dehydration distorts this reading.", color = Warn) }
            SmallButton("Save scan") {
                if (w == null || bfp == null) return@SmallButton
                val d = fields.value.map { it.toDoubleOrNull() }
                val now = System.currentTimeMillis()
                scope.launch {
                    repo.dao.insertScan(Scan(date = repo.dateOf(now).toString(), time = now, weightKg = w, bodyFatPct = bfp, skeletalMusclePct = d[2], muscleMassKg = d[3], visceralFat = d[4], proteinPct = d[5], bodyWaterPct = d[6], bmrKcal = d[7]?.toInt(), fatFreeMassKg = d[8]))
                    fields.value = List(9) { "" }
                }
            }
        }

        SectionCard("Fat mass (kg)") { LineChart(scans.map { it.fatMassKg }) }
        SectionCard("Visceral fat") { LineChart(scans.mapNotNull { it.visceralFat }, color = Warn) }
        SectionCard("Muscle mass (kg)") { LineChart(scans.mapNotNull { it.muscleMassKg }, color = MaterialTheme.colorScheme.secondary) }

        SectionCard("Energy balance today (estimate)") {
            val b = BodyMath.balance(totals.kcal, day?.watchBurnKcal, day?.steps ?: 0, repo.prefs.burnBaseKcal, repo.prefs.burnPerStep.toDouble())
            Text("Eaten ${b.eaten} kcal, burned ${b.burned} kcal${if (day?.watchBurnKcal != null) " (watch)" else " (estimate from steps)"}")
            Text("Balance ${if (b.net >= 0) "+" else ""}${b.net} kcal, plus or minus ${b.errorKcal} kcal", color = if (b.net < 0) Good else Warn)
            Text("These are estimates. Trust the 7 day weight trend over any single day.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField("Watch total burn kcal", burn, Modifier.weight(1f)) { burn = it }
                SmallButton("Save") {
                    val v = burn.toIntOrNull()
                    scope.launch { repo.updateDay { it.copy(watchBurnKcal = v) }; burn = ""; refresh++ }
                }
            }
        }

        if (weights.isNotEmpty()) SectionCard("Recent weights") {
            weights.takeLast(7).reversed().forEach { w -> Text("${w.date}  ${show(w.kg)}${if (w.fasted) "" else " (not fasted)"}") }
        }
    }
}
