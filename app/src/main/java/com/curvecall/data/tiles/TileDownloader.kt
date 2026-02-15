package com.curvecall.data.tiles

import com.curvecall.engine.types.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates bulk tile download for offline route display.
 *
 * Downloads tiles along a route corridor using OkHttpClient and stores them
 * in osmdroid's SqlTileWriter so they're immediately available to MapView.
 *
 * Download strategy:
 * - Filters out already-cached tiles
 * - Downloads with 2 concurrent connections (OSM policy)
 * - Rate limits: 100ms between batches
 * - Non-blocking: if download fails, session still starts
 */
@Singleton
class TileDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val _state = MutableStateFlow<TileDownloadState>(TileDownloadState.Idle)
    val state: StateFlow<TileDownloadState> = _state.asStateFlow()

    /**
     * Download tiles for a route corridor. Non-blocking — errors are reported
     * via [state] but don't prevent session start.
     */
    suspend fun downloadForRoute(routePoints: List<LatLon>) {
        try {
            _state.value = TileDownloadState.Calculating

            val allTiles = withContext(Dispatchers.Default) {
                CorridorTileCalculator.calculateTiles(routePoints)
            }

            if (allTiles.isEmpty()) {
                _state.value = TileDownloadState.Complete(0)
                return
            }

            // Filter out already-cached tiles
            val tileSource = TileSourceFactory.MAPNIK
            val tileWriter = SqlTileWriter()
            val tilesToDownload = try {
                allTiles.filter { tile ->
                    val mapTileIndex = MapTileIndex.getTileIndex(tile.zoom, tile.x, tile.y)
                    tileWriter.getExpirationTimestamp(tileSource, mapTileIndex) <= 0
                }
            } catch (e: Exception) {
                // If we can't check cache, download all
                allTiles.toList()
            }

            if (tilesToDownload.isEmpty()) {
                _state.value = TileDownloadState.Complete(allTiles.size)
                tileWriter.onDetach()
                return
            }

            _state.value = TileDownloadState.Downloading(0, tilesToDownload.size)

            var downloaded = 0
            val batchSize = 2 // OSM policy: max 2 concurrent connections

            withContext(Dispatchers.IO) {
                tilesToDownload.chunked(batchSize).forEach { batch ->
                    for (tile in batch) {
                        try {
                            val url = tile.toUrl()
                            val request = Request.Builder()
                                .url(url)
                                .header("User-Agent", "CurveCall/1.0")
                                .build()

                            val response = okHttpClient.newCall(request).execute()
                            if (response.isSuccessful) {
                                response.body?.bytes()?.let { bytes ->
                                    val mapTileIndex = MapTileIndex.getTileIndex(
                                        tile.zoom, tile.x, tile.y
                                    )
                                    val inputStream = ByteArrayInputStream(bytes)
                                    tileWriter.saveFile(
                                        tileSource,
                                        mapTileIndex,
                                        inputStream,
                                        null
                                    )
                                }
                            }
                            response.close()
                        } catch (e: Exception) {
                            // Skip failed tiles — they'll load on-demand later
                        }
                        downloaded++
                        _state.value = TileDownloadState.Downloading(downloaded, tilesToDownload.size)
                    }

                    // Rate limit between batches (OSM policy compliance)
                    delay(100)
                }
            }

            tileWriter.onDetach()
            _state.value = TileDownloadState.Complete(downloaded)

        } catch (e: Exception) {
            _state.value = TileDownloadState.Error(e.message ?: "Tile download failed")
        }
    }

    /**
     * Reset download state back to idle.
     */
    fun reset() {
        _state.value = TileDownloadState.Idle
    }
}
