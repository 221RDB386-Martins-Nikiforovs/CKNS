package com.example.ckns

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MbtilesSource(path: String) {
    companion object {
        const val TILE_SIZE = 256
    }
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    )
    val isVector: Boolean by lazy {
        val fmt = readMetadata("format")?.lowercase() ?: ""
        fmt.contains("pbf") || fmt.contains("mvt")
    }
    val minZoom: Int by lazy { (readMetadata("minzoom") ?: "0").toIntOrNull() ?: 0 }
    val maxZoom: Int by lazy { (readMetadata("maxzoom") ?: "16").toIntOrNull() ?: 16 }
    fun close() = try {
        db.close()
    } catch (_: Throwable) {
    }

    private fun readMetadata(key: String): String? = try {
        db.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }

    fun getTile(z: Int, x: Int, yTms: Int): Bitmap? {
        return if (isVector) renderVectorTile(z, x, yTms) else readRasterTile(z, x, yTms)
    }

    private fun readRasterTile(z: Int, x: Int, yTms: Int): Bitmap? = try {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), yTms.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val blob = c.getBlob(0)
            decodeRaster(blob)
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeRaster(bytes: ByteArray): Bitmap? {
        fun decode(b: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(b, 0, b.size)
        decode(bytes)?.let { return it }
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                val decompressed = gz.readBytes()
                decode(decompressed)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        try {
            InflaterInputStream(ByteArrayInputStream(bytes)).use { inf ->
                val decompressed = inf.readBytes()
                decode(decompressed)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    @SuppressLint("UseKtx")
    private fun renderVectorTile(z: Int, x: Int, yTms: Int): Bitmap? = try {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), yTms.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val blob = c.getBlob(0)
            val pbf = try {
                GZIPInputStream(ByteArrayInputStream(blob)).readBytes()
            } catch (_: Throwable) {
                try {
                    InflaterInputStream(ByteArrayInputStream(blob)).readBytes()
                } catch (_: Throwable) {
                    blob
                }
            }
            val bmp = createBitmap(TILE_SIZE, TILE_SIZE)
            val bgCanvas = Canvas(bmp)
            bgCanvas.drawColor(Color.WHITE)

            MvtRenderer.drawTile(bmp, pbf, TILE_SIZE, TILE_SIZE)
            bmp
        }
    } catch (_: Throwable) {
        null
    }
}

class MbtilesMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var source: MbtilesSource? = null
    private val tileCache = ConcurrentHashMap<Long, Bitmap>()
    // aptuvenas Rigas koordinaatas (aizgūtas no https://lv.wikipedia.org/wiki/R%C4%ABga ), priekš default
    private var centerLat = 56.9475
    private var centerLon = 24.1063
    private var zoom = 14
    private var route: List<TripPoint> = emptyList()
    private var hits: List<TripObjectHit> = emptyList()
    var onZoomChanged: ((Int) -> Unit)? = null
    var onCenterChanged: ((Double, Double) -> Unit)? = null

    private val gesture =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                val tileSize = MbtilesSource.TILE_SIZE.toDouble()
                val scale = 1 shl zoom
                val lonPerPx = 360.0 / (tileSize * scale)
                val latPerPx = 360.0 / (tileSize * scale)
                centerLon += dx * lonPerPx
                centerLat -= dy * latPerPx
                centerLon = ((centerLon + 180.0 + 360.0) % 360.0) - 180.0
                centerLat = centerLat.coerceIn(-85.05112878, 85.05112878)
                onCenterChanged?.invoke(centerLat, centerLon)
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomIn(); return true
            }
        })
    private val scaler =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleThreshold = 0.15f
                if (detector.scaleFactor > 1.0f + scaleThreshold) {
                    zoomIn()
                    return true
                } else if (detector.scaleFactor < 1.0f - scaleThreshold) {
                    zoomOut()
                    return true
                }
                return false
            }
        })

    fun setSource(src: MbtilesSource) {
        this.source = src
        zoom = src.minZoom.coerceAtLeast(1).coerceAtMost(MAX_UI_ZOOM)
        invalidate()
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = scaler.onTouchEvent(event)
        val b = gesture.onTouchEvent(event)
        return a || b || super.onTouchEvent(event)
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tileCache.values.forEach { it.recycle() }
        tileCache.clear()
        source?.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val (cx, cy) = latLonToTile(centerLat, centerLon, zoom)
        val left = floor(cx - w / MbtilesSource.TILE_SIZE / 2.0).toInt()
        val top = floor(cy - h / MbtilesSource.TILE_SIZE / 2.0).toInt()
        val cols = w / MbtilesSource.TILE_SIZE + 2
        val rows = h / MbtilesSource.TILE_SIZE + 2

        for (dy in 0..rows) for (dx in 0..cols) {
            val tx = left + dx
            val ty = top + dy
            val px = ((tx - cx) * MbtilesSource.TILE_SIZE + w / 2).toInt()
            val py = ((ty - cy) * MbtilesSource.TILE_SIZE + h / 2).toInt()
            drawTileWithOverscale(canvas, src, zoom, tx, ty, px, py)
        }
        if (route.isNotEmpty()) drawRoute(canvas, route)
        if (hits.isNotEmpty()) drawHits(canvas, hits)
    }

    private fun drawTileWithOverscale(
        canvas: Canvas,
        src: MbtilesSource,
        zReq: Int,
        xReq: Int,
        yReq: Int,
        px: Int,
        py: Int
    ) {
        val nReq = 1 shl zReq
        if (yReq !in 0 until nReq) return
        val xNormReq = ((xReq % nReq) + nReq) % nReq
        val zAvail = min(zReq, src.maxZoom)
        val dz = zReq - zAvail
        val xAvail = xNormReq shr dz
        val yAvail = yReq shr dz
        val nAvail = 1 shl zAvail
        val xNormAvail = ((xAvail % nAvail) + nAvail) % nAvail
        val yTmsAvail = (nAvail - 1) - yAvail

        val key = ((zAvail.toLong() shl 58) or (xNormAvail.toLong() shl 29) or yTmsAvail.toLong())
        val bmp = tileCache[key] ?: src.getTile(zAvail, xNormAvail, yTmsAvail)
            ?.also { tileCache[key] = it } ?: return

        if (dz == 0) {
            canvas.drawBitmap(bmp, px.toFloat(), py.toFloat(), null)
            return
        }
        val factor = 1 shl dz
        val subSize = MbtilesSource.TILE_SIZE / factor
        val sx = (xNormReq and (factor - 1)) * subSize
        val sy = (yReq and (factor - 1)) * subSize
        val srcRect = Rect(sx, sy, sx + subSize, sy + subSize)
        val dstRect = Rect(px, py, px + MbtilesSource.TILE_SIZE, py + MbtilesSource.TILE_SIZE)
        canvas.drawBitmap(bmp, srcRect, dstRect, null)
    }

    private fun drawRoute(canvas: Canvas, pts: List<TripPoint>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.RED
        }
        var lastX: Float? = null
        var lastY: Float? = null
        for (i in pts.indices) {
            val (px, py) = latLonToPixel(pts[i].lat, pts[i].lon, zoom)
            val cx = width / 2f
            val cy = height / 2f
            val (tx, ty) = latLonToTile(centerLat, centerLon, zoom)
            val ox = (px - tx * MbtilesSource.TILE_SIZE + cx).toFloat()
            val oy = (py - ty * MbtilesSource.TILE_SIZE + cy).toFloat()
            if (lastX != null && lastY != null) canvas.drawLine(lastX!!, lastY!!, ox, oy, p)
            lastX = ox; lastY = oy
        }
    }
    private fun drawHits(canvas: Canvas, spots: List<TripObjectHit>) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = "#4FC3F7".toColorInt()
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
        val radius = 10f
        val cxView = width / 2f
        val cyView = height / 2f
        val (tx, ty) = latLonToTile(centerLat, centerLon, zoom)

        for (h in spots) {
            val (px, py) = latLonToPixel(h.lat, h.lon, zoom)
            val ox = (px - tx * MbtilesSource.TILE_SIZE + cxView).toFloat()
            val oy = (py - ty * MbtilesSource.TILE_SIZE + cyView).toFloat()
            canvas.drawCircle(ox, oy, radius, fill)
            canvas.drawCircle(ox, oy, radius, stroke)
        }
    }

    fun setRoute(points: List<TripPoint>) {
        route = points; invalidate()
    }
    fun setObjectHits(h: List<TripObjectHit>) {
        hits = h; invalidate()
    }
    fun zoomIn() {
        val nz = min(MAX_UI_ZOOM, zoom + 1)
        if (nz != zoom) {
            zoom = nz; onZoomChanged?.invoke(zoom); invalidate()
        }
    }
    fun zoomOut() {
        val nz = max(0, zoom - 1)
        if (nz != zoom) {
            zoom = nz; onZoomChanged?.invoke(zoom); invalidate()
        }
    }
    private fun latLonToTile(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val latClamped = max(-85.05112878, min(85.05112878, lat))
        val x = (lon + 180.0) / 360.0 * (1 shl z)
        val s = sin(latClamped * Math.PI / 180.0)
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)) * (1 shl z)
        return x to y
    }
    private fun latLonToPixel(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val (tx, ty) = latLonToTile(lat, lon, z)
        return tx * MbtilesSource.TILE_SIZE to ty * MbtilesSource.TILE_SIZE
    }
    companion object {
        const val MAX_UI_ZOOM = 24
    }
}

object MvtRenderer {
    fun drawTile(
        bitmap: Bitmap,
        pbf: ByteArray,
        width: Int,
        height: Int
    ) {
        val canvas = Canvas(bitmap)

        val layers = try { decodeTile(pbf) } catch (_: Throwable) { emptyList() }
        if (layers.isEmpty()) return

        layers.forEach { layer ->
            val extent = layer.extent.toFloat().coerceAtLeast(1f)
            val sx = width / extent
            val sy = height / extent

            layer.features.filter { it.type == GeomType.Polygon }.forEach { f ->
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
                for (ring in f.rings) {
                    var first = true
                    for ((ix, iy) in ring) {
                        val px = ix * sx
                        val py = iy * sy
                        if (first) {
                            path.moveTo(px, py); first = false
                        } else path.lineTo(px, py)
                    }
                    path.close()
                }
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = when {
                        layer.name.contains("water", true) -> 0xFFBFDFFF.toInt()
                        layer.name.contains("park", true) || layer.name.contains(
                            "landuse",
                            true
                        ) -> 0xFFE8F2E8.toInt()

                        else -> 0xFFF3F3F3.toInt()
                    }
                }
                canvas.drawPath(path, fillPaint)

                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.2f
                    color = 0xFFB9B9B9.toInt()
                }
                canvas.drawPath(path, strokePaint)
            }
            layer.features.filter { it.type == GeomType.LineString }.forEach { f ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = when {
                        layer.name.contains("motor", true) || layer.name.contains(
                            "highway",
                            true
                        ) -> 3.5f

                        layer.name.contains("road", true) -> 2.5f
                        else -> 1.5f
                    }
                    color = when {
                        layer.name.contains("water", true) -> 0xFF6EA5FF.toInt()
                        else -> 0xFF666666.toInt()
                    }
                }
                for (line in f.lines) {
                    val path = Path()
                    var first = true
                    for ((ix, iy) in line) {
                        val px = ix * sx
                        val py = iy * sy
                        if (first) {
                            path.moveTo(px, py); first = false
                        } else path.lineTo(px, py)
                    }
                    canvas.drawPath(path, p)
                }
            }
        }
    }
    private enum class GeomType { Unknown, Point, LineString, Polygon }

    private data class Feature(
        val type: GeomType,
        val lines: List<List<Pair<Float, Float>>>,
        val rings: List<List<Pair<Float, Float>>>
    )

    private data class Layer(val name: String, val extent: Int, val features: List<Feature>)

    private fun decodeTile(buf: ByteArray): List<Layer> {
        var i = 0
        val layers = ArrayList<Layer>()

        while (i < buf.size) {
            val key = readVarint(buf, i).also { i = it.second }.first
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (field == 3 && wire == 2) {
                val len = readVarint(buf, i).also { i = it.second }.first.toInt()
                val end = i + len
                layers += decodeLayer(buf, i, end)
                i = end
            } else {
                i = skipField(buf, i, wire)
            }
        }
        return layers
    }

    private fun decodeLayer(buf: ByteArray, start: Int, end: Int): Layer {
        var i = start
        var name = "layer"
        var extent = 4096
        val features = ArrayList<Feature>()

        while (i < end) {
            val key = readVarint(buf, i).also { i = it.second }.first
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (field) {
                1 -> {
                    val len = readVarint(buf, i).also { i = it.second }.first.toInt()
                    name = buf.copyOfRange(i, i + len).toString(Charsets.UTF_8)
                    i += len
                }
                2 -> {
                    val len = readVarint(buf, i).also { i = it.second }.first.toInt()
                    val fEnd = i + len
                    features += decodeFeature(buf, i, fEnd)
                    i = fEnd
                }
                5 -> {
                    extent = readVarint(buf, i).also { i = it.second }.first.toInt()
                }
                else -> {
                    i = skipField(buf, i, wire)
                }
            }
        }
        return Layer(name, extent, features)
    }

    private fun decodeFeature(buf: ByteArray, start: Int, end: Int): Feature {
        var i = start
        var type = GeomType.Unknown
        var geomBytes: ByteArray? = null

        while (i < end) {
            val key = readVarint(buf, i).also { i = it.second }.first
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (field) {
                3 -> {
                    val t = readVarint(buf, i).also { i = it.second }.first.toInt()
                    type = when (t) { 1 -> GeomType.Point; 2 -> GeomType.LineString; 3 -> GeomType.Polygon; else -> GeomType.Unknown }
                }
                4 -> {
                    val len = readVarint(buf, i).also { i = it.second }.first.toInt()
                    geomBytes = buf.copyOfRange(i, i + len)
                    i += len
                }
                else -> { i = skipField(buf, i, wire) }
            }
        }

        val lines = ArrayList<List<Pair<Float, Float>>>()
        val rings = ArrayList<List<Pair<Float, Float>>>()

        if (geomBytes != null && type != GeomType.Unknown) {
            var j = 0
            var cursorX = 0
            var cursorY = 0
            val current = ArrayList<Pair<Float, Float>>()

            fun closeCurrent() {
                if (current.isNotEmpty()) {
                    when (type) {
                        GeomType.LineString -> lines += current.toList()
                        GeomType.Polygon -> rings += current.toList()
                        else -> {}
                    }
                    current.clear()
                }
            }

            while (j < geomBytes.size) {
                val cmdLen = readVarint(geomBytes, j).also { j = it.second }.first.toInt()
                val cmdId = cmdLen and 0x7
                val count = cmdLen ushr 3
                when (cmdId) {
                    1 -> {
                        closeCurrent()
                        repeat(count) {
                            val dx = zigZag(readVarint(geomBytes, j).also { j = it.second }.first)
                            val dy = zigZag(readVarint(geomBytes, j).also { j = it.second }.first)
                            cursorX += dx
                            cursorY += dy
                            current.add(cursorX.toFloat() to cursorY.toFloat())
                        }
                    }
                    2 -> {
                        repeat(count) {
                            val dx = zigZag(readVarint(geomBytes, j).also { j = it.second }.first)
                            val dy = zigZag(readVarint(geomBytes, j).also { j = it.second }.first)
                            cursorX += dx
                            cursorY += dy
                            current.add(cursorX.toFloat() to cursorY.toFloat())
                        }
                    }
                    7 -> {
                        if (type == GeomType.Polygon) {
                            if (current.isNotEmpty() && current.first() != current.last()) {
                                current.add(current.first())
                            }
                        }
                        closeCurrent()
                    }
                    else -> break
                }
            }
            if (current.isNotEmpty()) closeCurrent()
        }

        return Feature(type, lines, rings)
    }

    private fun readVarint(buf: ByteArray, pos: Int): Pair<Long, Int> {
        var i = pos
        var shift = 0
        var result = 0L
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            i++
            result = result or (((b and 0x7F).toLong()) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result to i
    }

    private fun skipField(buf: ByteArray, start: Int, wireType: Int): Int {
        var i = start
        when (wireType) {
            0 -> { readVarint(buf, i).also { i = it.second } }
            1 -> { i += 8 }
            2 -> {
                val len = readVarint(buf, i).also { i = it.second }.first.toInt()
                i += len
            }
            5 -> { i += 4 }
            else -> return buf.size
        }
        return i
    }
    private fun zigZag(v: Long): Int = ((v ushr 1) xor (-(v and 1))).toInt()
}

class MbtilesPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/octet-stream",
                    "application/x-sqlite3",
                    "application/vnd.sqlite3",
                    "application/mbtiles"
                )
            )
        }
        try {
            startActivityForResult(i, REQ_OPEN)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Neizdevās atvērt failu izvēlētāju: ${e.message ?: "nezināma kļūda"}",
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                try {
                    openFileOutput("selected.mbtiles", MODE_PRIVATE).use { out ->
                        contentResolver.openInputStream(uri)!!.use { inp -> inp.copyTo(out) }
                    }
                    val path = File(filesDir, "selected.mbtiles").absolutePath
                    val outI = Intent().apply { putExtra("mbtiles_path", path) }
                    setResult(RESULT_OK, outI)
                } catch (e: Throwable) {
                    Toast.makeText(
                        this,
                        "Faila kopēšana neizdevās: ${e.message ?: "nezināma kļūda"}",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_CANCELED)
                }
            } else setResult(RESULT_CANCELED)
        } else setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val REQ_OPEN = 1001
    }
}
