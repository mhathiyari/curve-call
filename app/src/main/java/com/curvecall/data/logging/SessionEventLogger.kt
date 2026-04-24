package com.curvecall.data.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.curvecall.narration.types.NarrationEvent
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a timestamped CSV event log for each driving session.
 *
 * One CSV file is created per session in the app's external files dir under
 * "session_logs/". Each row records a single event (narration fired, off-route,
 * session start/stop, etc.) with GPS coordinates and speed at that moment.
 *
 * This is the primary post-session debugging tool: open in any spreadsheet app,
 * correlate with GPX replay or dashcam footage by ISO timestamp.
 *
 * CSV columns:
 *   timestamp, event, lat, lon, speed_kmh, text, severity, direction,
 *   advisory_kmh, curve_dist_m, priority, extra
 */
class SessionEventLogger(private val context: Context) {

    private var writer: FileWriter? = null
    private var currentLogFile: File? = null

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val logDir: File
        get() {
            val dir = context.getExternalFilesDir("session_logs")
                ?: File(context.filesDir, "session_logs")
            dir.mkdirs()
            return dir
        }

    // -- Session lifecycle --

    fun startSession(curveCount: Int, totalDistanceM: Double) {
        val file = File(logDir, "session_${fileFmt.format(Date())}.csv")
        currentLogFile = file
        writer = FileWriter(file, false)
        writeLine("timestamp,event,lat,lon,speed_kmh,text,severity,direction,advisory_kmh,curve_dist_m,priority,extra")
        writeRow("SESSION_START", extra = "curves=$curveCount dist=${totalDistanceM.toInt()}m")
    }

    fun endSession() {
        writeRow("SESSION_STOP")
        writer?.flush()
        writer?.close()
        writer = null
    }

    fun logPause() = writeRow("SESSION_PAUSE")
    fun logResume() = writeRow("SESSION_RESUME")

    // -- Narration events --

    fun logNarration(lat: Double, lon: Double, speedKmh: Double, event: NarrationEvent) =
        writeNarrationRow("NARRATION", lat, lon, speedKmh, event)

    fun logNarrationInterrupt(lat: Double, lon: Double, speedKmh: Double, event: NarrationEvent) =
        writeNarrationRow("NARRATION_INTERRUPT", lat, lon, speedKmh, event)

    fun logUrgentAlert(lat: Double, lon: Double, speedKmh: Double, event: NarrationEvent) =
        writeNarrationRow("NARRATION_URGENT", lat, lon, speedKmh, event)

    // -- Location events --

    fun logOffRoute(lat: Double, lon: Double, distM: Double) =
        writeRow("OFF_ROUTE", lat = lat, lon = lon, extra = "dist=${distM.toInt()}m")

    fun logBackOnRoute(lat: Double, lon: Double) =
        writeRow("BACK_ON_ROUTE", lat = lat, lon = lon)

    // -- Export --

    fun getLogFiles(): List<File> =
        logDir.listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Returns a share intent for the most recent log file, or null if no logs exist. */
    fun shareLatestLog(): Intent? {
        val file = getLogFiles().firstOrNull() ?: return null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // -- Internal --

    private fun writeNarrationRow(kind: String, lat: Double, lon: Double, speedKmh: Double, event: NarrationEvent) {
        val c = event.associatedCurve
        writeRow(
            kind,
            lat = lat, lon = lon, speedKmh = speedKmh,
            text = event.text,
            severity = c?.severity?.name,
            direction = c?.direction?.name,
            advisoryKmh = event.advisorySpeedMs?.times(3.6),
            curveDistM = event.curveDistanceFromStart,
            priority = event.priority
        )
    }

    private fun writeRow(
        event: String,
        lat: Double? = null,
        lon: Double? = null,
        speedKmh: Double? = null,
        text: String? = null,
        severity: String? = null,
        direction: String? = null,
        advisoryKmh: Double? = null,
        curveDistM: Double? = null,
        priority: Int? = null,
        extra: String? = null
    ) {
        val ts = isoFmt.format(Date())
        val row = listOf(
            ts,
            event,
            lat?.let { "%.6f".format(it) } ?: "",
            lon?.let { "%.6f".format(it) } ?: "",
            speedKmh?.let { "%.1f".format(it) } ?: "",
            (text ?: "").replace(",", ";"),
            severity ?: "",
            direction ?: "",
            advisoryKmh?.let { "%.1f".format(it) } ?: "",
            curveDistM?.let { "%.0f".format(it) } ?: "",
            priority?.toString() ?: "",
            extra ?: ""
        ).joinToString(",")
        writeLine(row)
    }

    private fun writeLine(line: String) {
        try {
            writer?.write("$line\n")
            writer?.flush()
        } catch (_: Exception) {
            // Never crash the app due to logging failure
        }
    }
}
