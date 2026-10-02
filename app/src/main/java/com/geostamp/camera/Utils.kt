package com.geostamp.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max

object ImageUtils {

    /** Decode a JPEG file, scaled so the longest side is at most [maxSide], and turned upright. */
    fun decode(path: String, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longest = max(bmp.width, bmp.height)
            if (longest > maxSide) {
                val k = maxSide.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            }
            val rotation = try { ExifInterface(path).rotationDegrees } catch (_: Exception) { 0 }
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

/** Turns coordinates into a place name and address, with a small cache. */
object Geo {
    private val cache = HashMap<String, Pair<String, String>>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun reverse(ctx: Context, lat: Double, lng: Double, done: (place: String, address: String) -> Unit) {
        val key = String.format(Locale.US, "%.4f,%.4f", lat, lng)
        cache[key]?.let { done(it.first, it.second); return }
        if (!Geocoder.isPresent()) return
        val appCtx = ctx.applicationContext
        io.execute {
            val list: List<Address>? = try {
                @Suppress("DEPRECATION")
                Geocoder(appCtx, Locale.getDefault()).getFromLocation(lat, lng, 1)
            } catch (_: Exception) { null }
            val a = list?.firstOrNull() ?: return@execute
            val place = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                .distinct().joinToString(", ")
            val address = a.getAddressLine(0) ?: ""
            main.post {
                cache[key] = Pair(place, address)
                done(place, address)
            }
        }
    }
}

/** Stamps a photo at full size and saves it to Pictures/GeoStamp with matching EXIF data. */
object Exporter {

    fun save(ctx: Context, item: PhotoItem, info: StampInfo): String {
        val src = ImageUtils.decode(item.file, 4096) ?: throw IllegalStateException("decode failed")
        val stamped = Stamper.stamp(src, info)
        val tmp = File(ctx.cacheDir, "export_${item.id}.jpg")
        FileOutputStream(tmp).use { stamped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        stamped.recycle()

        val t = item.timeMillis
        val local = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(t)
        val utc = TimeZone.getTimeZone("UTC")
        val utcDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utc }.format(t)
        val utcTime = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = utc }.format(t)
        val off = Stamper.formatOffset(Format.offsetMinutes(t))

        ExifInterface(tmp.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME, local)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, local)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, local)
            setAttribute(ExifInterface.TAG_OFFSET_TIME, off)
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, off)
            setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, off)
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            val lat = item.lat
            val lng = item.lng
            if (lat != null && lng != null) {
                setLatLong(lat, lng)
                setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utcDate)
                setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utcTime)
            }
            saveAttributes()
        }

        val name = "GeoStamp_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(t) +
            "_" + item.id.take(4) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, t)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoStamp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("insert failed")
        resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("open failed")
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        tmp.delete()
        return name
    }
}
