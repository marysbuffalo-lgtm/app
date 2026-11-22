package com.murtdah.fit.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.murtdah.fit.MainActivity
import com.murtdah.fit.app
import com.murtdah.fit.domain.EatingWindow
import com.murtdah.fit.domain.ProgramSeed
import com.murtdah.fit.domain.ReminderPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val bg = ColorProvider(Color(0xFF15181C))
private val fg = ColorProvider(Color(0xFFECEFF3))
private val dim = ColorProvider(Color(0xFF9AA4AE))
private val accent = ColorProvider(Color(0xFF4FD1A5))
private val warn = ColorProvider(Color(0xFFFF7A6B))

data class WidgetData(
    val steps: Int, val stepsMin: Int, val stepsGoal: Int,
    val waterMl: Int, val waterTarget: Int,
    val proteinG: Int, val proteinTarget: Int,
    val workout: String, val trained: Boolean,
    val window: String, val late: Boolean, val nextReminder: String,
)

suspend fun loadWidgetData(context: Context): WidgetData {
    val repo = context.app.repo
    val date = repo.today()
    val day = repo.day(date)
    val totals = repo.totalsNow(date)
    val type = repo.prefs.dayTypeFor(date.dayOfWeek)
    val plan = ProgramSeed.plans.getValue(type)
    val now = LocalTime.now(repo.zone)
    val close = repo.prefs.reminders.eatingClose
    val next = ReminderPlanner.plan(date.dayOfWeek, true, repo.prefs.reminders).firstOrNull { it.time > now }
    return WidgetData(
        steps = day.steps, stepsMin = repo.prefs.targets.stepsMin, stepsGoal = plan.stepsGoal,
        waterMl = totals.waterMl, waterTarget = repo.waterTargetMl(date),
        proteinG = totals.proteinG, proteinTarget = repo.prefs.targets.proteinG,
        workout = plan.title, trained = repo.dao.sessionsNow(date.toString()).any { it.completed },
        window = EatingWindow.statusText(now, close), late = EatingWindow.isLate(now, close),
        nextReminder = next?.let { "Next: ${it.type.name.lowercase().replace('_', ' ')} at ${it.time.format(DateTimeFormatter.ofPattern("HH:mm"))}" } ?: "No more reminders today",
    )
}

class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.app.repo.addDrink(330)
        Widgets.refresh(context)
    }
}

@Composable
private fun Label(text: String, size: Int = 12, color: ColorProvider = fg, bold: Boolean = false) =
    Text(text, style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))

private fun progress(v: Int, max: Int) = if (max <= 0) 0f else (v.toFloat() / max).coerceIn(0f, 1f)

class SmallWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = loadWidgetData(context)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(bg).padding(10.dp).clickable(actionStartActivity<MainActivity>())) {
                Label("Steps", 11, dim)
                Label("${d.steps}", 20, if (d.steps >= d.stepsMin) accent else fg, bold = true)
                LinearProgressIndicator(progress(d.steps, d.stepsGoal), GlanceModifier.fillMaxWidth(), color = accent, backgroundColor = dim)
                Spacer(GlanceModifier.height(6.dp))
                Label("Water ${d.waterMl} / ${d.waterTarget} ml", 11, dim)
                LinearProgressIndicator(progress(d.waterMl, d.waterTarget), GlanceModifier.fillMaxWidth(), color = accent, backgroundColor = dim)
                Spacer(GlanceModifier.height(6.dp))
                Button("+330 ml", onClick = actionRunCallback<AddWaterAction>())
            }
        }
    }
}

class MediumWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = loadWidgetData(context)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(bg).padding(12.dp)) {
                Label(d.workout, 14, fg, bold = true)
                Label(if (d.trained) "Done today" else "Not started", 11, if (d.trained) accent else dim)
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Column(GlanceModifier.defaultWeight()) {
                        Label("Steps ${d.steps}", 11)
                        Label("Water ${d.waterMl} ml", 11)
                        Label("Protein ${d.proteinG} / ${d.proteinTarget} g", 11)
                    }
                    Button(
                        "Start",
                        onClick = actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "start")),
                    )
                }
            }
        }
    }
}

class WideWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = loadWidgetData(context)
        provideContent {
            Row(
                GlanceModifier.fillMaxSize().background(bg).padding(10.dp).clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(d.window, 13, if (d.late) warn else accent, bold = true)
                Spacer(GlanceModifier.width(10.dp))
                Label(d.nextReminder, 11, dim)
            }
        }
    }
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SmallWidget() }
class MediumWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MediumWidget() }
class WideWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WideWidget() }

object Widgets {
    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                SmallWidget().updateAll(context)
                MediumWidget().updateAll(context)
                WideWidget().updateAll(context)
            }
        }
    }
}
