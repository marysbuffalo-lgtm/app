package com.murtdah.fit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.murtdah.fit.domain.Adjustments
import com.murtdah.fit.domain.DayType
import com.murtdah.fit.domain.Session
import com.murtdah.fit.domain.SessionEvent
import com.murtdah.fit.domain.SessionMachine
import com.murtdah.fit.domain.SessionPlanner
import com.murtdah.fit.domain.SessionState
import com.murtdah.fit.service.LiveSession
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveWorkoutUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun loggingAStrengthSetRecordsRepsInReserveAndMovesToRest() {
        val steps = SessionPlanner.build(DayType.UPPER_A)
        val index = steps.indexOfFirst { it.strength && it.tempoDownSec == 0 }
        val state = mutableStateOf(
            LiveSession(1, DayType.UPPER_A, Adjustments(), Session(steps, SessionState.Work(index, 0, null)), 0),
        )
        val events = mutableListOf<SessionEvent>()
        rule.setContent {
            FitTheme {
                LiveWorkout(state.value, now = 1_000, onEvent = { e ->
                    events += e
                    state.value = state.value.copy(session = SessionMachine.reduce(state.value.session, e, 1_000).session)
                }, onExit = {})
            }
        }
        rule.onNodeWithText(steps[index].name).assertIsDisplayed()
        rule.onNodeWithTag("setDone").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(state.value.session.state is SessionState.Log)
        rule.onNodeWithTag("rir2").performScrollTo().performClick()
        rule.waitForIdle()
        val logged = events.filterIsInstance<SessionEvent.Logged>().single()
        assertEquals(2, logged.rir)
        assertEquals(steps[index].repsMax, logged.reps)
        assertTrue(state.value.session.state is SessionState.Rest)
        rule.onNodeWithText("Skip rest").assertIsDisplayed()
    }
}
