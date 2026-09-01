package com.example.ckns

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class TripMapActivity : AppCompatActivity(), OnMapReadyCallback {
    private val MAP_MB = "mbtiles"
    private val MAP_GOOGLE = "google"
    private val PREF_MAP_PROVIDER = "map_provider"
    private var tripId: String? = null
    private lateinit var root: FrameLayout
    private var classNames: List<String> = emptyList()

    private var mbtilesView: MbtilesMapView? = null
    private var googleMap: GoogleMap? = null
    private var googleContainerId: Int = View.generateViewId()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val requestLocationPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        enableMyLocationIfPermitted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tripId = intent.getStringExtra("trip_id")
        classNames = loadClassNamesFromAssets(this)
        root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        title = "Brauciena karte"
        val provider = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_MAP_PROVIDER, MAP_MB) ?: MAP_MB

        if (provider == MAP_GOOGLE) {
            setupGoogleMapUI(root)
            showGoogleMap()
        } else {
            setupMbtilesUI(root)
            showMbtilesMap()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupMbtilesUI(rootLayout: FrameLayout) {
        val map = MbtilesMapView(this).also { mbtilesView = it }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 15, 12)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = null
        }
        val zoomIn = Button(this).apply {
            text = "+"
            setOnClickListener { mbtilesView?.zoomIn() }
        }
        val zoomOut = Button(this).apply {
            text = "-"
            setOnClickListener { mbtilesView?.zoomOut() }
        }
        topBar.addView(
            zoomOut,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        topBar.addView(space(6))
        topBar.addView(
            zoomIn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        topBar.addView(space(6))
        rootLayout.addView(
            map,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootLayout.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 16
                rightMargin = 16
            }
        )
    }

    private fun showMbtilesMap() {
        val id = tripId ?: return
        val mbPath = getSharedPreferences("cfg", MODE_PRIVATE).getString("mbtiles_path", null)
        if (mbPath.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Nav izvēlēts MBTiles fails",
                Toast.LENGTH_LONG
            ).show()
            return
        } else {
            mbtilesView?.setSource(MbtilesSource(mbPath))
        }

        ioExecutor.execute {
            val pts = TripStorage.loadPoints(this, id)
            val hits = TripObjectsCounter.readHits(this, id)
            val objectCountsMap = TripObjectsCounter.getAll(this, id)

            val countsText = objectCountsMap.mapNotNull { (classId, count) ->
                val name = classNames.getOrNull(classId) ?: classId.toString()
                if (count > 0) "$name: $count" else null
            }.joinToString(", ")
            runOnUiThread {
                if (pts.isNotEmpty()) {
                    mbtilesView?.setRoute(pts)
                    fitMbtilesTo(pts)
                }
                if (hits.isNotEmpty()) {
                    mbtilesView?.setObjectHits(hits)
                }
                addBottomInfoView(
                    "Režīms: MBTiles (offline)",
                    "Punkti: ${pts.size}",
                    if (countsText.isNotBlank()) countsText else "Nav atrasto objektu"
                )
            }
        }
    }

    private fun fitMbtilesTo(points: List<TripPoint>) {
        val mv = mbtilesView ?: return
        if (points.isEmpty()) return

        var minLat = 90.0; var maxLat = -90.0
        var minLon = 180.0; var maxLon = -180.0
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        fun approxSizeDeg(): Double = max(maxLat - minLat, maxLon - minLon)
        val targetZoom = when {
            approxSizeDeg() >= 10.0 -> 8
            approxSizeDeg() >= 4.0  -> 10
            approxSizeDeg() >= 2.0  -> 11
            approxSizeDeg() >= 1.2  -> 12
            approxSizeDeg() >= 0.6  -> 13
            approxSizeDeg() >= 0.3  -> 14
            approxSizeDeg() >= 0.15 -> 15
            approxSizeDeg() >= 0.08 -> 16
            else -> 17
        }
        val baseline = 14
        val diff = targetZoom - baseline
        if (diff > 0) repeat(diff) { mv.zoomIn() } else repeat(-diff) { mv.zoomOut() }
    }

    @SuppressLint("SetTextI18n")
    private fun setupGoogleMapUI(rootLayout: FrameLayout) {
        val mapContainer = FrameLayout(this).apply { id = googleContainerId }
        rootLayout.addView(
            mapContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 120, 12)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val zoomIn = Button(this).apply {
            text = "+"
            setOnClickListener { googleMap?.animateCamera(CameraUpdateFactory.zoomIn()) }
        }
        val zoomOut = Button(this).apply {
            text = "-"
            setOnClickListener { googleMap?.animateCamera(CameraUpdateFactory.zoomOut()) }
        }
        topBar.addView(
            zoomOut,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        topBar.addView(space(6))
        topBar.addView(
            zoomIn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        topBar.addView(space(6))

        rootLayout.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 16
                rightMargin = 16
            }
        )
    }

    private fun showGoogleMap() {
        val frag = SupportMapFragment.newInstance()
        supportFragmentManager
            .beginTransaction()
            .replace(googleContainerId, frag)
            .commitNowAllowingStateLoss()

        frag.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        enableMyLocationIfPermitted()
        val id = tripId ?: return

        ioExecutor.execute {
            val pts = TripStorage.loadPoints(this, id)
            val hits = TripObjectsCounter.readHits(this, id)
            val objectCountsMap = TripObjectsCounter.getAll(this, id)

            Log.d("TripMapActivity", "GoogleMaps - Trip ID: $id, ObjectCountsMap: $objectCountsMap")
            val countsText = objectCountsMap.mapNotNull { (classId, count) ->
                val name = classNames.getOrNull(classId) ?: classId.toString()
                if (count > 0) "$name: $count" else null
            }.joinToString(", ")
            Log.d("TripMapActivity", "GoogleMaps - CountsText: '$countsText'")

            runOnUiThread {
                if (pts.isNotEmpty()) {
                    val poly = PolylineOptions().width(6f).geodesic(true).color(Color.RED)
                    val bounds = LatLngBounds.Builder()
                    for (p in pts) {
                        val ll = LatLng(p.lat, p.lon)
                        poly.add(ll)
                        bounds.include(ll)}
                    map.addPolyline(poly)

                    for (h in hits) {
                        val mo = MarkerOptions()
                            .position(LatLng(h.lat, h.lon))
                            .icon(BitmapDescriptorFactory.defaultMarker(198.6f))
                            .title("Objekts: ${h.label}")
                        map.addMarker(mo)
                    }
                    try {
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 64))
                    } catch (_: Exception) {
                        val c = LatLng(pts.last().lat, pts.last().lon)
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(c, 16f))
                    }
                }
                addBottomInfoView(
                    "Režīms: Google Maps (online)",
                    "Punkti: ${pts.size}; Atradumi: ${hits.size}",
                    if (countsText.isNotBlank()) countsText else "Nav atrasto objektu"
                )
            }
        }
    }

    private fun enableMyLocationIfPermitted() {
        val m = googleMap ?: return
        val hasFine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            try {
                m.isMyLocationEnabled = true
            } catch (_: Exception) {
            }
        } else {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
    private fun addBottomInfoView(line1: String, line2: String, line3: String? = null) {
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 65)
            background = null
        }
        val t1 = TextView(this).apply {
            text = line1
            setTypeface(typeface, Typeface.BOLD)
        }
        val t2 = TextView(this).apply { text = line2 }
        info.addView(t1)
        info.addView(t2)
        if (line3 != null) {
            val t3 = TextView(this).apply {
                text = line3
                textSize = 12f
            }
            info.addView(t3)
        }

        root.addView(
            info,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = 16
                bottomMargin = 16
            }
        )
    }
    private fun space(w: Int): View = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }
}
