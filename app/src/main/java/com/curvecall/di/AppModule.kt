package com.curvecall.di

import android.content.Context
import com.curvecall.audio.AndroidTtsEngine
import com.curvecall.data.gpx.GpxParser
import com.curvecall.data.location.LocationProvider
import com.curvecall.data.osm.OverpassClient
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.session.SessionDataHolder
import com.curvecall.engine.RouteAnalyzer
import com.curvecall.narration.NarrationManager
import com.curvecall.narration.TemplateEngine
import com.curvecall.narration.TimingCalculator
import com.curvecall.narration.TtsEngine
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing application-wide singletons.
 *
 * Provides:
 * - Engine components (RouteAnalyzer)
 * - Narration components (TemplateEngine, TimingCalculator, NarrationManager, TtsEngine)
 * - Data layer (LocationProvider, UserPreferences, GpxParser, OverpassClient)
 * - Android services (FusedLocationProviderClient, OkHttpClient)
 *
 * Note: MapMatcher is NOT provided here because it requires route-specific data
 * (route points and segments) that are only available at runtime after route analysis.
 * It is created locally in SessionViewModel after route data is loaded.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // -- Android Services --

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // -- Data Layer --

    @Provides
    @Singleton
    fun provideUserPreferences(
        @ApplicationContext context: Context
    ): UserPreferences {
        return UserPreferences(context)
    }

    @Provides
    @Singleton
    fun provideLocationProvider(
        fusedLocationProviderClient: FusedLocationProviderClient
    ): LocationProvider {
        return LocationProvider(fusedLocationProviderClient)
    }

    @Provides
    @Singleton
    fun provideGpxParser(): GpxParser {
        return GpxParser()
    }

    @Provides
    @Singleton
    fun provideOverpassClient(
        okHttpClient: OkHttpClient
    ): OverpassClient {
        return OverpassClient(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideSessionDataHolder(): SessionDataHolder {
        return SessionDataHolder()
    }

    // -- Engine --

    @Provides
    @Singleton
    fun provideRouteAnalyzer(): RouteAnalyzer {
        return RouteAnalyzer()
    }

    // -- Narration --

    @Provides
    @Singleton
    fun provideTemplateEngine(): TemplateEngine {
        return TemplateEngine()
    }

    @Provides
    @Singleton
    fun provideTimingCalculator(): TimingCalculator {
        return TimingCalculator()
    }

    @Provides
    @Singleton
    fun provideTtsEngine(
        @ApplicationContext context: Context
    ): TtsEngine {
        return AndroidTtsEngine(context)
    }

    @Provides
    @Singleton
    fun provideNarrationManager(
        templateEngine: TemplateEngine,
        timingCalculator: TimingCalculator
    ): NarrationManager {
        return NarrationManager(templateEngine, timingCalculator)
    }
}
