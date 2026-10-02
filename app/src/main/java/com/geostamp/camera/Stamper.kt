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
    val showMap: Boolean
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
        val panelH = 150f * scale
        val mapS = if (showMap) panelH else 0f
        val gap = if (showMap) 10f * scale else 0f
        val px = m + mapS + gap
        val py = h - m - panelH
        val pw = w - px - m

        if (showMap) drawMap(c, m, py, mapS, info.lat!!, info.lng!!)

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

        val ip = 14f * scale
        val maxW = pw - ip * 2
        var ty = py + ip

        val titlePaint = textPaint(24f * scale, Typeface.create("sans-serif-medium", Typeface.NORMAL))
        val bodyPaint = textPaint(16.5f * scale, Typeface.create("sans-serif", Typeface.NORMAL))
        val lh = bodyPaint.textSize * 1.32f

        if (info.place.isNotBlank()) {
            val line = wrap(titlePaint, info.place, maxW, 1).first()
            c.drawText(line, px + ip, ty - titlePaint.fontMetrics.ascent, titlePaint)
            ty += titlePaint.textSize * 1.3f
        }

        val lines = mutableListOf<String>()
        if (info.address.isNotBlank()) lines += wrap(bodyPaint, info.address, maxW, 2)
        if (hasLoc) {
            lines += String.format(Locale.US, "Lat %.6f°  Long %.6f°", info.lat, info.lng)
        }
        if (info.timeLine.isNotBlank()) lines += info.timeLine

        val room = maxOf(1, ((py + panelH - ip - ty) / lh).toInt())
        lines.takeLast(room).forEachIndexed { i, l ->
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

    // Simple seeded random so the same spot always draws the same tile
    private class Rng(seed: Long) {
        private var s = seed xor 0x5DEECE66DL
        fun next(): Float {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
        }
    }

    private fun drawMap(c: Canvas, x: Float, y: Float, s: Float, lat: Double, lng: Double) {
        val r = Rng((lat * 1e4).roundToInt().toLong() * 7919L xor (lng * 1e4).roundToInt().toLong() * 104729L)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.clipRect(x, y, x + s, y + s)

        p.color = Color.rgb(239, 235, 227)
        c.drawRect(x, y, x + s, y + s, p)

        repeat(14) {
            p.color = if (r.next() < 0.25f) Color.rgb(207, 230, 196) else Color.rgb(227, 222, 212)
            val bw = s * (0.12f + r.next() * 0.25f)
            val bh = s * (0.1f + r.next() * 0.22f)
            val bx = x + r.next() * s
            val by = y + r.next() * s
            c.drawRect(bx, by, bx + bw, by + bh, p)
        }
        if (r.next() < 0.6f) {
            p.color = Color.rgb(170, 211, 223)
            val wx = x + if (r.next() < 0.5f) 0f else s * 0.65f
            val wy = y + r.next() * s * 0.5f + s * 0.3f
            c.drawOval(RectF(wx - s * 0.35f, wy - s * 0.25f, wx + s * 0.35f, wy + s * 0.25f), p)
        }

        fun road(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, col: Int) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.rgb(201, 194, 180)
            p.strokeWidth = width + s * 0.012f
            c.drawLine(x1, y1, x2, y2, p)
            p.color = col
            p.strokeWidth = width
            c.drawLine(x1, y1, x2, y2, p)
            p.style = Paint.Style.FILL
        }
        repeat(5) {
            val yy = y + r.next() * s
            road(x - 10, yy, x + s + 10, yy + (r.next() - 0.5f) * s * 0.5f, s * 0.03f, Color.WHITE)
        }
        repeat(4) {
            val xx = x + r.next() * s
            road(xx, y - 10, xx + (r.next() - 0.5f) * s * 0.5f, y + s + 10, s * 0.03f, Color.WHITE)
        }
        road(x - 10, y + s * (0.3f + r.next() * 0.4f), x + s + 10, y + s * (0.3f + r.next() * 0.4f), s * 0.05f, Color.rgb(251, 211, 141))

        // Pin
        val cx = x + s / 2
        val cy = y + s / 2
        val pr = s * 0.085f
        p.color = Color.argb(64, 0, 0, 0)
        c.drawOval(RectF(cx - pr * 0.55f, cy + pr * 0.15f - pr * 0.2f, cx + pr * 0.55f, cy + pr * 0.15f + pr * 0.2f), p)
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

        c.restore()
        p.style = Paint.Style.STROKE
        p.color = Color.argb(230, 255, 255, 255)
        p.strokeWidth = maxOf(2f, s * 0.012f)
        c.drawRect(x, y, x + s, y + s, p)
    }

    fun formatOffset(minutes: Int): String {
        val sign = if (minutes < 0) "-" else "+"
        val a = abs(minutes)
        return String.format(Locale.US, "%s%02d:%02d", sign, a / 60, a % 60)
    }
}
