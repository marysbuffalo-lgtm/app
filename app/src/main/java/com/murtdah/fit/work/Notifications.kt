package com.murtdah.fit.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.murtdah.fit.MainActivity
import com.murtdah.fit.R
import com.murtdah.fit.app
import com.murtdah.fit.domain.DayClock
import com.murtdah.fit.domain.FireContext
import com.murtdah.fit.domain.PlannedReminder
import com.murtdah.fit.domain.ReminderPlanner
import com.murtdah.fit.domain.ReminderType
import com.murtdah.fit.domain.StepPace
import com.murtdah.fit.domain.WaterPace
import com.murtdah.fit.domain.WorkoutDelay
import com.murtdah.fit.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_IMPORTANT = "important"
    const val CH_WORKOUT = "workout"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Water, steps, protein and meal reminders"
        })
        nm.createNotificationChannel(NotificationChannel(CH_IMPORTANT, "Eating window and weigh-in", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORKOUT, "Live workout", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        })
    }

    fun canPost(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(context: Context, code: Int, open: String? = null): PendingIntent = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java).apply { open?.let { putExtra(MainActivity.EXTRA_OPEN, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun action(context: Context, action: String, code: Int, extra: Int = 0, type: String = ""): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, ActionReceiver::class.java).setAction(action).putExtra("value", extra).putExtra("type", type).putExtra("notif", code / 10),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

object Scheduler {
    private const val PLAN_WORK = "plan_day"
    private const val REFRESH_WORK = "refresh"

    fun ensureBackgroundWork(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(PLAN_WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PlanWorker>(6, TimeUnit.HOURS).build())
        wm.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build())
    }

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun planToday(context: Context) {
        val repo = context.app.repo
        val date = repo.today()
        val type = repo.prefs.dayTypeFor(date.dayOfWeek)
        val training = type.strength || type.name == "WALK_CORE" || type.name == "MOBILITY"
        val plan = ReminderPlanner.plan(date.dayOfWeek, training, repo.prefs.reminders)
        val now = System.currentTimeMillis()
        plan.forEach { r ->
            val at = DayClock.at(date, r.time, repo.zone).toInstant().toEpochMilli()
            if (at > now) schedule(context, r, at)
        }
        val tomorrow = date.plusDays(1)
        ReminderPlanner.plan(tomorrow.dayOfWeek, training, repo.prefs.reminders)
            .filter { it.type == ReminderType.WEIGH_IN }
            .forEach { schedule(context, it, DayClock.at(tomorrow, it.time, repo.zone).toInstant().toEpochMilli()) }
    }

    fun schedule(context: Context, r: PlannedReminder, at: Long) = scheduleRaw(context, r.type.name, r.id, at)

    fun scheduleRaw(context: Context, type: String, id: Int, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, id, Intent(context, ReminderReceiver::class.java).putExtra("type", type).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class PlanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Scheduler.planToday(applicationContext)
        return Result.success()
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { applicationContext.app.steps.read() }
        Widgets.refresh(applicationContext)
        return Result.success()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.ensureBackgroundWork(context)
        Scheduler.planToday(context)
        Widgets.refresh(context)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = runCatching { ReminderType.valueOf(intent.getStringExtra("type")!!) }.getOrNull() ?: return
        val id = intent.getIntExtra("id", 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fire(context, type, id)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, type: ReminderType, id: Int) {
        if (!Notifications.canPost(context)) return
        val repo = context.app.repo
        val now = LocalTime.now(repo.zone)
        val day = repo.day()
        val totals = repo.totalsNow()
        val steps = runCatching { context.app.steps.read().steps }.getOrDefault(day.steps)
        val trained = repo.dao.sessionsNow(repo.today().toString()).any { it.completed }
        val ctx = FireContext(now, totals.waterMl, repo.waterTargetMl(), totals.proteinG, steps, totals.lateMeal, trained, day.notificationsFired)
        if (!ReminderPlanner.shouldFire(type, ctx, repo.prefs.reminders)) return

        val target = repo.waterTargetMl()
        val b = NotificationCompat.Builder(context, if (type.priority >= ReminderType.EATING_CLOSE.priority) Notifications.CH_IMPORTANT else Notifications.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat)
            .setAutoCancel(true)
            .setContentIntent(Notifications.openApp(context, id))

        when (type) {
            ReminderType.WATER -> {
                val expected = WaterPace.expectedMl(now, target)
                b.setContentTitle("Water check")
                    .setContentText("${WaterPace.litres(totals.waterMl)} done, ${WaterPace.litres(expected)} expected by now.")
                    .addAction(0, "+330 ml", Notifications.action(context, ActionReceiver.ADD_WATER, id * 10 + 1, 330))
                    .addAction(0, "+500 ml", Notifications.action(context, ActionReceiver.ADD_WATER, id * 10 + 2, 500))
                    .addAction(0, "Snooze 30 min", Notifications.action(context, ActionReceiver.SNOOZE, id * 10 + 3, 30, type.name))
            }
            ReminderType.FIRST_MEAL -> b.setContentTitle("First meal soon").setContentText("Protein first. Whey or yogurt before anything sweet.")
            ReminderType.WORKOUT_SOON, ReminderType.WORKOUT_NOW -> {
                val last = repo.dao.lastMeal()
                val mins = last?.let { ((System.currentTimeMillis() - it.time) / 60_000).toInt() }
                val delay = WorkoutDelay.suggestedDelayMinutes(mins, last?.kcal)
                val dayType = repo.prefs.dayTypeFor(repo.today().dayOfWeek)
                val title = if (type == ReminderType.WORKOUT_SOON) "Workout in 45 minutes" else "Time to train"
                val text = if (delay > 0) "Large meal ${mins} min ago. Delay up to 2 hours instead of skipping." else "Today: ${com.murtdah.fit.domain.ProgramSeed.plans.getValue(dayType).title}"
                b.setContentTitle(title).setContentText(text)
                    .addAction(0, "Start", Notifications.openApp(context, id * 10 + 4, "start"))
                    .addAction(0, "Delay 1 h", Notifications.action(context, ActionReceiver.DELAY_WORKOUT, id * 10 + 5, 60))
                    .addAction(0, "Skip today", Notifications.openApp(context, id * 10 + 6, "skip"))
            }
            ReminderType.STEPS -> {
                val pace = StepPace.status(now, steps)
                b.setContentTitle("Steps behind pace")
                    .setContentText("$steps steps, ${pace.expected} expected by now. A ${StepPace.walkMinutesFor(pace.behindBy)} minute walk closes the gap.")
            }
            ReminderType.EATING_WARNING -> b.setContentTitle("Eating window closes at 20:00")
                .setContentText("30 minutes left. Protein now if you still need it: ${totals.proteinG} g so far.")
            ReminderType.EATING_CLOSE -> b.setContentTitle("Eating window closed").setContentText("Water only from now. Kitchen closed until 12:00.")
            ReminderType.LATE_FOOD_CHECK -> b.setContentTitle("Evening check").setContentText("Water and sleep now. Tomorrow starts at 12:00.")
            ReminderType.PROTEIN_CHECK -> b.setContentTitle("Protein check").setContentText("${totals.proteinG} g so far. A whey shake or Greek yogurt gets you back on track.")
            ReminderType.WEIGH_IN -> b.setContentTitle("Friday weigh-in").setContentText("Weigh in fasted, then do the Feelfit scan, before water or food.")
                .setContentIntent(Notifications.openApp(context, id, "body"))
            ReminderType.BEDTIME -> b.setContentTitle("Bedtime").setContentText("Screens down. Sleep drives tomorrow's hunger and training.")
        }
        NotificationManagerCompat.from(context).notify(id, b.build())
        repo.updateDay { it.copy(notificationsFired = it.notificationsFired + 1) }
        if (type == ReminderType.EATING_CLOSE) Widgets.refresh(context)
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ADD_WATER = "com.murtdah.fit.ADD_WATER"
        const val SNOOZE = "com.murtdah.fit.SNOOZE"
        const val DELAY_WORKOUT = "com.murtdah.fit.DELAY_WORKOUT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getIntExtra("value", 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ADD_WATER -> {
                        context.app.repo.addDrink(value)
                        Widgets.refresh(context)
                    }
                    SNOOZE -> Scheduler.scheduleRaw(context, intent.getStringExtra("type") ?: "WATER", 9001, System.currentTimeMillis() + value * 60_000L)
                    DELAY_WORKOUT -> Scheduler.scheduleRaw(context, ReminderType.WORKOUT_NOW.name, 9002, System.currentTimeMillis() + value * 60_000L)
                }
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notif", 0))
            } finally {
                pending.finish()
            }
        }
    }
}

