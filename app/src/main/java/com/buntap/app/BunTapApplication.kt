package com.buntap.app

import android.app.Application
import com.buntap.app.vision.OpenCvRuntime
import kotlinx.coroutines.launch

class BunTapApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            // Load OpenCV's native library off the main thread so the first scan is fast.
            OpenCvRuntime.ensureLoaded()
            // Leftover full-screen captures from a previous run are never needed again.
            container.images.clearTemp()
        }
    }
}
