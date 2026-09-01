package com.example.ckns

import android.R
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TripListActivity : ComponentActivity() {
    private lateinit var listView: ListView
    private var trips: List<TripMeta> = emptyList()
    private var classNames: List<String> = emptyList()

    private var pendingExportTrip: TripMeta? = null
    private var pendingExportTitle: String? = null

    private val createGeoJsonDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri: Uri? ->
            val trip = pendingExportTrip
            val displayTitle = pendingExportTitle

            pendingExportTrip = null
            pendingExportTitle = null

            if (trip == null || displayTitle == null) return@registerForActivityResult

            if (uri == null) {
                Toast.makeText(this, "Eksports atcelts", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            exportTripToUri(uri, trip, displayTitle)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        listView = ListView(this)
        setContentView(listView)

        ViewCompat.setOnApplyWindowInsetsListener(listView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        classNames = loadClassNamesFromAssets(this)
        loadTrips()

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = trips.size
            override fun getItem(position: Int): Any = trips[position]
            override fun getItemId(position: Int): Long = position.toLong()

            @SuppressLint("DefaultLocale", "SetTextI18n")
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val ctx = this@TripListActivity
                val trip = trips[position]

                val itemRootLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                }

                val topPartLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val date = trip.prettyTitle()
                val km = String.format("%.2f", trip.distanceMeters / 1000.0)
                val objs = trip.objectCount
                val displayTitle = "$date — ${km} km, ${trip.pointsCount} punkti, objekti: $objs"

                val titleTextView = TextView(ctx).apply {
                    text = displayTitle
                }

                val spacer = Space(ctx)

                val exportButton = Button(ctx).apply {
                    text = "Eksportēt"
                }

                val deleteButton = Button(ctx).apply {
                    text = "Dzēst"
                }

                exportButton.setOnClickListener {
                    pendingExportTrip = trip
                    pendingExportTitle = displayTitle
                    createGeoJsonDocument.launch(
                        TripStorage.suggestedGeoJsonFileName(displayTitle)
                    )
                }

                deleteButton.setOnClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("Dzēst braucienu?")
                        .setMessage("Vai tiešām dzēst šo braucienu?")
                        .setNegativeButton("Atcelt", null)
                        .setPositiveButton("Dzēst") { d, _ ->
                            if (TripStorage.deleteTrip(ctx, trip.id)) {
                                Toast.makeText(ctx, "Dzēsts", Toast.LENGTH_SHORT).show()
                                loadTrips()
                                notifyDataSetChanged()
                            } else {
                                Toast.makeText(ctx, "Neizdevās dzēst", Toast.LENGTH_LONG).show()
                            }
                            d.dismiss()
                        }
                        .show()
                }

                topPartLayout.addView(
                    titleTextView,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                topPartLayout.addView(
                    spacer,
                    LinearLayout.LayoutParams(8, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                topPartLayout.addView(
                    exportButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = 8
                    }
                )
                topPartLayout.addView(
                    deleteButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = 8
                    }
                )

                itemRootLayout.addView(topPartLayout)

                val countsTextView = TextView(ctx).apply {
                    val objectCountsMap = TripObjectsCounter.getAll(ctx, trip.id)
                    if (objectCountsMap.isNotEmpty()) {
                        val countsText = objectCountsMap.mapNotNull { (classId, count) ->
                            val name = classNames.getOrNull(classId) ?: classId.toString()
                            if (count > 0) "$name: $count" else null
                        }.joinToString(", ")

                        if (countsText.isNotBlank()) {
                            text = countsText
                            visibility = View.VISIBLE
                            val lp = layoutParams as? ViewGroup.MarginLayoutParams
                                ?: ViewGroup.MarginLayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            lp.topMargin = (8 * ctx.resources.displayMetrics.density).toInt()
                            layoutParams = lp
                        } else {
                            visibility = View.GONE
                        }
                    } else {
                        visibility = View.GONE
                    }
                    textSize = 12f
                }

                if (countsTextView.isVisible) {
                    itemRootLayout.addView(countsTextView)
                }

                itemRootLayout.setOnClickListener {
                    val i = Intent(ctx, TripMapActivity::class.java).apply {
                        putExtra("trip_id", trip.id)
                    }
                    startActivity(i)
                }

                return itemRootLayout
            }
        }

        title = "Saglabātie braucieni"
    }

    private fun exportTripToUri(uri: Uri, trip: TripMeta, displayTitle: String) {
        Thread {
            val result = TripStorage.exportTripGeoJsonToUri(
                ctx = this,
                trip = trip,
                displayTitle = displayTitle,
                classNames = classNames,
                uri = uri
            )

            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "Brauciens eksportēts", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Eksports neizdevās: ${error.message ?: "nezināma kļūda"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun loadTrips() {
        trips = TripStorage.listTrips(this).sortedByDescending { it.startedAt }
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }
}

class GpsTripListActivity : ComponentActivity() {
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var trips: List<TripMeta> = emptyList()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val bStart = Button(context).apply { text = "Start" }
            val bStop = Button(context).apply { text = "Stop" }
            addView(bStart)
            addView(bStop)
            bStart.setOnClickListener { TripApi.startRecording(this@GpsTripListActivity) }
            bStop.setOnClickListener { TripApi.stopRecording(this@GpsTripListActivity) }
        }

        listView = ListView(this)
        adapter = ArrayAdapter(this, R.layout.simple_list_item_1, ArrayList())
        listView.adapter = adapter

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(
                listView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val id = trips[pos].id
            startActivity(Intent(this, TripMapActivity::class.java).putExtra("trip_id", id))
        }
    }

    override fun onResume() {
        super.onResume()
        trips = TripStorage.listTrips(this)
        adapter.clear()
        adapter.addAll(trips.map { it.prettyTitle() })
    }
}