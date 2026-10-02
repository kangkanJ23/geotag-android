package com.geostamp.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Builds a real terrain map tile (OpenTopoMap, based on OpenStreetMap and SRTM elevation data)
 * centred on a point. Tiles are cached on disk. Call from a background thread.
 */
object MapTiles {
    private const val ZOOM = 14
    private const val TILE = 256
    private const val OUT = 256
    private val subdomains = listOf("a", "b", "c")

    fun terrain(ctx: Context, lat: Double, lng: Double): Bitmap? {
        val n = 1 shl ZOOM
        val worldX = (lng + 180.0) / 360.0 * n * TILE
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE

        val left = worldX - OUT / 2.0
        val top = worldY - OUT / 2.0
        val out = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(239, 235, 227))

        val tx0 = floor(left / TILE).toInt()
        val ty0 = floor(top / TILE).toInt()
        val tx1 = floor((left + OUT - 1) / TILE).toInt()
        val ty1 = floor((top + OUT - 1) / TILE).toInt()
        var drawn = 0
        for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            for (tx in tx0..tx1) {
                val wx = ((tx % n) + n) % n
                val tile = load(ctx, wx, ty) ?: continue
                canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
                tile.recycle()
                drawn++
            }
        }
        return if (drawn > 0) out else null
    }

    private fun load(ctx: Context, x: Int, y: Int): Bitmap? {
        val dir = File(ctx.cacheDir, "tiles/$ZOOM/$x").apply { mkdirs() }
        val f = File(dir, "$y.png")
        if (!f.exists() || f.length() == 0L) {
            val sub = subdomains[(x + y) % subdomains.size]
            try {
                val conn = (URL("https://$sub.tile.opentopomap.org/$ZOOM/$x/$y.png").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "GeoStamp/2.1 (Android photo stamp app)")
                }
                try {
                    if (conn.responseCode != 200) return null
                    val tmp = File(dir, "$y.tmp")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(f)
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                return null
            }
        }
        return BitmapFactory.decodeFile(f.absolutePath)
    }
}
