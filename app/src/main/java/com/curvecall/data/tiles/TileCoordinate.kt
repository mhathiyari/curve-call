package com.curvecall.data.tiles

/**
 * Represents a single map tile by its zoom level, column (x), and row (y)
 * in the standard OSM slippy map tile numbering scheme.
 */
data class TileCoordinate(
    val zoom: Int,
    val x: Int,
    val y: Int
) {
    /**
     * Build the standard OSM tile URL for this coordinate.
     */
    fun toUrl(baseUrl: String = "https://tile.openstreetmap.org"): String {
        return "$baseUrl/$zoom/$x/$y.png"
    }
}
