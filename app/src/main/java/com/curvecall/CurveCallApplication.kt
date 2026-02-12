package com.curvecall

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class annotated with @HiltAndroidApp to enable Hilt dependency injection.
 * This triggers Hilt's code generation and serves as the application-level DI container.
 */
@HiltAndroidApp
class CurveCallApplication : Application()
