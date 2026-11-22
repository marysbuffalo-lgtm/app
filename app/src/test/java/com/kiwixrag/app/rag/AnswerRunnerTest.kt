package com.kiwixrag.app.rag

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiwixrag.app.FixtureZimLibrary
import com.kiwixrag.app.llm.FakeEngine
import com.kiwixrag.app.llm.LlmEngine
import com.kiwixrag.app.llm.RagPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerRunnerTest {
    private val runner = AnswerRunner(RagPipeline(FixtureZimLibrary()))
    private val phases = mutableListOf<Phase>()
    private var retrieval: Retrieval? = null
    private val partials = mutableListOf<String>()

    private suspend fun run(engine: LlmEngine?, q: String = "What is photosynthesis?") = runner.run(
        q, 4, Mutex(), { engine }, { phases += it }, { retrieval = it }, { partials += it },
    )

    @Test
    fun successfulAnswerStreamsPassesPhotosAndRecyclesBitmaps() = runTest {
        val engine = FakeEngine()
        val out = run(engine)
        assertEquals(Outcome.Answer("Answer [S1]."), out)
        assertEquals(listOf("Ans", "Answer [S1]."), partials)
        assertEquals(listOf(Phase.Searching, Phase.Generating, Phase.Idle), phases)
        val prompt = engine.lastPrompt!!
        assertEquals(1, prompt.photos.size)
        assertTrue(prompt.contextBlock.contains("test_wiki_2026-01.zim"))
        assertTrue(retrieval!!.images.all { it.bitmap.isRecycled })
    }

    @Test
    fun bitmapsRecycledWhenModelThrows() = runTest {
        val engine = FakeEngine().also { it.failWith = IllegalStateException("gpu lost") }
        try { run(engine); fail() } catch (e: IllegalStateException) { assertEquals("gpu lost", e.message) }
        assertTrue(retrieval!!.images.isNotEmpty() && retrieval!!.images.all { it.bitmap.isRecycled })
        assertEquals(Phase.Idle, phases.last())
    }

    @Test
    fun bitmapsRecycledOnCancellationMidGeneration() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<String>()
        val engine = object : LlmEngine by FakeEngine() {
            override suspend fun generate(prompt: RagPrompt, onPartial: (String) -> Unit): String {
                started.complete(Unit)
                return never.await()
            }
        }
        val job = async { run(engine) }
        started.await()
        job.cancel()
        try { job.await(); fail() } catch (e: CancellationException) { }
        assertTrue(retrieval!!.images.isNotEmpty() && retrieval!!.images.all { it.bitmap.isRecycled })
        assertEquals(Phase.Idle, phases.last())
    }

    @Test
    fun noModelStillReturnsSourcesAndFreesBitmaps() = runTest {
        assertEquals(Outcome.NoModel, run(null))
        assertTrue(retrieval!!.sources.isNotEmpty())
        assertTrue(retrieval!!.images.all { it.bitmap.isRecycled })
    }

    @Test
    fun noMatchesReportsNoResultsWithoutTouchingTheModel() = runTest {
        val engine = FakeEngine()
        assertEquals(Outcome.NoResults, run(engine, "quantum zebra bicycle"))
        assertEquals(null, engine.lastPrompt)
    }

    @Test
    fun inferenceLockSerializesQuestions() = runBlocking {
        val lock = Mutex()
        val inside = java.util.concurrent.atomic.AtomicInteger()
        var maxInside = 0
        val engine = object : LlmEngine by FakeEngine() {
            override suspend fun generate(prompt: RagPrompt, onPartial: (String) -> Unit): String {
                maxInside = maxOf(maxInside, inside.incrementAndGet())
                kotlinx.coroutines.delay(30)
                inside.decrementAndGet()
                return "ok"
            }
        }
        val a = async { runner.run("What is photosynthesis?", 4, lock, { engine }, {}, {}, {}) }
        val b = async { runner.run("What is photosynthesis?", 4, lock, { engine }, {}, {}, {}) }
        a.await(); b.await()
        assertEquals(1, maxInside)
    }
}
