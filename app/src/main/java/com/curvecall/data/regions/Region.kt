package com.curvecall.data.regions

/**
 * Represents a region available for download from the manifest.
 * Each region corresponds to a US state and contains routing graph
 * and map tile data for offline use.
 */
data class Region(
    val id: String,
    val name: String,
    val graphUrl: String,
    val graphSizeMb: Int,
    val boundingBox: BoundingBox,
    val lastUpdated: String
)

/**
 * Geographic bounding box for a region.
 */
data class BoundingBox(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
)

/**
 * Metadata for a region that has been downloaded to the device.
 */
data class DownloadedRegion(
    val id: String,
    val name: String,
    val graphSizeMb: Int,
    val downloadedAt: Long, // epoch millis
    val boundingBox: BoundingBox? = null
)

/**
 * Sealed class representing the current state of a region download.
 * Emitted as a Flow during the download process.
 */
sealed class DownloadProgress {
    object Idle : DownloadProgress()

    data class Downloading(
        val regionId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val phase: String
    ) : DownloadProgress()

    data class Extracting(val regionId: String) : DownloadProgress()

    data class Completed(val regionId: String) : DownloadProgress()

    data class Failed(val regionId: String, val error: String) : DownloadProgress()
}
