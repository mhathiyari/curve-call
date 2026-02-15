package com.curvecall.data.tiles

/**
 * Represents the current state of the tile download process.
 */
sealed class TileDownloadState {
    /** No download in progress. */
    data object Idle : TileDownloadState()

    /** Calculating which tiles are needed for the route corridor. */
    data object Calculating : TileDownloadState()

    /** Actively downloading tiles. */
    data class Downloading(
        val downloadedCount: Int,
        val totalCount: Int
    ) : TileDownloadState() {
        val progressPercent: Int
            get() = if (totalCount > 0) (downloadedCount * 100 / totalCount) else 0
    }

    /** All tiles downloaded successfully. */
    data class Complete(val tileCount: Int) : TileDownloadState()

    /** Download failed with an error (non-blocking — session can still start). */
    data class Error(val message: String) : TileDownloadState()
}
