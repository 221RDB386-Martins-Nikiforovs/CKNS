package com.example.ckns

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.RED
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val paintRecBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val paintRecDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val paintLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    private var dets: List<Detection> = emptyList()
    private var labelsFromCaller: List<String> = emptyList()
    private var lastHitSavedAt: Long = 0L

    fun setDetections(d: List<Detection>, classNames: List<String>) {
        labelsFromCaller = classNames
        setDetections(d)
    }
    fun setDetections(d: List<Detection>) {
        dets = d

        if (TripState.isRecording && d.isNotEmpty()) {
            val tripId = (TripState.currentTripId?.toString()
                ?: TripStorage.findActiveTripId(context)
                ?: TripStorage.lastTripId(context))

            if (tripId != null) {
                // agregē vienā mapā (nevis increment par katru detection)
                val counts = HashMap<Int, Int>()
                for (det in d) {
                    counts[det.label] = (counts[det.label] ?: 0) + 1
                }
                TripObjectsCounter.incrementBatch(context, tripId, counts)

                // hitus rakstām retāk un TripObjectsCounter tgd raksta async
                val p = TripState.lastPoint
                val now = System.currentTimeMillis()
                if (p != null && now - lastHitSavedAt > 800) {
                    val best = d.maxByOrNull { it.score }!!
                    TripObjectsCounter.appendHit(
                        context,
                        tripId,
                        TripObjectHit(ts = now, lat = p.lat, lon = p.lon, label = best.label)
                    )
                    lastHitSavedAt = now
                }
            }
        }

        postInvalidateOnAnimation()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (TripState.isRecording) {
            val pad = 24f
            val topPadding = 190f
            val text = "brauciens tiek ierakstīts"
            val tw = paintText.measureText(text)
            val th = paintText.fontMetrics.run { bottom - top }
            val bx = max(pad, (w - tw) * 0.5f - (20f + pad / 2))
            val by = pad + topPadding
            val bw = tw + 40f + pad
            val bh = th + 24f + pad
            val rect = RectF(bx, by, bx + bw, by + bh)
            canvas.drawRoundRect(rect, 32f, 32f, paintRecBg)
            val cy = by + bh * 0.5f
            canvas.drawCircle(bx + 16f + pad / 2, cy, 8f, paintRecDot)
            canvas.drawText(text, bx + 32f + pad / 2, cy + th * 0.35f - 2f, paintText)
        }

        for (d in dets) {
            val l = d.xmin * w
            val t = d.ymin * h
            val r = d.xmax * w
            val b = d.ymax * h
            canvas.drawRect(l, t, r, b, paintBox)

            val idx = d.label
            val rawName = labelsFromCaller.getOrNull(idx)
            val labelName = if (rawName.isNullOrBlank() || rawName == "?") idx.toString() else rawName

            val text = "$labelName ${"%.2f".format(d.score)}"
            val pad = 6f
            val tw = paintText.measureText(text)
            val fontH = paintText.fontMetrics.run { bottom - top }
            val rect = RectF(l, max(0f, t - fontH - 2 * pad), l + tw + 2 * pad, t)
            canvas.drawRoundRect(rect, 10f, 10f, paintLabelBg)
            canvas.drawText(text, l + pad, t - pad, paintText)
        }
    }
}

data class Detection(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val label: Int,
    val score: Float)