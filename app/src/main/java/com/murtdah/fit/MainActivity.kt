package com.murtdah.fit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.murtdah.fit.ui.BodyScreen
import com.murtdah.fit.ui.FitTheme
import com.murtdah.fit.ui.FoodScreen
import com.murtdah.fit.ui.LiveWorkoutScreen
import com.murtdah.fit.ui.ReadinessScreen
import com.murtdah.fit.ui.ReviewScreen
import com.murtdah.fit.ui.SettingsScreen
import com.murtdah.fit.ui.TodayScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingOpen.value = openTarget(intent)
        setContent {
            FitTheme {
                if (intent?.action in RATIONALE_ACTIONS) {
                    PrivacyText()
                } else {
                    AppRoot(pendingOpen.value) { pendingOpen.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpen.value = openTarget(intent)
    }

    private fun openTarget(i: Intent?): String? = i?.getStringExtra(EXTRA_OPEN)

    companion object {
        const val EXTRA_OPEN = "open"
        val RATIONALE_ACTIONS = setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", "android.intent.action.VIEW_PERMISSION_USAGE")
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Filled.FitnessCenter),
    Tab("food", "Food", Icons.Filled.Restaurant),
    Tab("body", "Body", Icons.Filled.MonitorWeight),
    Tab("review", "Review", Icons.Filled.BarChart),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun AppRoot(open: String?, consumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var askSkip by remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        when (open) {
            "workout" -> nav.navigate("live")
            "start" -> nav.navigate("readiness")
            "skip" -> askSkip = true
            "body" -> nav.navigateTab("body")
        }
        if (open != null) consumed()
    }

    Scaffold(
        bottomBar = {
            if (route in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigateTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(pad)) {
            composable("today") { TodayScreen(onStart = { nav.navigate("readiness") }, onResume = { nav.navigate("live") }) }
            composable("food") { FoodScreen() }
            composable("body") { BodyScreen() }
            composable("review") { ReviewScreen() }
            composable("settings") { SettingsScreen() }
            composable("readiness") {
                ReadinessScreen(
                    onStarted = { nav.navigate("live") { popUpTo("readiness") { inclusive = true } } },
                    onCancel = { nav.popBackStack() },
                )
            }
            composable("live") { LiveWorkoutScreen(onExit = { nav.navigateTab("today") }) }
        }
    }

    if (askSkip) SkipDialog { askSkip = false }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo("today") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val SKIP_REASONS = listOf("Tired", "Ate heavy", "No time", "Sick")

@Composable
fun SkipDialog(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Skip today") },
        text = {
            Column {
                Text("No problem. Pick the main reason so the weekly review can spot patterns.")
                SKIP_REASONS.forEach { r ->
                    TextButton(onClick = {
                        scope.launch {
                            context.app.repo.updateDay { it.copy(skipReason = r) }
                            onDone()
                        }
                    }) { Text(r) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun PrivacyText() {
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Murtdah Fit reads your step count from Health Connect to show daily progress and step reminders. Data stays on this phone. There are no accounts, no cloud and no analytics.")
    }
}
