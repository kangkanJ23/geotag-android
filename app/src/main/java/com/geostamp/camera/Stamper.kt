package com.geostamp.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class StampInfo(
    val lat: Double?,
    val lng: Double?,
    val place: String,
    val address: String,
    val timeLine: String,
    val brand: String,
    val showMap: Boolean,
    val mapTile: Bitmap? = null
)

object Stamper {

    fun stamp(src: Bitmap, info: StampInfo): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        val scale = min(w, h * 1.3f) / 1000f
        val m = 18f * scale
        val hasLoc = info.lat != null && info.lng != null
        val showMap = info.showMap && hasLoc
        val mapS = if (showMap) 150f * scale else 0f
        val gap = if (showMap) 10f * scale else 0f
        val px = m + mapS + gap
        val pw = w - px - m
        val ip = 14f * scale
        val maxW = pw - ip * 2

        val titlePaint = textPaint(24f * scale, Typeface.create("sans-serif-medium", Typeface.NORMAL))
        val bodyPaint = textPaint(16.5f * scale, Typeface.create("sans-serif", Typeface.NORMAL))
        val lh = bodyPaint.textSize * 1.32f
        val titleH = titlePaint.textSize * 1.3f

        val lines = mutableListOf<String>()
        if (info.address.isNotBlank()) lines += wrap(bodyPaint, info.address, maxW, 2)
        if (hasLoc) lines += String.format(Locale.US, "Lat %.6f°  Long %.6f°", info.lat, info.lng)
        if (info.timeLine.isNotBlank()) lines += wrap(bodyPaint, info.timeLine, maxW, 1)

        val hasTitle = info.place.isNotBlank()
        val contentH = (if (hasTitle) titleH else 0f) + lines.size * lh
        val panelH = maxOf(mapS, ip * 2 + contentH)
        val py = h - m - panelH

        if (showMap) drawMap(c, m, py + panelH - mapS, mapS, info.lat!!, info.lng!!, info.mapTile)

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
        val rr = 8f * scale
        c.drawRoundRect(RectF(px, py, px + pw, py + panelH), rr, rr, panelPaint)

        // Brand pill above the panel
        if (info.brand.isNotBlank()) {
            val bp = textPaint(13f * scale, Typeface.create("sans-serif-medium", Typeface.NORMAL))
            val pad = 8f * scale
            val bw = bp.measureText(info.brand) + pad * 2
            val bh = 24f * scale
            val bx = px + pw - bw
            val by = py - bh - 6f * scale
            c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 5f * scale, 5f * scale, panelPaint)
            val fm = bp.fontMetrics
            c.drawText(info.brand, bx + pad, by + bh / 2 - (fm.ascent + fm.descent) / 2, bp)
        }

        // Vertically centre the text block in the panel
        var ty = py + (panelH - contentH) / 2
        if (hasTitle) {
            val line = wrap(titlePaint, info.place, maxW, 1).first()
            c.drawText(line, px + ip, ty - titlePaint.fontMetrics.ascent, titlePaint)
            ty += titleH
        }
        lines.forEachIndexed { i, l ->
            c.drawText(l, px + ip, ty + i * lh - bodyPaint.fontMetrics.ascent, bodyPaint)
        }
        return out
    }

    private fun textPaint(size: Float, tf: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = tf
    }

    private fun wrap(p: Paint, text: String, maxW: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in words) {
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(t) > maxW && cur.isNotEmpty()) {
                lines += cur; cur = word
            } else cur = t
        }
        if (cur.isNotEmpty()) lines += cur
        if (lines.isEmpty()) return listOf("")
        if (lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            var last = kept[maxLines - 1]
            while (last.isNotEmpty() && p.measureText("$last…") > maxW) last = last.dropLast(1)
            kept[maxLines - 1] = "$last…"
            return kept
        }
        return lines
    }

    private fun drawMap(c: Canvas, x: Float, y: Float, s: Float, lat: Double, lng: Double, tile: Bitmap?) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.save()
        c.clipRect(x, y, x + s, y + s)

        if (tile != null) {
            c.drawBitmap(tile, null, RectF(x, y, x + s, y + s), p)
        } else {
            drawTerrainSketch(c, x, y, s, lat, lng)
        }

        // Pin
        val cx = x + s / 2
        val cy = y + s / 2
        val pr = s * 0.085f
        p.style = Paint.Style.FILL
        p.color = Color.argb(64, 0, 0, 0)
        c.drawOval(RectF(cx - pr * 0.55f, cy - pr * 0.05f, cx + pr * 0.55f, cy + pr * 0.35f), p)
        val headY = cy - pr * 1.6f
        val tail = Path().apply {
            moveTo(cx - pr * 0.8f, headY + pr * 0.6f)
            lineTo(cx, cy)
            lineTo(cx + pr * 0.8f, headY + pr * 0.6f)
            close()
        }
        p.color = Color.rgb(229, 57, 53)
        c.drawPath(tail, p)
        c.drawCircle(cx, headY, pr, p)
        p.color = Color.rgb(127, 29, 29)
        c.drawCircle(cx, headY, pr * 0.38f, p)

        // Data credit required by the map provider
        if (tile != null) {
            val credit = "© OpenStreetMap · OpenTopoMap"
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = s * 0.052f
                color = Color.rgb(40, 40, 40)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val tw = tp.measureText(credit)
            val bh = tp.textSize * 1.35f
            p.color = Color.argb(190, 255, 255, 255)
            c.drawRect(x + s - tw - s * 0.04f, y + s - bh, x + s, y + s, p)
            c.drawText(credit, x + s - tw - s * 0.02f, y + s - bh * 0.28f, tp)
        }

        c.restore()
        p.style = Paint.Style.STROKE
        p.color = Color.argb(230, 255, 255, 255)
        p.strokeWidth = maxOf(2f, s * 0.012f)
        c.drawRect(x, y, x + s, y + s, p)
    }

    // Simple seeded random so the same spot always draws the same sketch
    private class Rng(seed: Long) {
        private var st = seed xor 0x5DEECE66DL
        fun next(): Float {
            st = st * 6364136223846793005L + 1442695040888963407L
            return ((st ushr 33) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
        }
    }

    /** Offline fallback: a terrain-style sketch with shaded hills and contour lines. */
    private fun drawTerrainSketch(c: Canvas, x: Float, y: Float, s: Float, lat: Double, lng: Double) {
        val r = Rng((lat * 1e4).roundToInt().toLong() * 7919L xor (lng * 1e4).roundToInt().toLong() * 104729L)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(226, 232, 206)
        c.drawRect(x, y, x + s, y + s, p)

        // Forest patches
        repeat(6) {
            p.color = Color.argb(150, 178, 210, 160)
            val rx = s * (0.12f + r.next() * 0.2f)
            val cx = x + r.next() * s
            val cy = y + r.next() * s
            c.drawOval(RectF(cx - rx, cy - rx * 0.7f, cx + rx, cy + rx * 0.7f), p)
        }
        // Hills as nested contour rings
        p.style = Paint.Style.STROKE
        repeat(3) {
            val hx = x + r.next() * s
            val hy = y + r.next() * s
            val base = s * (0.18f + r.next() * 0.25f)
            val tilt = r.next() * 0.5f + 0.6f
            for (k in 0 until 6) {
                val rad = base * (1f - k * 0.15f)
                p.strokeWidth = if (k % 3 == 0) s * 0.008f else s * 0.004f
                p.color = Color.argb(170, 168, 128, 84)
                c.drawOval(RectF(hx - rad, hy - rad * tilt, hx + rad, hy + rad * tilt), p)
            }
        }
        // River
        p.color = Color.rgb(140, 190, 220)
        p.strokeWidth = s * 0.025f
        p.strokeCap = Paint.Cap.ROUND
        val path = Path()
        val ry = y + s * (0.2f + r.next() * 0.6f)
        path.moveTo(x - 10, ry)
        path.cubicTo(x + s * 0.3f, ry + (r.next() - 0.5f) * s * 0.5f, x + s * 0.6f, ry + (r.next() - 0.5f) * s * 0.5f, x + s + 10, ry + (r.next() - 0.5f) * s * 0.3f)
        c.drawPath(path, p)
        // Roads
        p.color = Color.WHITE
        p.strokeWidth = s * 0.022f
        repeat(2) {
            val yy = y + r.next() * s
            c.drawLine(x - 10, yy, x + s + 10, yy + (r.next() - 0.5f) * s * 0.6f, p)
        }
        p.color = Color.rgb(246, 200, 120)
        p.strokeWidth = s * 0.03f
        val xx = x + r.next() * s
        c.drawLine(xx, y - 10, xx + (r.next() - 0.5f) * s * 0.6f, y + s + 10, p)
        p.style = Paint.Style.FILL
    }

    fun formatOffset(minutes: Int): String {
        val sign = if (minutes < 0) "-" else "+"
        val a = abs(minutes)
        return String.format(Locale.US, "%s%02d:%02d", sign, a / 60, a % 60)
    }
}
