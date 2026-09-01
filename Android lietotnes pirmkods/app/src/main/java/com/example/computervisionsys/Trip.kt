package com.example.ckns

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TripPoint(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float? = null
)
data class TripObjectHit(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val label: Int
)
data class TripMeta(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val distanceMeters: Double = 0.0,
    val pointsCount: Int = 0,
    val objectCount: Int = 0
)

fun TripMeta.prettyTitle(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(startedAt))
}

object TripState {
    @Volatile
    var isRecording: Boolean = false

    @Volatile
    var currentTripId: Long? = null

    @Volatile
    var lastPoint: TripPoint? = null
}
object TripApi {
    fun startRecording(context: Context) {
        val i = Intent(context, TripRecordingService::class.java)
            .apply { action = TripRecordingService.Companion.ACTION_START }
        ContextCompat.startForegroundService(context, i)
    }
    fun stopRecording(context: Context) {
        val i = Intent(context, TripRecordingService::class.java)
            .apply { action = TripRecordingService.Companion.ACTION_STOP }
        ContextCompat.startForegroundService(context, i)
    }
}
