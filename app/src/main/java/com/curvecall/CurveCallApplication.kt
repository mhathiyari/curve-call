package com.curvecall

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

/**
 * Application class annotated with @HiltAndroidApp to enable Hilt dependency injection.
 * This triggers Hilt's code generation and serves as the application-level DI container.
 *
 * Also initializes osmdroid configuration for OSM map tiles.
 */
@HiltAndroidApp
class CurveCallApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Configure osmdroid: user agent (required by OSM tile policy) and cache path
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = "CurveCue/1.0"
        osmConfig.osmdroidTileCache = cacheDir.resolve("osmdroid")
        osmConfig.tileDownloadThreads = 2
    }
}
