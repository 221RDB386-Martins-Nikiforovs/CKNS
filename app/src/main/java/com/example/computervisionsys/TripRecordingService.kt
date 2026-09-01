package com.example.ckns

import android.R
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TripRecordingService : Service() {
    private var recorder: TripRecorder? = null
    private var tripId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        val id = TripStorage.createNewTrip(this)
        tripId = id

        TripState.isRecording = true
        TripState.currentTripId = id.toLongOrNull()

        recorder = TripRecorder(this, id).also { it.start() }
        startForeground(NOTIF_ID, buildNotification("ieraksta"))
    }

    private fun stopRecording() {
        recorder?.stop()
        recorder = null
        tripId?.let { TripStorage.finishTrip(this, it) }

        TripState.isRecording = false
        TripState.currentTripId = null
        tripId = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, TripRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("brauciena ieraksts")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_menu_mylocation)
            .setContentIntent(contentPi)
            .addAction(
                R.drawable.ic_media_pause,
                "beigt",
                stopPi
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "brauciena ieraksts",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun immutableFlag(): Int =
        PendingIntent.FLAG_IMMUTABLE
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP  = "stop"
        private const val CHANNEL_ID = "trip_rec_channel"
        private const val NOTIF_ID   = 42
    }
}

class TripRecorder(private val ctx: Context, private val tripId: String) : LocationListener {

    private var started = false
    private var lm: LocationManager? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this) } catch (_: Throwable) {}
        try { lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, this) } catch (_: Throwable) {}
    }
    fun stop() {
        if (!started) return
        started = false
        lm?.removeUpdates(this)
        lm = null
    }

    override fun onLocationChanged(location: Location) {
        val p = TripPoint(
            ts = System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed else null
        )
        TripStorage.appendPoint(ctx, tripId, p)
        TripState.lastPoint = p
    }
}
