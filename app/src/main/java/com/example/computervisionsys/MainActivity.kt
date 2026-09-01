package com.example.ckns

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
import java.nio.ByteOrder


class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var statusTv: TextView
    private lateinit var overlay: OverlayView
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"

    private var outBoxesIdx: Int = 0
    private var outScoresIdx: Int = 1
    private var outLabelsIdx: Int = 2

    private var lastInferMs = 0L
    private var cadenceMs = 100L
    private var scoreThreshold = 0.09f

    private var classNames: List<String> = emptyList()

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val inputShape = longArrayOf(1, 3, TARGET_H.toLong(), TARGET_W.toLong())

    private var lastStatusUiMs = 0L
    private val STATUS_UI_EVERY_MS = 500L

    private var inputByteBuf: ByteBuffer? = null
    private var inputFloatBuf: FloatBuffer? = null
    private var reusableInputTensor: OnnxTensor? = null
    private var reusableInputFeed: Map<String, OnnxTensor>? = null

    private val MERGE_SAME_CLASS_OVERLAPS = true
    private val MERGE_IOU_THR = 0.10f
    private val MERGE_IOS_THR = 0.05f

    private var blurKernel = 0

    private val blurR = FloatArray(TARGET_W * TARGET_H)
    private val blurG = FloatArray(TARGET_W * TARGET_H)
    private val blurB = FloatArray(TARGET_W * TARGET_H)
    private val blurTmp = FloatArray(TARGET_W * TARGET_H)

    private val disabledClassIds = hashSetOf<Int>()

    private val USE_NNAPI = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        previewView = PreviewView(this)
        overlay = OverlayView(this)
        statusTv = TextView(this).apply {
            text = "Notiek ielade"
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0x33000000)
            setTextColor(Color.WHITE)
        }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            statusTv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { topMargin = 120 }
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(8, 8, 8, 8)
        }

        val buttons = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bTrips = Button(this).apply { text = "Braucieni" }
        val bStart = Button(this).apply { text = "Sākt ierakstu" }
        val bStop = Button(this).apply { text = "Beigt ierakstu" }
        val bSettings = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu_preferences)
            background = null
            setOnClickListener { showMapProviderDialog() }
        }

        buttons.addView(
            bTrips,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 4
                rightMargin = 4
            }
        )
        buttons.addView(
            bStart,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 4
                rightMargin = 4
            }
        )
        buttons.addView(
            bStop,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 4
                rightMargin = 4
            }
        )
        buttons.addView(
            bSettings,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 8
                rightMargin = 4
            }
        )

        controls.addView(buttons)

        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = 60
                topMargin = 60
            }
        )

        setContentView(root)

        bTrips.setOnClickListener { startActivity(Intent(this, TripListActivity::class.java)) }
        bStart.setOnClickListener { TripApi.startRecording(this) }
        bStop.setOnClickListener { TripApi.stopRecording(this) }

        if (allPermsGranted()) {
            setupOrt()
            startCamera()
        } else {
            requestPermissions(REQ_PERMS, REQ_CODE_PERMS)
        }
    }

    @SuppressLint("ResourceType", "SetTextI18n")
    private fun showMapProviderDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getString(PREF_MAP_PROVIDER, MAP_MB) ?: MAP_MB

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val titleTv = TextView(this).apply {
            text = "Iestatījumi"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(12, 8, 12, 20)
        }
        container.addView(titleTv)

        val classSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }
        val classTitle = TextView(this).apply {
            text = "Izslēgt klases"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        val classHint = TextView(this).apply {
            text = "Atzīmētās klases netiek zīmētas un netiek saglabātas braucienā"
            setTextColor(Color.BLACK)
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        val classRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val cb1 = CheckBox(this).apply {
            text = "1"
            isChecked = 1 in disabledClassIds
            setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) disabledClassIds.add(1) else disabledClassIds.remove(1)
            }
        }
        val cb2 = CheckBox(this).apply {
            text = "2"
            isChecked = 2 in disabledClassIds
            setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) disabledClassIds.add(2) else disabledClassIds.remove(2)
            }
        }
        val cb3 = CheckBox(this).apply {
            text = "3"
            isChecked = 3 in disabledClassIds
            setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) disabledClassIds.add(3) else disabledClassIds.remove(3)
            }
        }
        val cb4 = CheckBox(this).apply {
            text = "4"
            isChecked = 4 in disabledClassIds
            setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) disabledClassIds.add(4) else disabledClassIds.remove(4)
            }
        }

        classRow.addView(
            cb1,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        classRow.addView(
            cb2,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        classRow.addView(
            cb3,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        classRow.addView(
            cb4,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        classSection.addView(classTitle)
        classSection.addView(classHint)
        classSection.addView(classRow)

        container.addView(
            classSection,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        )

        val blurSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#ECECEC"))
        }
        val blurTitle = TextView(this).apply {
            text = "Blur filtrs"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        }
        val blurRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val bBlurOff = Button(this).apply {
            text = "OFF"
            setTextColor(Color.BLACK)
        }
        val bBlurLow = Button(this).apply {
            text = "LOW"
            setTextColor(Color.BLACK)
        }
        val bBlurMedium = Button(this).apply {
            text = "MEDIUM"
            setTextColor(Color.BLACK)
        }
        val bBlurStrong = Button(this).apply {
            text = "STRONG"
            setTextColor(Color.BLACK)
        }

        val blurButtons = listOf(
            bBlurOff to 0,
            bBlurLow to 3,
            bBlurMedium to 5,
            bBlurStrong to 7
        )

        fun refreshBlurButtons() {
            for ((btn, kernel) in blurButtons) {
                val selected = blurKernel == kernel

                btn.isEnabled = true
                btn.alpha = 1.0f

                if (selected) {
                    btn.setBackgroundColor(Color.parseColor("#616161"))
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.parseColor("#E0E0E0"))
                    btn.setTextColor(Color.BLACK)
                }
            }
        }

        for ((btn, kernel) in blurButtons) {
            btn.setOnClickListener {
                blurKernel = kernel
                refreshBlurButtons()
            }
            blurRow.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = 4
                    rightMargin = 4
                }
            )
        }

        refreshBlurButtons()
        blurSection.addView(blurTitle)
        blurSection.addView(blurRow)

        container.addView(
            blurSection,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        )

        val thrSection = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }
        val thrTitle = TextView(this).apply {
            text = "Pārliecības slieksnis"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        val thrLabel = TextView(this).apply {
            text = "Slieksnis: ${(scoreThreshold * 100).toInt()}%"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        val thrSeek = SeekBar(this).apply {
            max = 100
            progress = (scoreThreshold * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    scoreThreshold = p / 100f
                    thrLabel.text = "Slieksnis: $p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        thrSection.addView(thrTitle)
        thrSection.addView(thrLabel)
        thrSection.addView(thrSeek)

        container.addView(
            thrSection,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        )

        val cadSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#ECECEC"))
        }
        val cadTitle = TextView(this).apply {
            text = "Kadance"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        val cadLabel = TextView(this).apply {
            text = "Kadance: ${cadenceMs}ms"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        val cadSeek = SeekBar(this).apply {
            max = 2000
            progress = cadenceMs.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    cadenceMs = p.coerceAtLeast(0).toLong()
                    cadLabel.text = "Kadance: ${cadenceMs}ms"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        cadSection.addView(cadTitle)
        cadSection.addView(cadLabel)
        cadSection.addView(cadSeek)

        container.addView(
            cadSection,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        )

        val mapSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }
        val mapTitle = TextView(this).apply {
            text = "Kartes avots"
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        }

        val rbGg = RadioButton(this).apply {
            text = "Google Maps (online)"
            id = 2
            setTextColor(Color.BLACK)
        }
        val rbMb = RadioButton(this).apply {
            text = "MBTiles (offline)"
            id = 1
            setTextColor(Color.BLACK)
        }
        val pickBtn = Button(this).apply {
            text = "IZVĒLĒTIES MBTILES"
            setTextColor(Color.BLACK)
        }

        fun selectMap(sel: String) {
            rbGg.isChecked = sel == MAP_GOOGLE
            rbMb.isChecked = sel == MAP_MB
        }

        rbGg.setOnClickListener { selectMap(MAP_GOOGLE) }
        rbMb.setOnClickListener { selectMap(MAP_MB) }
        selectMap(current)

        val ggRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ggRow.addView(
            rbGg,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val mbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mbRow.addView(
            rbMb,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        mbRow.addView(
            pickBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
        )

        mapSection.addView(mapTitle)
        mapSection.addView(ggRow)
        mapSection.addView(mbRow)

        container.addView(
            mapSection,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dlg = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Labi") { d, _ ->
                val sel = if (rbGg.isChecked) MAP_GOOGLE else MAP_MB
                prefs.edit { putString(PREF_MAP_PROVIDER, sel) }
                Toast.makeText(
                    this,
                    "Izvēlēts: " + if (sel == MAP_GOOGLE) "Google Maps (online)" else "MBTiles (offline)",
                    Toast.LENGTH_SHORT
                ).show()
                d.dismiss()
            }
            .setNegativeButton("Atcelt", null)
            .setNeutralButton("INFO", null)
            .create()

        pickBtn.setOnClickListener {
            startActivityForResult(Intent(this, MbtilesPickerActivity::class.java), 2002)
        }

        dlg.show()

        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(Color.BLACK)
            setOnClickListener {
                showInfoDialog()
            }
        }
    }

    private fun showInfoDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 40, 48, 24)
            setBackgroundColor(Color.WHITE)
        }

        val authorTv = TextView(this).apply {
            text = "Darba autors: Mārtiņš Nikiforovs (221RDB386)"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.BLACK)
        }

        val subtitleTv = TextView(this).apply {
            text = "\nKlašu atšifrējumi:\n\n1 - plaisa\n2 - bedre\n3 - gara plaisa\n4 - ceļa ielāps"

            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 12, 0, 0)
        }

        content.addView(
            authorTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            subtitleTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dlg = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("Aizvērt", null)
            .create()

        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
    }

    override fun onDestroy() {
        super.onDestroy()
        reusableInputTensor?.close()
        reusableInputTensor = null
        reusableInputFeed = null
        inputFloatBuf = null
        inputByteBuf = null

        ortSession?.close(); ortSession = null
        ortEnv?.close(); ortEnv = null
        analysisExecutor.shutdown()
    }
    private fun allPermsGranted(): Boolean {
        val ps = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        return ps.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }
    private val REQ_PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    private val REQ_CODE_PERMS = 1234
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CODE_PERMS && allPermsGranted()) { setupOrt(); startCamera() }
        else Toast.makeText(this, "Nepieciešamas atļaujas", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetTextI18n")
    private fun setupOrt() {
        if (ortSession != null) return

        val env = OrtEnvironment.getEnvironment()

        val intraThreads = 4
        val interThreads = 1

        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(intraThreads)
            try { setInterOpNumThreads(interThreads) } catch (_: Throwable) {}
        }

        if (USE_NNAPI && !isProbablyEmulator()) {
            try { opts.addNnapi() } catch (_: Throwable) {}
        }

        val modelPath = AssetUtils(this).assetFilePath("model.onnx")
        val sess = env.createSession(modelPath, opts)

        ortEnv = env
        ortSession = sess
        inputName = sess.inputNames.firstOrNull() ?: "images"
        resolveOutputIndices(sess)
        classNames = loadClassNamesFromAssets(this)

        reusableInputTensor?.close()
        reusableInputTensor = null
        reusableInputFeed = null
        inputFloatBuf = null
        inputByteBuf = null

        val byteCount = 4 * 3 * TARGET_W * TARGET_H
        val bb = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        val tensor = OnnxTensor.createTensor(env, fb, inputShape)

        inputByteBuf = bb
        inputFloatBuf = fb
        reusableInputTensor = tensor
        reusableInputFeed = mapOf(inputName to tensor)

        val HwName = if (USE_NNAPI && !isProbablyEmulator()) "NNAPI" else "CPU"
        statusTv.text = "Modelis ielādēts ($inputName, intra=$intraThreads, inter=$interThreads, HW=$HwName)"    }

    private fun isProbablyEmulator(): Boolean {
        val fp = Build.FINGERPRINT
        val model = Build.MODEL
        return fp.contains("generic", true) ||
                fp.contains("unknown", true) ||
                model.contains("google_sdk", true) ||
                model.contains("Emulator", true) ||
                model.contains("Android SDK built for", true)
    }
    private fun resolveOutputIndices(sess: OrtSession) {
        val outs = sess.outputNames.toList()
        fun findIdx(name: String): Int = outs.indexOfFirst { it.equals(name, ignoreCase = true) }
        val bi = findIdx("boxes")
        val si = findIdx("scores")
        val li = findIdx("labels")
        if (bi >= 0) outBoxesIdx = bi
        if (si >= 0) outScoresIdx = si
        if (li >= 0) outLabelsIdx = li
    }
    @SuppressLint("SetTextI18n")
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(TARGET_W, TARGET_H))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1)
                .build()

            analysis.setAnalyzer(analysisExecutor) { image ->
                val now = System.currentTimeMillis()
                if (now - lastInferMs < cadenceMs) {
                    image.close()
                    return@setAnalyzer
                }

                val session = ortSession
                if (session == null || ortEnv == null) {
                    image.close()
                    return@setAnalyzer
                }

                val imgW = image.width
                val imgH = image.height

                val fb = inputFloatBuf
                val feed = reusableInputFeed
                if (fb == null || feed == null) {
                    try { image.close() } catch (_: Throwable) {}
                    statusTv.post { statusTv.text = "Kļūda: nav sagatavots reusable input tensor" }
                    return@setAnalyzer
                }

                val chwReady = try {
                    fillChwFromRgba8888(image, fb)
                } catch (t: Throwable) {
                    Log.e("CV2", "RGBA fast-path kļūda", t)
                    false
                } finally {
                    try {
                        image.close()
                    } catch (_: Throwable) {
                    }
                }

                if (!chwReady) {
                    Log.w("CV2", "Frame skipped: fillChwFromRgba8888=false, w=$imgW h=$imgH target=${TARGET_W}x${TARGET_H}")
                    return@setAnalyzer
                }

                lastInferMs = now

                try {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    session.run(feed).use { outs ->
                        val t1 = SystemClock.elapsedRealtimeNanos()
                        val ms = (t1 - t0) / 1_000_000.0
                        Log.d("ORT_BENCH", "session.run ms=%.1f".format(ms))

                        val detsRaw = parseDetections(outs)
                        val dets = filterDisabledClasses(detsRaw)

                        overlay.post { overlay.setDetections(dets, classNames) }

                        val uiNow = SystemClock.uptimeMillis()
                        if (uiNow - lastStatusUiMs >= STATUS_UI_EVERY_MS) {
                            lastStatusUiMs = uiNow
                            statusTv.post {
                                val best = dets.maxOfOrNull { it.score } ?: 0f
                                statusTv.text =
                                    "Obj: ${dets.size} | Labākais=${"%.2f".format(best)} | Slieksnis=${"%.2f".format(scoreThreshold)} | Ātrums=${"%.1f".format(ms)}ms"
                            }
                        }
                    }
                } catch (e: Throwable) {
                    statusTv.post { statusTv.text = "Kļūda: ${e.message}" }
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                statusTv.text = "CameraX kļūda: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun parseDetections(outputs: OrtSession.Result): List<Detection> {
        return try {
            if (outputs.size() != 3) return emptyList()

            val boxesAny = outputs.get(outBoxesIdx).value
            val scoresAny = outputs.get(outScoresIdx).value
            val labelsAny = outputs.get(outLabelsIdx).value

            fun boxAt(i: Int): FloatArray? {
                return when (boxesAny) {
                    is Array<*> -> {
                        when (val first = boxesAny.firstOrNull()) {
                            is FloatArray -> boxesAny.getOrNull(i) as? FloatArray
                            is Array<*> -> first.getOrNull(i) as? FloatArray
                            else -> null
                        }
                    }
                    else -> null
                }
            }

            fun scoreAt(i: Int): Float? = when (scoresAny) {
                is FloatArray -> scoresAny.getOrNull(i)
                is Array<*> -> {
                    when (val first = scoresAny.firstOrNull()) {
                        is FloatArray -> first.getOrNull(i)
                        else -> null
                    }
                }
                else -> null
            }

            fun rawLabelAt(i: Int): Int = when (labelsAny) {
                is LongArray -> labelsAny.getOrNull(i)?.toInt() ?: 0
                is IntArray -> labelsAny.getOrNull(i) ?: 0
                is FloatArray -> labelsAny.getOrNull(i)?.toInt() ?: 0
                is Array<*> -> {
                    when (val first = labelsAny.firstOrNull()) {
                        is LongArray -> first.getOrNull(i)?.toInt() ?: 0
                        is IntArray -> first.getOrNull(i) ?: 0
                        is FloatArray -> first.getOrNull(i)?.toInt() ?: 0
                        else -> 0
                    }
                }
                else -> 0
            }

            val out = ArrayList<Detection>(30)
            var i = 0

            while (true) {
                val bb = boxAt(i) ?: break
                if (bb.size < 4) {
                    i++
                    continue
                }

                val s = scoreAt(i) ?: 0f
                if (s < scoreThreshold) {
                    i++
                    continue
                }

                var x1 = bb[0]
                var y1 = bb[1]
                var x2 = bb[2]
                var y2 = bb[3]

                if (x1 > 1f || y1 > 1f || x2 > 1f || y2 > 1f) {
                    x1 /= TARGET_W
                    x2 /= TARGET_W
                    y1 /= TARGET_H
                    y2 /= TARGET_H
                }

                val l = min(x1, x2).coerceIn(0f, 1f)
                val r = max(x1, x2).coerceIn(0f, 1f)
                val t = min(y1, y2).coerceIn(0f, 1f)
                val b = max(y1, y2).coerceIn(0f, 1f)

                if (r <= l || b <= t) {
                    i++
                    continue
                }

                val lblRaw = rawLabelAt(i)
                val lbl = normalizeClassIndex(lblRaw)

                out.add(
                    Detection(
                        xmin = l,
                        ymin = t,
                        xmax = r,
                        ymax = b,
                        label = lbl,
                        score = s
                    )
                )
                i++
            }

            if (MERGE_SAME_CLASS_OVERLAPS) mergeSameClassOverlaps(out) else out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun filterDisabledClasses(dets: List<Detection>): List<Detection> {
        if (disabledClassIds.isEmpty()) return dets
        return dets.filterNot { it.label in disabledClassIds }
    }

    private fun mergeSameClassOverlaps(dets: List<Detection>): List<Detection> {
        if (dets.isEmpty()) return dets

        val merged = ArrayList<Detection>()

        dets.groupBy { it.label }.forEach { (cls, clsDets) ->
            val sorted = clsDets.sortedByDescending { it.score }
            val used = BooleanArray(sorted.size)

            for (i in sorted.indices) {
                if (used[i]) continue

                val group = ArrayList<Int>()
                group.add(i)
                used[i] = true

                var changed = true
                while (changed) {
                    changed = false

                    var ux1 = Float.POSITIVE_INFINITY
                    var uy1 = Float.POSITIVE_INFINITY
                    var ux2 = Float.NEGATIVE_INFINITY
                    var uy2 = Float.NEGATIVE_INFINITY

                    for (idx in group) {
                        val d = sorted[idx]
                        if (d.xmin < ux1) ux1 = d.xmin
                        if (d.ymin < uy1) uy1 = d.ymin
                        if (d.xmax > ux2) ux2 = d.xmax
                        if (d.ymax > uy2) uy2 = d.ymax
                    }

                    for (j in sorted.indices) {
                        if (used[j]) continue

                        val cand = sorted[j]
                        val iou = boxIou(
                            ux1, uy1, ux2, uy2,
                            cand.xmin, cand.ymin, cand.xmax, cand.ymax
                        )
                        val ios = boxIos(
                            ux1, uy1, ux2, uy2,
                            cand.xmin, cand.ymin, cand.xmax, cand.ymax
                        )

                        if (iou >= MERGE_IOU_THR || ios >= MERGE_IOS_THR) {
                            group.add(j)
                            used[j] = true
                            changed = true
                        }
                    }
                }

                var mx1 = Float.POSITIVE_INFINITY
                var my1 = Float.POSITIVE_INFINITY
                var mx2 = Float.NEGATIVE_INFINITY
                var my2 = Float.NEGATIVE_INFINITY
                var bestScore = Float.NEGATIVE_INFINITY

                for (idx in group) {
                    val d = sorted[idx]
                    if (d.xmin < mx1) mx1 = d.xmin
                    if (d.ymin < my1) my1 = d.ymin
                    if (d.xmax > mx2) mx2 = d.xmax
                    if (d.ymax > my2) my2 = d.ymax
                    if (d.score > bestScore) bestScore = d.score
                }

                val left = mx1.coerceIn(0f, 1f)
                val top = my1.coerceIn(0f, 1f)
                val right = mx2.coerceIn(0f, 1f)
                val bottom = my2.coerceIn(0f, 1f)

                if (right > left && bottom > top) {
                    merged.add(
                        Detection(
                            xmin = left,
                            ymin = top,
                            xmax = right,
                            ymax = bottom,
                            label = cls,
                            score = if (bestScore.isFinite()) bestScore else 0f
                        )
                    )
                }
            }
        }

        return merged.sortedByDescending { it.score }
    }

    private fun boxIou(
        ax1: Float, ay1: Float, ax2: Float, ay2: Float,
        bx1: Float, by1: Float, bx2: Float, by2: Float
    ): Float {
        val x1 = max(ax1, bx1)
        val y1 = max(ay1, by1)
        val x2 = min(ax2, bx2)
        val y2 = min(ay2, by2)

        val iw = max(0f, x2 - x1)
        val ih = max(0f, y2 - y1)
        val inter = iw * ih

        val areaA = max(0f, ax2 - ax1) * max(0f, ay2 - ay1)
        val areaB = max(0f, bx2 - bx1) * max(0f, by2 - by1)
        val union = areaA + areaB - inter

        if (union <= 0f) return 0f
        return inter / union
    }

    private fun boxIos(
        ax1: Float, ay1: Float, ax2: Float, ay2: Float,
        bx1: Float, by1: Float, bx2: Float, by2: Float
    ): Float {
        val x1 = max(ax1, bx1)
        val y1 = max(ay1, by1)
        val x2 = min(ax2, bx2)
        val y2 = min(ay2, by2)

        val iw = max(0f, x2 - x1)
        val ih = max(0f, y2 - y1)
        val inter = iw * ih

        val areaA = max(0f, ax2 - ax1) * max(0f, ay2 - ay1)
        val areaB = max(0f, bx2 - bx1) * max(0f, by2 - by1)
        val smaller = max(1e-6f, min(areaA, areaB))

        return inter / smaller
    }





    private fun fillChwFromRgba8888(image: ImageProxy, out: FloatBuffer): Boolean {
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 0 || srcH <= 0) return false

        val plane = image.planes.firstOrNull() ?: return false
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride < 4) return false

        val planeSize = TARGET_W * TARGET_H
        val scaleX = srcW.toFloat() / TARGET_W.toFloat()
        val scaleY = srcH.toFloat() / TARGET_H.toFloat()

        if (blurKernel <= 0) {
            out.clear()

            for (y in 0 until TARGET_H) {
                val sy = ((y + 0.5f) * scaleY).toInt().coerceIn(0, srcH - 1)
                val rowStart = sy * rowStride

                for (x in 0 until TARGET_W) {
                    val sx = ((x + 0.5f) * scaleX).toInt().coerceIn(0, srcW - 1)
                    val off = rowStart + sx * pixelStride
                    val i = y * TARGET_W + x

                    val r = (buf.get(off).toInt() and 0xFF) / 255f
                    val g = (buf.get(off + 1).toInt() and 0xFF) / 255f
                    val b = (buf.get(off + 2).toInt() and 0xFF) / 255f

                    out.put(i, r)
                    out.put(planeSize + i, g)
                    out.put(2 * planeSize + i, b)
                }
            }

            out.rewind()
            return true
        }

        for (y in 0 until TARGET_H) {
            val sy = ((y + 0.5f) * scaleY).toInt().coerceIn(0, srcH - 1)
            val rowStart = sy * rowStride

            for (x in 0 until TARGET_W) {
                val sx = ((x + 0.5f) * scaleX).toInt().coerceIn(0, srcW - 1)
                val off = rowStart + sx * pixelStride
                val i = y * TARGET_W + x

                blurR[i] = (buf.get(off).toInt() and 0xFF) / 255f
                blurG[i] = (buf.get(off + 1).toInt() and 0xFF) / 255f
                blurB[i] = (buf.get(off + 2).toInt() and 0xFF) / 255f
            }
        }

        applyGaussianBlurInPlace(blurR, TARGET_W, TARGET_H, blurKernel)
        applyGaussianBlurInPlace(blurG, TARGET_W, TARGET_H, blurKernel)
        applyGaussianBlurInPlace(blurB, TARGET_W, TARGET_H, blurKernel)

        out.clear()
        for (i in 0 until planeSize) {
            out.put(i, blurR[i])
            out.put(planeSize + i, blurG[i])
            out.put(2 * planeSize + i, blurB[i])
        }
        out.rewind()
        return true
    }

    private fun applyGaussianBlurInPlace(
        data: FloatArray,
        width: Int,
        height: Int,
        kernelSize: Int
    ) {
        val kernel = gaussianKernel1D(kernelSize) ?: return
        val radius = kernel.size / 2

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    sum += data[row + sx] * kernel[k + radius]
                }
                blurTmp[row + x] = sum
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    sum += blurTmp[sy * width + x] * kernel[k + radius]
                }
                data[y * width + x] = sum
            }
        }
    }

    private fun gaussianKernel1D(kernelSize: Int): FloatArray? {
        return when (kernelSize) {
            3 -> floatArrayOf(
                1f / 4f,
                2f / 4f,
                1f / 4f
            )
            5 -> floatArrayOf(
                1f / 16f,
                4f / 16f,
                6f / 16f,
                4f / 16f,
                1f / 16f
            )
            7 -> floatArrayOf(
                1f / 64f,
                6f / 64f,
                15f / 64f,
                20f / 64f,
                15f / 64f,
                6f / 64f,
                1f / 64f
            )
            else -> null
        }
    }

    private fun normalizeClassIndex(raw: Int): Int {
        val n = classNames.size
        if (n == 0) return raw.coerceAtLeast(0)
        return when {
            raw in 0 until n -> raw
            (raw - 1) in 0 until n -> raw - 1
            else -> raw.coerceIn(0, n - 1)
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?){
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2002 && resultCode == RESULT_OK) {
            val path = data?.getStringExtra("mbtiles_path")
            if (path != null) {
                getSharedPreferences("cfg", MODE_PRIVATE)
                    .edit { putString("mbtiles_path", path) }
                Toast.makeText(this, "MBtiles fails iestatīts", Toast.LENGTH_SHORT).show()
            }
        }
    }

//šeit jāmaina izšķirtspas izmērs, ja grib lietot citu modeli
    companion object {
        private const val TARGET_W = 400
        private const val TARGET_H = 400
        private const val PREF_MAP_PROVIDER = "map_provider"
        private const val MAP_MB = "mbtiles"
        private const val MAP_GOOGLE = "google"
    }
}