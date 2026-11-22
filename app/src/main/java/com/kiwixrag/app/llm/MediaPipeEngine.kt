package com.kiwixrag.app.llm

import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.kiwixrag.app.storage.Accelerator
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MediaPipe LLM Inference (.task bundles such as Gemma 3n). The model is memory mapped from [modelPath]
 * and never copied. GPU is used when requested, with an automatic CPU fallback.
 */
class MediaPipeEngine private constructor(
    override val modelName: String,
    override val backendLabel: String,
    override val supportsVision: Boolean,
    private val llm: LlmInference,
) : LlmEngine {

    override fun countTokens(text: String): Int = if (text.isEmpty()) 0 else runCatching { llm.sizeInTokens(text) }.getOrElse { estimate(text) }

    override suspend fun generate(prompt: RagPrompt, onPartial: (String) -> Unit): String {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.9f)
            .setTemperature(0.3f)
            .apply {
                if (supportsVision) setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            }
            .build()
        val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
        try {
            // Gemma turn format. Gemma has no system role, so the instruction leads the user turn.
            session.addQueryChunk("<start_of_turn>user\n${prompt.systemInstruction}\n\n${prompt.contextBlock}\n")
            if (supportsVision) {
                for (photo in prompt.photos) {
                    session.addQueryChunk("\n[${photo.label}] ${photo.caption}\n")
                    session.addImage(BitmapImageBuilder(photo.bitmap).build())
                }
            }
            session.addQueryChunk("\nQuestion: ${prompt.question}<end_of_turn>\n<start_of_turn>model\n")

            val partials = StringBuilder()
            return suspendCancellableCoroutine { cont ->
                val future = session.generateResponseAsync(ProgressListener<String> { partial, _ ->
                    partials.append(partial)
                    onPartial(partials.toString())
                })
                future.addListener({
                    try {
                        val full = future.get()
                        cont.resume(if (full.isNullOrEmpty()) partials.toString() else full)
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, Executor { it.run() })
                cont.invokeOnCancellation { runCatching { session.cancelGenerateResponseAsync() } }
            }
        } finally {
            runCatching { session.close() }
        }
    }

    override fun close() {
        runCatching { llm.close() }
    }

    companion object {
        private fun estimate(text: String) = (text.length / 3.0).toInt() + 1

        /** A text-only model can accept image slots at load time and only fail when a session enables vision. */
        private fun visionWorks(llm: LlmInference): Boolean = try {
            LlmInferenceSession.createFromOptions(
                llm,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                    .build(),
            ).close()
            true
        } catch (e: Throwable) {
            false
        }

        /** Heavy: call off the main thread. */
        fun load(context: Context, modelPath: String, displayName: String, accelerator: Accelerator): MediaPipeEngine {
            val wanted = if (accelerator == Accelerator.CPU) LlmInference.Backend.CPU else LlmInference.Backend.GPU
            val attempts = buildList {
                add(wanted to true)
                add(wanted to false)
                if (wanted == LlmInference.Backend.GPU) {
                    add(LlmInference.Backend.CPU to true)
                    add(LlmInference.Backend.CPU to false)
                }
            }
            var last: Throwable? = null
            for ((backend, vision) in attempts) {
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(LlmEngine.CONTEXT_TOKENS)
                        .setMaxTopK(40)
                        .setPreferredBackend(backend)
                        .apply { if (vision) setMaxNumImages(LlmEngine.MAX_IMAGES) }
                        .build()
                    val llm = LlmInference.createFromOptions(context, options)
                    if (vision && !visionWorks(llm)) {
                        // The model accepted image slots but has no vision encoder: load it as text only.
                        runCatching { llm.close() }
                        continue
                    }
                    val label = buildString {
                        append("MediaPipe ").append(if (backend == LlmInference.Backend.GPU) "GPU" else "CPU")
                        if (accelerator == Accelerator.NPU) append(" (NPU needs LiteRT-LM)")
                        else if (backend != wanted) append(" (fallback)")
                    }
                    return MediaPipeEngine(displayName, label, vision, llm)
                } catch (e: Throwable) {
                    last = e
                }
            }
            throw IllegalStateException(last?.message ?: "MediaPipe could not load the model", last)
        }
    }
}
