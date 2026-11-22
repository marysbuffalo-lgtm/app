package com.kiwixrag.app.llm

import android.graphics.Bitmap

class PromptPhoto(val label: String, val caption: String, val bitmap: Bitmap)

/**
 * Engine independent prompt. Each engine turns it into its own wire format (Gemma turns for MediaPipe,
 * structured messages for LiteRT-LM).
 */
class RagPrompt(
    val systemInstruction: String,
    val contextBlock: String,
    val photos: List<PromptPhoto>,
    val question: String,
)

interface LlmEngine {
    val modelName: String
    /** Human readable backend, for the status indicator. */
    val backendLabel: String
    val supportsVision: Boolean

    /** Exact token count where the engine can provide it, otherwise a conservative estimate. */
    fun countTokens(text: String): Int

    /** Streams partial text through [onPartial] and returns the full answer. Cancel by cancelling the coroutine. */
    suspend fun generate(prompt: RagPrompt, onPartial: (String) -> Unit): String

    fun close()

    companion object {
        /** Whole context window shared by prompt, photos and the answer. */
        const val CONTEXT_TOKENS = 4096
        const val MAX_IMAGES = 2
        const val TOKENS_PER_IMAGE = 256
        const val RESERVED_FOR_ANSWER = 700
    }
}
