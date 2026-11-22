package com.kiwixrag.app.rag

import com.kiwixrag.app.llm.LlmEngine
import com.kiwixrag.app.llm.PromptBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Phase(val label: String) {
    Idle(""),
    Searching("Searching the ZIM library and reading articles"),
    Generating("Generating answer"),
}

sealed interface Outcome {
    /** Nothing in the library matched the question. */
    data object NoResults : Outcome

    /** Retrieval worked but no model is loaded. */
    data object NoModel : Outcome

    data class Answer(val text: String) : Outcome
}

/**
 * One question end to end: search, read, build a prompt that fits the context window, generate, and
 * always free the decoded bitmaps afterwards, including on failure and cancellation.
 */
class AnswerRunner(private val pipeline: RagPipeline) {

    suspend fun run(
        question: String,
        topArticles: Int,
        inferenceLock: Mutex,
        engineProvider: () -> LlmEngine?,
        onPhase: (Phase) -> Unit,
        onRetrieved: (Retrieval) -> Unit,
        onPartial: (String) -> Unit,
    ): Outcome {
        var retrieval: Retrieval? = null
        try {
            onPhase(Phase.Searching)
            val r = pipeline.retrieve(question, topArticles, LlmEngine.MAX_IMAGES)
            retrieval = r
            if (r.sources.isEmpty()) return Outcome.NoResults
            onRetrieved(r)

            onPhase(Phase.Generating)
            return inferenceLock.withLock {
                // Read the engine under the lock so a model reload cannot close it mid-question.
                val engine = engineProvider() ?: return@withLock Outcome.NoModel
                val built = PromptBuilder.build(r, question, engine, usePhotos = true)
                val text = engine.generate(built.prompt, onPartial)
                Outcome.Answer(text.trim())
            }
        } finally {
            retrieval?.recycleImages()
            onPhase(Phase.Idle)
        }
    }
}
