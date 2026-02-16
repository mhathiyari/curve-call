package com.curvecall.di

import android.content.Context
import com.curvecall.audio.AndroidTtsEngine
import com.curvecall.data.geocoding.GeocodingService
import com.curvecall.data.location.LocationProvider
import com.curvecall.data.preferences.UserPreferences
import com.curvecall.data.regions.RegionRepository
import com.curvecall.data.routing.GraphHopperRouter
import com.curvecall.data.routing.OnlineRouter
import com.curvecall.data.routing.RoutePipeline
import com.curvecall.data.session.SessionDataHolder
import com.curvecall.data.tiles.TileDownloader
import com.curvecall.engine.RouteAnalyzer
import com.curvecall.narration.NarrationManager
import com.curvecall.narration.TemplateEngine
import com.curvecall.narration.TimingCalculator
import com.curvecall.narration.TtsDurationCalibrator
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
 * - Data layer (LocationProvider, UserPreferences)
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
    fun provideSessionDataHolder(): SessionDataHolder {
        return SessionDataHolder()
    }

    @Provides
    @Singleton
    fun provideTileDownloader(
        okHttpClient: OkHttpClient
    ): TileDownloader {
        return TileDownloader(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideGraphHopperRouter(
        @ApplicationContext context: Context
    ): GraphHopperRouter {
        return GraphHopperRouter(context)
    }

    @Provides
    @Singleton
    fun provideRegionRepository(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): RegionRepository {
        return RegionRepository(context, okHttpClient)
    }

    @Provides
    @Singleton
    fun provideGeocodingService(
        okHttpClient: OkHttpClient
    ): GeocodingService {
        return GeocodingService(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideOnlineRouter(
        okHttpClient: OkHttpClient
    ): OnlineRouter {
        return OnlineRouter(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideRoutePipeline(
        graphHopperRouter: GraphHopperRouter,
        onlineRouter: OnlineRouter,
        routeAnalyzer: RouteAnalyzer,
        userPreferences: UserPreferences,
        sessionDataHolder: SessionDataHolder,
        tileDownloader: TileDownloader,
        regionRepository: RegionRepository
    ): RoutePipeline {
        return RoutePipeline(
            graphHopperRouter,
            onlineRouter,
            routeAnalyzer,
            userPreferences,
            sessionDataHolder,
            tileDownloader,
            regionRepository
        )
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
    fun provideTtsDurationCalibrator(): TtsDurationCalibrator {
        return TtsDurationCalibrator()
    }

    @Provides
    @Singleton
    fun provideTimingCalculator(
        calibrator: TtsDurationCalibrator
    ): TimingCalculator {
        return TimingCalculator(calibrator)
    }

    @Provides
    @Singleton
    fun provideTtsEngine(
        @ApplicationContext context: Context,
        calibrator: TtsDurationCalibrator
    ): TtsEngine {
        return AndroidTtsEngine(context, calibrator)
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
