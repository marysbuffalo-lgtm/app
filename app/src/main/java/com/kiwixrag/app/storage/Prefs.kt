package com.kiwixrag.app.storage

import android.content.Context

enum class Accelerator(val label: String) {
    GPU("GPU"),
    NPU("NPU (LiteRT-LM only)"),
    CPU("CPU"),
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kiwix_rag", Context.MODE_PRIVATE)

    var modelUri: String?
        get() = sp.getString("model_uri", null)
        set(v) { sp.edit().putString("model_uri", v).apply() }

    var zimTreeUri: String?
        get() = sp.getString("zim_tree_uri", null)
        set(v) { sp.edit().putString("zim_tree_uri", v).apply() }

    var accelerator: Accelerator
        get() = runCatching { Accelerator.valueOf(sp.getString("accel", Accelerator.GPU.name)!!) }.getOrDefault(Accelerator.GPU)
        set(v) { sp.edit().putString("accel", v.name).apply() }

    var topArticles: Int
        get() = sp.getInt("top_articles", 4).coerceIn(3, 5)
        set(v) { sp.edit().putInt("top_articles", v.coerceIn(3, 5)).apply() }
}
