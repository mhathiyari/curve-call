package com.curvecall.data.regions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Repository for managing offline region data (routing graphs).
 *
 * Handles:
 * - Loading the bundled region manifest (regions.json)
 * - Downloading .ghz (graph archive) files
 * - Extracting archives to the correct filesystem locations
 * - Tracking downloaded region metadata
 * - Deleting region data
 *
 * Map tiles are handled online via CartoDB/OSM tile servers + corridor caching.
 *
 * File layout:
 * - Graphs: {filesDir}/graphhopper-graphs/{regionId}/
 * - Metadata: {filesDir}/downloaded-regions.json
 */
class RegionRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val graphsBaseDir: File
        get() = File(context.filesDir, GRAPHS_DIR_NAME)

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE_NAME)

    // -- Public API --

    /**
     * Load the list of available regions from the bundled asset manifest.
     *
     * The region catalog is embedded in assets/regions.json so it's always
     * available without network access. Update the asset file when adding
     * new regions.
     *
     * @return list of regions available for download
     */
    suspend fun fetchAvailableRegions(): List<Region> = withContext(Dispatchers.IO) {
        val json = context.assets.open("regions.json").bufferedReader().use { it.readText() }
        parseRegionsJson(json)
    }

    /**
     * Scan the filesystem for downloaded regions and return their metadata.
     */
    fun getDownloadedRegions(): List<DownloadedRegion> {
        return readMetadata()
    }

    /**
     * Download a region's routing graph and emit progress updates.
     *
     * Downloads the .ghz archive and extracts it to the graph directory.
     * Map tiles are handled online — no tile download needed.
     *
     * @param region the region to download (must come from fetchAvailableRegions)
     * @return flow of download progress updates
     */
    fun downloadRegion(region: Region): Flow<DownloadProgress> = flow {
        val regionId = region.id

        try {
            emit(DownloadProgress.Downloading(regionId, 0L, 0L, "Downloading routing data..."))

            val graphTempFile = File(context.cacheDir, "${regionId}.ghz")
            try {
                downloadFile(
                    url = region.graphUrl,
                    destination = graphTempFile,
                    onProgress = { downloaded, total ->
                        emit(
                            DownloadProgress.Downloading(
                                regionId = regionId,
                                bytesDownloaded = downloaded,
                                totalBytes = total,
                                phase = "Downloading routing data..."
                            )
                        )
                    }
                )

                emit(DownloadProgress.Extracting(regionId))
                val graphDir = File(graphsBaseDir, regionId)
                extractZip(graphTempFile, graphDir)
            } finally {
                graphTempFile.delete()
            }

            val downloaded = DownloadedRegion(
                id = regionId,
                name = region.name,
                graphSizeMb = region.graphSizeMb,
                downloadedAt = System.currentTimeMillis(),
                boundingBox = region.boundingBox
            )
            addToMetadata(downloaded)

            emit(DownloadProgress.Completed(regionId))

        } catch (e: Exception) {
            File(graphsBaseDir, regionId).deleteRecursively()
            emit(DownloadProgress.Failed(regionId, e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Delete all data for a downloaded region (graph directory + metadata).
     */
    suspend fun deleteRegion(regionId: String) = withContext(Dispatchers.IO) {
        File(graphsBaseDir, regionId).deleteRecursively()
        removeFromMetadata(regionId)
    }

    /**
     * Calculate total storage used by all downloaded graphs in bytes.
     */
    fun getStorageUsed(): Long {
        if (!graphsBaseDir.exists()) return 0L
        return graphsBaseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Check if a region has been downloaded.
     */
    fun isRegionDownloaded(regionId: String): Boolean {
        return readMetadata().any { it.id == regionId }
    }

    /**
     * Get the path to the unzipped graph directory for a region.
     * This is the path to pass to GraphHopperRouter.loadGraph().
     */
    fun getGraphDir(regionId: String): File {
        return File(graphsBaseDir, regionId)
    }

    /**
     * Find the first downloaded region whose bounding box contains the given coordinate.
     * Returns null if no downloaded region covers the point.
     */
    fun findRegionForCoordinate(lat: Double, lon: Double): DownloadedRegion? {
        return readMetadata().firstOrNull { region ->
            region.boundingBox?.let { bbox ->
                lat in bbox.south..bbox.north && lon in bbox.west..bbox.east
            } ?: false
        }
    }

    // -- Private helpers --

    /**
     * Download a file from URL to the given destination, reporting progress.
     */
    private fun downloadFile(
        url: String,
        destination: File,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ) {
        destination.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val totalBytes = body.contentLength()
        var bytesDownloaded = 0L
        var lastProgressReport = 0L

        body.byteStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesDownloaded += read

                    // Report progress at most every PROGRESS_INTERVAL_BYTES
                    if (bytesDownloaded - lastProgressReport >= PROGRESS_INTERVAL_BYTES) {
                        kotlinx.coroutines.runBlocking {
                            onProgress(bytesDownloaded, totalBytes)
                        }
                        lastProgressReport = bytesDownloaded
                    }
                }
            }
        }
    }

    /**
     * Extract a zip file to the given directory.
     */
    private fun extractZip(zipFile: File, destDir: File) {
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)

                // Prevent zip slip attacks
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Parse the regions.json manifest into a list of Region objects.
     */
    private fun parseRegionsJson(json: String): List<Region> {
        val root = JSONObject(json)
        val regionsArray = root.getJSONArray("regions")
        val regions = mutableListOf<Region>()

        for (i in 0 until regionsArray.length()) {
            val obj = regionsArray.getJSONObject(i)
            val bbox = obj.getJSONObject("boundingBox")

            regions.add(
                Region(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    graphUrl = obj.getString("graphUrl"),
                    graphSizeMb = obj.getInt("graphSizeMb"),
                    boundingBox = BoundingBox(
                        north = bbox.getDouble("north"),
                        south = bbox.getDouble("south"),
                        east = bbox.getDouble("east"),
                        west = bbox.getDouble("west")
                    ),
                    lastUpdated = obj.optString("lastUpdated", "")
                )
            )
        }

        return regions
    }

    /**
     * Read download metadata from the JSON file.
     */
    private fun readMetadata(): List<DownloadedRegion> {
        if (!metadataFile.exists()) return emptyList()

        return try {
            val json = metadataFile.readText()
            val root = JSONObject(json)
            val array = root.getJSONArray("downloaded")
            val result = mutableListOf<DownloadedRegion>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val bboxObj = obj.optJSONObject("boundingBox")
                result.add(
                    DownloadedRegion(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        graphSizeMb = obj.getInt("graphSizeMb"),
                        downloadedAt = obj.getLong("downloadedAt"),
                        boundingBox = bboxObj?.let {
                            BoundingBox(
                                north = it.getDouble("north"),
                                south = it.getDouble("south"),
                                east = it.getDouble("east"),
                                west = it.getDouble("west")
                            )
                        }
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Add a downloaded region to the metadata file.
     */
    private fun addToMetadata(region: DownloadedRegion) {
        val existing = readMetadata().toMutableList()
        existing.removeAll { it.id == region.id }
        existing.add(region)
        writeMetadata(existing)
    }

    /**
     * Remove a region from the metadata file.
     */
    private fun removeFromMetadata(regionId: String) {
        val existing = readMetadata().toMutableList()
        existing.removeAll { it.id == regionId }
        writeMetadata(existing)
    }

    /**
     * Write the full metadata list to the JSON file.
     */
    private fun writeMetadata(regions: List<DownloadedRegion>) {
        val root = JSONObject()
        val array = org.json.JSONArray()

        for (region in regions) {
            val obj = JSONObject().apply {
                put("id", region.id)
                put("name", region.name)
                put("graphSizeMb", region.graphSizeMb)
                put("downloadedAt", region.downloadedAt)
                region.boundingBox?.let { bbox ->
                    put("boundingBox", JSONObject().apply {
                        put("north", bbox.north)
                        put("south", bbox.south)
                        put("east", bbox.east)
                        put("west", bbox.west)
                    })
                }
            }
            array.put(obj)
        }

        root.put("downloaded", array)
        metadataFile.writeText(root.toString(2))
    }

    companion object {
        private const val GRAPHS_DIR_NAME = "graphhopper-graphs"
        private const val METADATA_FILE_NAME = "downloaded-regions.json"
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_INTERVAL_BYTES = 65536L // report every 64KB
    }
}
