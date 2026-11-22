package com.kiwixrag.app.llm

import android.content.Context
import com.kiwixrag.app.storage.Accelerator

object EngineFactory {
    /** `.litertlm` goes to LiteRT-LM. `.task` and `.bin` go to MediaPipe. Unknown extensions try MediaPipe first. */
    fun load(context: Context, modelPath: String, displayName: String, accelerator: Accelerator): LlmEngine {
        val name = displayName.lowercase()
        return when {
            name.endsWith(".litertlm") -> LiteRtLmEngine.load(context, modelPath, displayName, accelerator)
            name.endsWith(".task") || name.endsWith(".bin") -> MediaPipeEngine.load(context, modelPath, displayName, accelerator)
            else -> runCatching { MediaPipeEngine.load(context, modelPath, displayName, accelerator) }
                .getOrElse { LiteRtLmEngine.load(context, modelPath, displayName, accelerator) }
        }
    }
}
