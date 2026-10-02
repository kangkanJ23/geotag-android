package com.geostamp.camera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** One photo taken in the app, with its own geotag info. */
data class PhotoItem(
    val id: String,
    val file: String,
    var lat: Double?,
    var lng: Double?,
    var place: String,
    var address: String,
    var timeMillis: Long,
    var saved: Boolean = false
) {
    fun hasLocation() = lat != null && lng != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("file", file)
        put("lat", lat ?: JSONObject.NULL); put("lng", lng ?: JSONObject.NULL)
        put("place", place); put("address", address)
        put("time", timeMillis); put("saved", saved)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject) = PhotoItem(
            id = o.getString("id"),
            file = o.getString("file"),
            lat = if (o.isNull("lat")) null else o.getDouble("lat"),
            lng = if (o.isNull("lng")) null else o.getDouble("lng"),
            place = o.optString("place"),
            address = o.optString("address"),
            timeMillis = o.getLong("time"),
            saved = o.optBoolean("saved")
        )
    }
}

/**
 * The current shooting session. Photos and their info are kept in the app's private
 * storage, so nothing is lost if the app closes before you save.
 * All calls happen on the main thread.
 */
object Session {
    val items = mutableListOf<PhotoItem>()
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "session").apply { mkdirs() }
        dir = d
        val f = File(d, "session.json")
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val item = PhotoItem.fromJson(arr.getJSONObject(i))
                    if (File(item.file).exists()) items += item
                }
            } catch (_: Exception) {}
        }
    }

    fun newPhotoFile(): File = File(dir, "IMG_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.jpg")

    fun find(id: String?): PhotoItem? = items.firstOrNull { it.id == id }

    fun add(item: PhotoItem) {
        items += item
        persist()
    }

    fun remove(item: PhotoItem) {
        items.remove(item)
        File(item.file).delete()
        persist()
    }

    fun clear() {
        items.forEach { File(it.file).delete() }
        items.clear()
        persist()
    }

    fun persist() {
        val d = dir ?: return
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(d, "session.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(d, "session.json"))
    }
}

/** Stamp options shared by all photos. */
object Prefs {
    private const val NAME = "stamp"
    fun brand(ctx: Context): String = ctx.getSharedPreferences(NAME, 0).getString("brand", "") ?: ""
    fun showMap(ctx: Context): Boolean = ctx.getSharedPreferences(NAME, 0).getBoolean("map", true)
    fun set(ctx: Context, brand: String, showMap: Boolean) {
        ctx.getSharedPreferences(NAME, 0).edit().putString("brand", brand).putBoolean("map", showMap).apply()
    }
}

object Format {
    fun offsetMinutes(ms: Long): Int = TimeZone.getDefault().getOffset(ms) / 60000

    fun stampTime(ms: Long): String =
        SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.US).format(ms) +
            " GMT " + Stamper.formatOffset(offsetMinutes(ms))

    fun shortTime(ms: Long): String = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(ms)

    fun stampInfo(ctx: Context, item: PhotoItem) = StampInfo(
        lat = item.lat,
        lng = item.lng,
        place = item.place,
        address = item.address,
        timeLine = stampTime(item.timeMillis),
        brand = Prefs.brand(ctx),
        showMap = Prefs.showMap(ctx)
    )
}
