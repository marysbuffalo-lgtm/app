package com.murtdah.fit.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.murtdah.fit.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyTabOpensWithoutCrashing() {
        rule.waitForIdle()
        rule.onNodeWithText("Posture block").assertExists()
        for ((tab, heading) in listOf("Food" to "Log a meal", "Body" to "Body and progress", "Review" to "Weekly review", "Settings" to "Permissions")) {
            rule.onAllNodesWithText(tab)[0].performClick()
            rule.waitForIdle()
            rule.waitUntil(5_000) { rule.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onAllNodesWithText("Today")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Start", substring = true)[0].performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Ready to train?").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("6.5 h or more").performScrollTo().performClick()
        rule.onNodeWithText("Normal").performScrollTo().performClick()
        rule.onNodeWithText("No").performScrollTo().performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Amber", substring = true).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Green").fetchSemanticsNodes().isNotEmpty() }
    }
}
