package com.kiwixrag.app.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.kiwixrag.app.rag.ImageDecoder
import com.kiwixrag.app.storage.Accelerator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LiteRT-LM engine for .litertlm bundles (Gemma 3n and Gemma 4 style). Supports GPU and, on devices
 * with a supported vendor runtime, NPU. Chat templating is done by the model bundle itself.
 */
class LiteRtLmEngine private constructor(
    override val modelName: String,
    override val backendLabel: String,
    override val supportsVision: Boolean,
    private val engine: Engine,
) : LlmEngine {

    // LiteRT-LM exposes no standalone tokenizer call, so use a conservative estimate (safe side).
    override fun countTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length / 2.8).toInt() + 1

    override suspend fun generate(prompt: RagPrompt, onPartial: (String) -> Unit): String {
        val conversation: Conversation = engine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(prompt.systemInstruction)),
        )
        try {
            val parts = mutableListOf<Content>()
            parts += Content.Text(prompt.contextBlock)
            if (supportsVision) {
                for (photo in prompt.photos) {
                    parts += Content.Text("[${photo.label}] ${photo.caption}")
                    parts += Content.ImageBytes(ImageDecoder.toJpeg(photo.bitmap))
                }
            }
            parts += Content.Text("Question: ${prompt.question}")

            val text = StringBuilder()
            return suspendCancellableCoroutine { cont ->
                conversation.sendMessageAsync(
                    Contents.of(parts),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            text.append(textOf(message))
                            onPartial(text.toString())
                        }

                        override fun onDone() {
                            if (cont.isActive) cont.resume(text.toString())
                        }

                        override fun onError(throwable: Throwable) {
                            if (cont.isActive) cont.resumeWithException(throwable)
                        }
                    },
                )
                cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
            }
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun textOf(message: Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        /** Heavy: call off the main thread. */
        fun load(context: Context, modelPath: String, displayName: String, accelerator: Accelerator): LiteRtLmEngine {
            val order = when (accelerator) {
                Accelerator.NPU -> listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
                Accelerator.GPU -> listOf(Accelerator.GPU, Accelerator.CPU)
                Accelerator.CPU -> listOf(Accelerator.CPU)
            }
            var last: Throwable? = null
            for (backend in order) {
                for (vision in listOf(true, false)) {
                    try {
                        val main = backendFor(context, backend)
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = main,
                            visionBackend = if (vision) (if (backend == Accelerator.CPU) Backend.CPU() else Backend.GPU()) else null,
                            maxNumTokens = LlmEngine.CONTEXT_TOKENS,
                            maxNumImages = if (vision) LlmEngine.MAX_IMAGES else null,
                            cacheDir = context.cacheDir.absolutePath,
                        )
                        val engine = Engine(config)
                        engine.initialize()
                        val label = "LiteRT-LM ${backend.name}" + if (backend != accelerator) " (fallback)" else ""
                        return LiteRtLmEngine(displayName, label, vision, engine)
                    } catch (e: Throwable) {
                        last = e
                    }
                }
            }
            throw IllegalStateException(last?.message ?: "LiteRT-LM could not load the model", last)
        }

        private fun backendFor(context: Context, a: Accelerator): Backend = when (a) {
            Accelerator.GPU -> Backend.GPU()
            Accelerator.CPU -> Backend.CPU()
            Accelerator.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        }
    }
}
