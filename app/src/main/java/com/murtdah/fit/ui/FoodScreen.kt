package com.murtdah.fit.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.murtdah.fit.data.Favourite
import com.murtdah.fit.data.Meal
import com.murtdah.fit.domain.EatingWindow
import com.murtdah.fit.widget.Widgets
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun FoodScreen() {
    val context = LocalContext.current
    val repo = context.app.repo
    val scope = rememberCoroutineScope()
    val date = remember { repo.today() }
    val totals by remember { repo.totalsFlow(date) }.collectAsState(DayTotals())
    val meals by remember { repo.dao.meals(date.toString()) }.collectAsState(emptyList())
    val drinks by remember { repo.dao.drinks(date.toString()) }.collectAsState(emptyList())
    val favourites by remember { repo.dao.favourites() }.collectAsState(emptyList())
    val t = repo.prefs.targets
    val weekStart = remember { date.minusDays(((date.dayOfWeek.value % 7)).toLong()) }
    val flexUsed by produceState(0, meals) {
        value = repo.dao.mealsBetween(weekStart.toString(), weekStart.plusDays(6).toString()).count { it.flex }
    }

    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var sodium by remember { mutableStateOf("") }
    var fiber by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var sweet by remember { mutableStateOf(false) }
    var flex by remember { mutableStateOf(false) }
    var saveFav by remember { mutableStateOf(false) }
    var drinkKind by remember { mutableStateOf("milk") }
    var drinkMl by remember { mutableStateOf("") }
    var drinkKcal by remember { mutableStateOf("") }
    var drinkProtein by remember { mutableStateOf("") }

    fun fill(f: Favourite) {
        name = f.name; kcal = "${f.kcal}"; protein = "${f.proteinG}"; fat = "${f.fatG}"; carbs = "${f.carbsG}"
        sodium = "${f.sodiumMg}"; fiber = "${f.fiberG}"; sweet = f.sweet
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Food", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        SectionCard("Today against targets") {
            Metric("Calories", totals.kcal, t.kcal, "kcal", lowerIsBetter = true)
            Metric("Protein", totals.proteinG, t.proteinG, "g")
            Metric("Fat", totals.fatG, t.fatG, "g", lowerIsBetter = true)
            Metric("Carbs", totals.carbsG, t.carbsG, "g", lowerIsBetter = true)
            Metric("Sodium", totals.sodiumMg, t.sodiumMaxMg, "mg", lowerIsBetter = true)
            Metric("Fiber", totals.fiberG, t.fiberG, "g")
            if (totals.proteinG < t.proteinFloorG) Text("Protein is under the ${t.proteinFloorG} g floor.", color = Warn)
            if (totals.sweetsFirst) Text("Sweet first today. Next time open with protein.", color = Warn)
            if (totals.lateMeal) Text("Something was eaten after the window closed.", color = Bad)
            Text("Treat budget this week: ${flexUsed} of 1 flex meal used (Saturday).", style = MaterialTheme.typography.bodySmall)
        }

        if (favourites.isNotEmpty()) SectionCard("Favourites") {
            favourites.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { fill(f) }, modifier = Modifier.weight(1f)) { Text("${f.name}  ${f.kcal} kcal, ${f.proteinG} g protein") }
                    TextButton(onClick = { scope.launch { repo.dao.deleteFavourite(f.id) } }) { Text("Remove") }
                }
            }
        }

        SectionCard("Log a meal") {
            OutlinedTextField(name, { name = it }, label = { Text("Meal name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("kcal", kcal, Modifier.weight(1f)) { kcal = it }
                NumberField("Protein g", protein, Modifier.weight(1f)) { protein = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Fat g", fat, Modifier.weight(1f)) { fat = it }
                NumberField("Carbs g", carbs, Modifier.weight(1f)) { carbs = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Sodium mg", sodium, Modifier.weight(1f)) { sodium = it }
                NumberField("Fiber g", fiber, Modifier.weight(1f)) { fiber = it }
            }
            OutlinedTextField(time, { time = it }, label = { Text("Time eaten (HH:mm)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sweet, { sweet = it }); Text("Sweet") }
            if (date.dayOfWeek == DayOfWeek.SATURDAY) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(flex, { flex = it }); Text("Flex meal (weekly treat)") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(saveFav, { saveFav = it }); Text("Save as favourite") }
            val parsedTime = runCatching { LocalTime.parse(time) }.getOrNull()
            if (parsedTime != null && EatingWindow.isLate(parsedTime, repo.prefs.reminders.eatingClose)) Text("This is after the eating window.", color = Warn)
            BigButton("Save meal", enabled = name.isNotBlank() && kcal.isNotBlank() && parsedTime != null) {
                val at = com.murtdah.fit.domain.DayClock.at(date, parsedTime!!, repo.zone).toInstant().toEpochMilli()
                val m = Meal(
                    date = date.toString(), time = at, name = name.trim(), kcal = kcal.toIntOrNull() ?: 0, proteinG = protein.toIntOrNull() ?: 0,
                    fatG = fat.toIntOrNull() ?: 0, carbsG = carbs.toIntOrNull() ?: 0, sodiumMg = sodium.toIntOrNull() ?: 0, fiberG = fiber.toIntOrNull() ?: 0,
                    sweet = sweet, flex = flex,
                )
                scope.launch {
                    repo.dao.insertMeal(m)
                    if (saveFav) repo.dao.insertFavourite(Favourite(name = m.name, kcal = m.kcal, proteinG = m.proteinG, fatG = m.fatG, carbsG = m.carbsG, sodiumMg = m.sodiumMg, fiberG = m.fiberG, sweet = m.sweet))
                    Widgets.refresh(context)
                    name = ""; kcal = ""; protein = ""; fat = ""; carbs = ""; sodium = ""; fiber = ""; sweet = false; flex = false; saveFav = false
                }
            }
        }

        SectionCard("Log a drink with calories") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("milk", "juice", "shake").forEach { k ->
                    androidx.compose.material3.FilterChip(selected = drinkKind == k, onClick = { drinkKind = k }, label = { Text(k) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("ml", drinkMl, Modifier.weight(1f)) { drinkMl = it }
                NumberField("kcal", drinkKcal, Modifier.weight(1f)) { drinkKcal = it }
                NumberField("Protein g", drinkProtein, Modifier.weight(1f)) { drinkProtein = it }
            }
            Text("Counts toward water and toward calories.", style = MaterialTheme.typography.bodySmall)
            SmallButton("Save drink") {
                val ml = drinkMl.toIntOrNull() ?: return@SmallButton
                scope.launch {
                    repo.addDrink(ml, drinkKind, drinkKcal.toIntOrNull() ?: 0, drinkProtein.toIntOrNull() ?: 0)
                    drinkMl = ""; drinkKcal = ""; drinkProtein = ""
                }
            }
        }

        SectionCard("Logged today") {
            if (meals.isEmpty() && drinks.isEmpty()) Text("Nothing yet.")
            meals.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${repo.localTime(m.time).format(DateTimeFormatter.ofPattern("HH:mm"))}  ${m.name}${if (m.sweet) " (sweet)" else ""}${if (m.flex) " (flex)" else ""}")
                        Text("${m.kcal} kcal, P ${m.proteinG} g, F ${m.fatG} g, C ${m.carbsG} g, Na ${m.sodiumMg} mg", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { scope.launch { repo.dao.deleteMeal(m.id) } }) { Text("Delete") }
                }
            }
            drinks.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${repo.localTime(d.time).format(DateTimeFormatter.ofPattern("HH:mm"))}  ${d.kind} ${d.ml} ml${if (d.kcal > 0) ", ${d.kcal} kcal" else ""}", Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { repo.dao.deleteDrink(d.id) } }) { Text("Delete") }
                }
            }
        }

        val supplements by remember { repo.dao.supplements() }.collectAsState(emptyList())
        var newSupp by remember { mutableStateOf("") }
        SectionCard("Supplements (your list)") {
            supplements.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.lastTakenDate == date.toString(), { taken ->
                        scope.launch { repo.dao.upsertSupplement(s.copy(lastTakenDate = if (taken) date.toString() else null)) }
                    })
                    Column(Modifier.weight(1f)) {
                        Text(s.name)
                        if (!s.halalChecked) TextButton(onClick = { scope.launch { repo.dao.upsertSupplement(s.copy(halalChecked = true)) } }) {
                            Text("Check halal certification. Tap when confirmed.", color = Warn)
                        }
                    }
                    TextButton(onClick = { scope.launch { repo.dao.deleteSupplement(s.id) } }) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newSupp, { newSupp = it }, label = { Text("Add supplement") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val n = newSupp.trim()
                    if (n.isNotEmpty()) scope.launch { repo.dao.insertSupplement(com.murtdah.fit.data.Supplement(name = n)); newSupp = "" }
                }) { Text("Add") }
            }
            if (newSupp.isNotBlank()) Text("Check halal certification.", color = Warn, style = MaterialTheme.typography.bodySmall)
        }
    }
}
