package com.murtdah.fit

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import com.murtdah.fit.data.Repository
import com.murtdah.fit.data.StepReader
import com.murtdah.fit.media.MediaCatalog
import com.murtdah.fit.work.Notifications
import com.murtdah.fit.work.Scheduler

class FitApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    lateinit var repo: Repository
        private set
    lateinit var steps: StepReader
        private set
    lateinit var media: MediaCatalog
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository.create(this)
        steps = StepReader(this, repo)
        media = MediaCatalog(this)
        Notifications.createChannels(this)
        Scheduler.ensureBackgroundWork(this)
        Scheduler.planToday(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
}

val Context.app: FitApp get() = applicationContext as FitApp
