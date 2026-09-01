package com.example.ckns

import android.content.Context
import android.location.Location
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.collections.iterator

object TripStorage {
    private const val DIR_TRIPS = "trips"
    private const val FILE_SUMMARY = "summary.json"
    private const val FILE_POINTS = "points.jsonl"

    private fun tripsRoot(ctx: Context) = File(ctx.filesDir, DIR_TRIPS)
    private fun tripDir(ctx: Context, id: String) = File(tripsRoot(ctx), id)
    private fun summaryFile(ctx: Context, id: String) = File(tripDir(ctx, id), FILE_SUMMARY)
    private fun pointsFile(ctx: Context, id: String) = File(tripDir(ctx, id), FILE_POINTS)

    fun createNewTrip(ctx: Context): String {
        val id = System.currentTimeMillis().toString()
        val dir = tripDir(ctx, id)
        dir.mkdirs()

        val summary = JSONObject().apply {
            put("id", id)
            put("startedAt", System.currentTimeMillis())
            put("finishedAt", JSONObject.NULL)
            put("distanceMeters", 0.0)
            put("pointsCount", 0)
            put("objectCount", 0)
            put("lastLatLonUpdatedAt", 0L)
        }
        summaryFile(ctx, id).writeText(summary.toString())
        return id
    }

    fun finishTrip(ctx: Context, id: String) {
        val sFile = summaryFile(ctx, id)
        if (!sFile.exists()) return

        val pts = loadPoints(ctx, id)
        var distMeters = 0f
        var prev: Location? = null
        for (p in pts) {
            val l = Location("").apply {
                latitude = p.lat
                longitude = p.lon
            }
            if (prev != null) distMeters += prev!!.distanceTo(l)
            prev = l
        }

        TripObjectsCounter.flushNowSync(ctx, id)
        val objTotal = TripObjectsCounter.getAll(ctx, id).values.sum()
        TripObjectsCounter.clearTrip(id)

        val obj = try {
            JSONObject(sFile.readText())
        } catch (_: Throwable) {
            JSONObject()
        }

        obj.put("finishedAt", System.currentTimeMillis())
        obj.put("distanceMeters", distMeters.toDouble())
        obj.put("pointsCount", pts.size)
        obj.put("objectCount", objTotal)
        sFile.writeText(obj.toString())
    }

    fun appendPoint(ctx: Context, id: String, p: TripPoint) {
        val sFile = summaryFile(ctx, id)
        if (sFile.exists()) {
            try {
                val obj = JSONObject(sFile.readText())
                obj.put("pointsCount", obj.optInt("pointsCount", 0) + 1)
                obj.put("lastLatLonUpdatedAt", System.currentTimeMillis())
                sFile.writeText(obj.toString())
            } catch (_: Exception) {
            }
        }

        val line = JSONObject().apply {
            put("ts", p.ts)
            put("lat", p.lat)
            put("lon", p.lon)
            if (p.speedMps != null) put("speedMps", p.speedMps)
        }.toString()

        pointsFile(ctx, id).appendText(line + "\n")
    }

    fun listTrips(ctx: Context): List<TripMeta> {
        return tripsRoot(ctx)
            .listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { dir ->
                val s = summaryFile(ctx, dir.name)
                if (!s.exists()) return@mapNotNull null
                try {
                    val obj = JSONObject(s.readText())
                    TripMeta(
                        id = obj.getString("id"),
                        startedAt = obj.optLong("startedAt", 0L),
                        finishedAt = if (obj.isNull("finishedAt")) null else obj.optLong(
                            "finishedAt",
                            0L
                        ),
                        distanceMeters = obj.optDouble("distanceMeters", 0.0),
                        pointsCount = obj.optInt("pointsCount", 0),
                        objectCount = obj.optInt("objectCount", 0)
                    )
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()
    }

    fun loadPoints(ctx: Context, id: String): List<TripPoint> {
        val f = pointsFile(ctx, id)
        if (!f.exists()) return emptyList()

        val res = ArrayList<TripPoint>()
        f.forEachLine { line ->
            try {
                val o = JSONObject(line)
                res += TripPoint(
                    ts = o.optLong("ts"),
                    lat = o.optDouble("lat"),
                    lon = o.optDouble("lon"),
                    speedMps = if (o.has("speedMps")) o.optDouble("speedMps").toFloat() else null
                )
            } catch (_: Exception) {
            }
        }
        return res
    }

    fun findActiveTripId(ctx: Context): String? {
        val root = tripsRoot(ctx)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return null
        var bestStarted = 0L
        var bestId: String? = null

        for (dir in dirs) {
            val s = summaryFile(ctx, dir.name)
            if (!s.exists()) continue
            try {
                val obj = JSONObject(s.readText())
                if (obj.isNull("finishedAt")) {
                    val started = obj.optLong("startedAt", 0L)
                    if (started > bestStarted) {
                        bestStarted = started
                        bestId = obj.getString("id")
                    }
                }
            } catch (_: Exception) {
            }
        }
        return bestId
    }

    fun lastTripId(ctx: Context): String? {
        val root = tripsRoot(ctx)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return null
        var bestStarted = 0L
        var bestId: String? = null

        for (dir in dirs) {
            val s = summaryFile(ctx, dir.name)
            if (!s.exists()) continue
            try {
                val obj = JSONObject(s.readText())
                val started = obj.optLong("startedAt", 0L)
                if (started > bestStarted) {
                    bestStarted = started
                    bestId = obj.getString("id")
                }
            } catch (_: Exception) {
            }
        }
        return bestId
    }

    fun deleteTrip(ctx: Context, id: String): Boolean {
        return try {
            val dir = tripDir(ctx, id)
            if (!dir.exists()) return true
            dir.walkBottomUp().forEach { it.delete() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun suggestedGeoJsonFileName(displayTitle: String): String {
        return sanitizeFileName(displayTitle) + ".geojson"
    }

    fun exportTripGeoJsonToUri(
        ctx: Context,
        trip: TripMeta,
        displayTitle: String,
        classNames: List<String>,
        uri: Uri
    ): Result<Unit> {
        return runCatching {
            TripObjectsCounter.flushNowSync(ctx, trip.id)

            val geoJson = buildGeoJson(ctx, trip, displayTitle, classNames)
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                requireNotNull(writer) { "Neizdevās atvērt izvēlēto failu rakstīšanai." }
                writer.write(geoJson.toString(2))
            }
        }
    }

    private fun buildGeoJson(
        ctx: Context,
        trip: TripMeta,
        displayTitle: String,
        classNames: List<String>
    ): JSONObject {
        val points = loadPoints(ctx, trip.id)
        val hits = TripObjectsCounter.readHits(ctx, trip.id)
        val objectCounts = TripObjectsCounter.getAll(ctx, trip.id)

        val features = JSONArray()

        buildRouteFeature(trip, displayTitle, points, objectCounts, classNames)?.let {
            features.put(it)
        }

        for (hit in hits) {
            features.put(buildHitFeature(trip, displayTitle, hit, classNames))
        }

        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("name", displayTitle)
            put("features", features)
        }
    }

    private fun buildRouteFeature(
        trip: TripMeta,
        displayTitle: String,
        points: List<TripPoint>,
        objectCounts: Map<Int, Int>,
        classNames: List<String>
    ): JSONObject? {
        if (points.isEmpty()) return null

        val geometry = if (points.size >= 2) {
            JSONObject().apply {
                put("type", "LineString")
                put("coordinates", JSONArray().apply {
                    for (p in points) {
                        put(JSONArray().put(p.lon).put(p.lat))
                    }
                })
            }
        } else {
            val p = points.first()
            JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(p.lon).put(p.lat))
            }
        }

        val objectCountsById = JSONObject().apply {
            for ((classId, count) in objectCounts.toSortedMap()) {
                put(classId.toString(), count)
            }
        }

        val objectCountsByName = JSONObject().apply {
            for ((classId, count) in objectCounts.toSortedMap()) {
                val name = classNames.getOrNull(classId) ?: classId.toString()
                put(name, count)
            }
        }

        return JSONObject().apply {
            put("type", "Feature")
            put("geometry", geometry)
            put("properties", JSONObject().apply {
                put("featureType", "route")
                put("tripId", trip.id)
                put("title", displayTitle)
                put("startedAt", trip.startedAt)
                put("finishedAt", trip.finishedAt ?: JSONObject.NULL)
                put("distanceMeters", trip.distanceMeters)
                put("pointsCount", trip.pointsCount)
                put("objectCount", trip.objectCount)
                put("objectCountsById", objectCountsById)
                put("objectCountsByName", objectCountsByName)
            })
        }
    }

    private fun buildHitFeature(
        trip: TripMeta,
        displayTitle: String,
        hit: TripObjectHit,
        classNames: List<String>
    ): JSONObject {
        return JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(hit.lon).put(hit.lat))
            })
            put("properties", JSONObject().apply {
                put("featureType", "hit")
                put("tripId", trip.id)
                put("title", displayTitle)
                put("ts", hit.ts)
                put("labelId", hit.label)
                put("labelName", classNames.getOrNull(hit.label) ?: hit.label.toString())
            })
        }
    }

    private fun sanitizeFileName(title: String): String {
        val cleaned = title
            .replace('—', '-')
            .replace(Regex("""[\\/:*?"<>|]"""), "-")
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (cleaned.isBlank()) {
            "trip-${System.currentTimeMillis()}"
        } else {
            cleaned
        }
    }
}

object TripObjectsCounter {
    private fun dir(ctx: Context, tripId: String) = File(ctx.filesDir, "trips/$tripId")
    private fun file(ctx: Context, tripId: String) = File(dir(ctx, tripId), "objects.json")
    private fun hitsFile(ctx: Context, tripId: String) = File(dir(ctx, tripId), "hits.jsonl")

    private val lock = Any()
    private val loadedTrips = HashSet<String>()
    private val cache = HashMap<String, MutableMap<Int, Int>>()
    private val dirtyTrips = HashSet<String>()
    private val lastFlushMs = HashMap<String, Long>()

    private val io = Executors.newSingleThreadExecutor()
    private const val FLUSH_INTERVAL_MS = 2000L

    fun increment(ctx: Context, tripId: String, label: Int) {
        incrementBy(ctx, tripId, label, 1)
    }

    fun incrementBy(ctx: Context, tripId: String, label: Int, delta: Int) {
        ensureLoaded(ctx, tripId)
        synchronized(lock) {
            val m = cache.getOrPut(tripId) { mutableMapOf() }
            m[label] = (m[label] ?: 0) + delta
            dirtyTrips.add(tripId)
        }
        flushIfNeededAsync(ctx, tripId, force = false)
    }

    fun incrementBatch(ctx: Context, tripId: String, counts: Map<Int, Int>) {
        if (counts.isEmpty()) return
        ensureLoaded(ctx, tripId)
        synchronized(lock) {
            val m = cache.getOrPut(tripId) { mutableMapOf() }
            for ((k, v) in counts) {
                if (v <= 0) continue
                m[k] = (m[k] ?: 0) + v
            }
            dirtyTrips.add(tripId)
        }
        flushIfNeededAsync(ctx, tripId, force = false)
    }

    fun flushNowSync(ctx: Context, tripId: String) {
        ensureLoaded(ctx, tripId)
        flushToDiskSync(ctx, tripId)
    }

    fun clearTrip(tripId: String) {
        synchronized(lock) {
            cache.remove(tripId)
            loadedTrips.remove(tripId)
            dirtyTrips.remove(tripId)
            lastFlushMs.remove(tripId)
        }
    }

    fun getAll(ctx: Context, tripId: String): Map<Int, Int> {
        ensureLoaded(ctx, tripId)
        synchronized(lock) {
            return cache[tripId]?.toMap() ?: emptyMap()
        }
    }

    fun appendHit(ctx: Context, tripId: String, hit: TripObjectHit) {
        io.execute {
            try {
                val f = hitsFile(ctx, tripId)
                f.parentFile?.mkdirs()
                val obj = JSONObject().apply {
                    put("ts", hit.ts)
                    put("lat", hit.lat)
                    put("lon", hit.lon)
                    put("label", hit.label)
                }
                f.appendText(obj.toString() + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun readHits(ctx: Context, tripId: String): List<TripObjectHit> {
        val f = hitsFile(ctx, tripId)
        if (!f.exists()) return emptyList()

        val out = ArrayList<TripObjectHit>()
        f.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = JSONObject(line)
                out.add(
                    TripObjectHit(
                        ts = obj.getLong("ts"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        label = obj.getInt("label")
                    )
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun ensureLoaded(ctx: Context, tripId: String) {
        synchronized(lock) {
            if (loadedTrips.contains(tripId)) return
        }
        val fromDisk = readAllFromDisk(ctx, tripId)
        synchronized(lock) {
            cache[tripId] = fromDisk.toMutableMap()
            loadedTrips.add(tripId)
        }
    }

    private fun readAllFromDisk(ctx: Context, tripId: String): Map<Int, Int> {
        val f = file(ctx, tripId)
        if (!f.exists()) return emptyMap()

        return try {
            val obj = JSONObject(f.readText())
            val res = mutableMapOf<Int, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val kStr = keys.next()
                val key = kStr.toIntOrNull() ?: continue
                res[key] = obj.optInt(kStr, 0)
            }
            res
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun flushIfNeededAsync(ctx: Context, tripId: String, force: Boolean) {
        val now = System.currentTimeMillis()
        val shouldFlush = synchronized(lock) {
            if (!dirtyTrips.contains(tripId)) return
            val last = lastFlushMs[tripId] ?: 0L
            force || (now - last >= FLUSH_INTERVAL_MS)
        }
        if (!shouldFlush) return

        io.execute {
            flushToDiskSync(ctx, tripId)
        }
    }

    private fun flushToDiskSync(ctx: Context, tripId: String) {
        val snapshot: Map<Int, Int> = synchronized(lock) {
            if (!dirtyTrips.contains(tripId)) return
            val m = cache[tripId]?.toMap() ?: emptyMap()
            dirtyTrips.remove(tripId)
            lastFlushMs[tripId] = System.currentTimeMillis()
            m
        }

        try {
            val f = file(ctx, tripId)
            f.parentFile?.mkdirs()
            val obj = JSONObject()
            for ((k, v) in snapshot) obj.put(k.toString(), v)
            f.writeText(obj.toString())
        } catch (_: Exception) {
        }
    }
}