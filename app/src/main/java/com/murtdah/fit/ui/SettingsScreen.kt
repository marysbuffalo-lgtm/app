package com.murtdah.fit.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.health.connect.client.PermissionController
import com.murtdah.fit.app
import com.murtdah.fit.data.Csv
import com.murtdah.fit.data.Targets
import com.murtdah.fit.domain.DayType
import com.murtdah.fit.domain.ProgramSeed
import com.murtdah.fit.domain.RestDefaults
import com.murtdah.fit.work.Notifications
import com.murtdah.fit.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repo = context.app.repo
    val prefs = repo.prefs
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { tick++ }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = Csv.export(repo.dao)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            message = "Exported."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.orEmpty()
            message = runCatching { "Imported ${Csv.import(repo.dao, text)} rows." }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    val hasHealth by produceState(false, tick) { value = context.app.steps.hasHealthPermission() }
    val pm = context.getSystemService(PowerManager::class.java)
    val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        message?.let { Text(it, color = Good) }

        SectionCard("Permissions") {
            Text("Notifications: ${if (Notifications.canPost(context)) "allowed" else "off"}")
            if (!Notifications.canPost(context) && Build.VERSION.SDK_INT >= 33) SmallButton("Allow notifications") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            Text("Exact reminder times: ${if (Scheduler.canExact(context)) "allowed" else "off. Reminders may arrive up to about 15 minutes late."}")
            if (!Scheduler.canExact(context) && Build.VERSION.SDK_INT >= 31) SmallButton("Allow exact alarms") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
            Text("Battery optimisation: ${if (batteryExempt) "exempt" else "on. Samsung may stop reminders and widgets in the background."}")
            if (!batteryExempt) SmallButton("Exempt this app") {
                @Suppress("BatteryLife")
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
        }

        SectionCard("Step source") {
            val hc = context.app.steps.healthConnectAvailable()
            Text("Health Connect: ${when { !hc -> "not available on this phone"; hasHealth -> "connected"; else -> "not granted" }}")
            if (hc && !hasHealth) SmallButton("Connect Health Connect") { healthLauncher.launch(setOf(context.app.steps.readPermission)) }
            if (!hc) SmallButton("Get Health Connect") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            }
            Text("Phone sensor: ${if (context.app.steps.hasActivityPermission()) "allowed" else "not allowed"}")
            if (!context.app.steps.hasActivityPermission() && Build.VERSION.SDK_INT >= 29) SmallButton("Allow physical activity") {
                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            ToggleRow("Prefer phone sensor over Health Connect", prefs.preferPhoneSensor) { prefs.preferPhoneSensor = it; tick++ }
            Text("Samsung Health must have Health Connect sync turned on for your watch steps to appear.", style = MaterialTheme.typography.bodySmall)
        }

        TargetsCard()
        WeekCard()
        RemindersCard { Scheduler.planToday(context) }
        RestCard()

        SectionCard("Sound, voice, vibration") {
            var s by remember { mutableStateOf(prefs.soundOn) }
            var v by remember { mutableStateOf(prefs.voiceOn) }
            var h by remember { mutableStateOf(prefs.vibrationOn) }
            ToggleRow("Sound effects", s) { s = it; prefs.soundOn = it }
            ToggleRow("Voice cues", v) { v = it; prefs.voiceOn = it }
            ToggleRow("Vibration", h) { h = it; prefs.vibrationOn = it }
        }

        SectionCard("Units and energy") {
            var lb by remember { mutableStateOf(prefs.weightInPounds) }
            ToggleRow("Show weight in pounds", lb) { lb = it; prefs.weightInPounds = it }
            var base by remember { mutableStateOf(prefs.burnBaseKcal.toString()) }
            var perStep by remember { mutableStateOf(prefs.burnPerStep.toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Base burn kcal", base, Modifier.weight(1f)) { base = it; it.toIntOrNull()?.let { v -> prefs.burnBaseKcal = v } }
                NumberField("kcal per step", perStep, Modifier.weight(1f), decimal = true) { perStep = it; it.toFloatOrNull()?.let { v -> prefs.burnPerStep = v } }
            }
        }

        MediaCheckCard()

        SectionCard("Backup") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Export CSV", Modifier.weight(1f)) { exportLauncher.launch("murtdah-fit-backup.csv") }
                SmallButton("Import CSV", Modifier.weight(1f)) { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }
            }
            Text("Import merges rows by id. Import into a fresh install to restore a backup.", style = MaterialTheme.typography.bodySmall)
        }

        var confirm by remember { mutableStateOf(false) }
        SmallButton("Restore program defaults") { confirm = true }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restore defaults?") },
            text = { Text("Targets, reminder times, rest times and day order go back to the defaults. Your logs are kept.") },
            confirmButton = { TextButton(onClick = { prefs.restoreDefaults(); confirm = false; tick++; Scheduler.planToday(context) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TargetsCard() {
    val prefs = LocalContext.current.app.repo.prefs
    val t = prefs.targets
    val values = remember {
        mutableStateOf(listOf(t.kcal, t.proteinG, t.proteinFloorG, t.fatG, t.carbsG, t.sodiumMaxMg, t.fiberG, t.waterMl, t.waterRampToMl, t.stepsMin, t.stepsTarget).map { it.toString() })
    }
    val labels = listOf("Calories", "Protein g", "Protein floor g", "Fat g", "Carbs g", "Sodium max mg", "Fiber g", "Water ml", "Water ramp to ml", "Steps minimum", "Steps target")
    SectionCard("Daily targets") {
        labels.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, label ->
                    val i = row * 2 + col
                    NumberField(label, values.value[i], Modifier.weight(1f)) { v -> values.value = values.value.toMutableList().also { it[i] = v } }
                }
            }
        }
        Text("Calories never go below ${Targets.MIN_KCAL} and the protein floor never below ${Targets.MIN_PROTEIN_FLOOR} g. You are still growing.", style = MaterialTheme.typography.bodySmall)
        SmallButton("Save targets") {
            val v = values.value.map { it.toIntOrNull() ?: 0 }
            prefs.targets = Targets(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10])
            val s = prefs.targets
            values.value = listOf(s.kcal, s.proteinG, s.proteinFloorG, s.fatG, s.carbsG, s.sodiumMaxMg, s.fiberG, s.waterMl, s.waterRampToMl, s.stepsMin, s.stepsTarget).map { it.toString() }
        }
    }
}

@Composable
private fun WeekCard() {
    val prefs = LocalContext.current.app.repo.prefs
    var tick by remember { mutableIntStateOf(0) }
    SectionCard("Weekly program (swap days)") {
        listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY).forEach { d ->
            var open by remember { mutableStateOf(false) }
            val current = remember(tick) { prefs.dayTypeFor(d) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.getDisplayName(TextStyle.SHORT, Locale.UK), Modifier.weight(0.3f))
                TextButton(onClick = { open = true }, modifier = Modifier.weight(1f)) { Text(ProgramSeed.plans.getValue(current).title) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DayType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(ProgramSeed.plans.getValue(t).title) }, onClick = { prefs.setDayType(d, t); open = false; tick++ })
                    }
                }
            }
        }
    }
}

@Composable
private fun RemindersCard(onSaved: () -> Unit) {
    val prefs = LocalContext.current.app.repo.prefs
    val r = prefs.reminders
    val values = remember { mutableStateOf(listOf(r.firstMeal, r.workoutTime, r.proteinCheck, r.eatingWarning, r.eatingClose, r.weighIn, r.bedtime, r.quietStart, r.quietEnd).map { it.toString() }) }
    val labels = listOf("First meal prompt", "Workout time", "Protein check", "Eating warning", "Eating window close", "Friday weigh-in", "Bedtime", "Quiet hours start", "Quiet hours end")
    var every by remember { mutableStateOf(r.waterEveryMin.toString()) }
    SectionCard("Reminder times (HH:mm)") {
        labels.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, label ->
                    val i = row * 2 + col
                    OutlinedTextField(values.value[i], { v -> values.value = values.value.toMutableList().also { it[i] = v } }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        }
        NumberField("Water reminder every (minutes)", every) { every = it }
        Text("At most 8 reminders a day. Water, steps and protein reminders skip themselves when you are on track.", style = MaterialTheme.typography.bodySmall)
        SmallButton("Save reminders") {
            val t = values.value.map { runCatching { LocalTime.parse(it) }.getOrNull() }
            if (t.any { it == null }) return@SmallButton
            prefs.reminders = r.copy(
                firstMeal = t[0]!!, workoutTime = t[1]!!, proteinCheck = t[2]!!, eatingWarning = t[3]!!, eatingClose = t[4]!!,
                weighIn = t[5]!!, bedtime = t[6]!!, quietStart = t[7]!!, quietEnd = t[8]!!, waterEveryMin = (every.toIntOrNull() ?: 90).coerceIn(30, 240),
            )
            onSaved()
        }
    }
}

@Composable
private fun RestCard() {
    val prefs = LocalContext.current.app.repo.prefs
    val r = prefs.restDefaults
    var strength by remember { mutableStateOf(r.strengthSec.toString()) }
    var mobility by remember { mutableStateOf(r.mobilitySec.toString()) }
    var phases by remember { mutableStateOf(r.betweenPhasesSec.toString()) }
    var overrides by remember { mutableStateOf(r.overrides.entries.joinToString(", ") { "${it.key}=${it.value}" }) }
    SectionCard("Rest times (seconds)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Strength", strength, Modifier.weight(1f)) { strength = it }
            NumberField("Mobility", mobility, Modifier.weight(1f)) { mobility = it }
            NumberField("Between phases", phases, Modifier.weight(1f)) { phases = it }
        }
        OutlinedTextField(overrides, { overrides = it }, label = { Text("Per exercise, e.g. tempo_squat=90") }, modifier = Modifier.fillMaxWidth())
        SmallButton("Save rest times") {
            val map = overrides.split(',').map { it.trim() }.filter { it.contains('=') }
                .mapNotNull { e -> e.substringAfter('=').trim().toIntOrNull()?.let { e.substringBefore('=').trim() to it } }.toMap()
            prefs.restDefaults = RestDefaults(strength.toIntOrNull() ?: 70, mobility.toIntOrNull() ?: 20, phases.toIntOrNull() ?: 105, map)
        }
    }
}

@Composable
private fun MediaCheckCard() {
    val media = LocalContext.current.app.media
    val c = remember { media.check() }
    SectionCard("Media check") {
        Text("GIFs found: ${c.found.size}. Missing: ${c.missing.size}.")
        if (c.missing.isNotEmpty()) Text("Placeholder shown for: ${c.missing.joinToString()}", style = MaterialTheme.typography.bodySmall)
        Text("Sounds found: ${c.soundsFound.size}. Using generated tones for: ${c.soundsMissing.joinToString()}", style = MaterialTheme.typography.bodySmall)
    }
}
