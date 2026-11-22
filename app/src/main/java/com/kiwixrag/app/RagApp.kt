package com.kiwixrag.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.kiwixrag.app.storage.Prefs
import com.kiwixrag.app.zim.LibzimLibrary

class RagApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var library: LibzimLibrary
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        library = LibzimLibrary(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) library.trimCaches()
    }
}

val Context.ragApp: RagApp get() = applicationContext as RagApp
