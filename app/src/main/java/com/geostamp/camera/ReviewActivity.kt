package com.geostamp.camera

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ReviewActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var btnSaveAll: MaterialButton
    private lateinit var adapter: PhotoAdapter
    private var saving = false

    private val importImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) importFromGallery(uris)
    }

    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) saveAll() else toast("Storage permission is needed to save on this Android version.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)
        Session.init(this)

        toolbar = findViewById(R.id.toolbar)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        btnSaveAll = findViewById(R.id.btnSaveAll)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.review_menu)
        toolbar.setOnMenuItemClickListener { mi ->
            if (saving) return@setOnMenuItemClickListener true
            when (mi.itemId) {
                R.id.action_shift -> shiftAllTimes()
                R.id.action_location_all -> placeForAll()
                R.id.action_settings -> stampSettings()
                R.id.action_import -> importImages.launch("image/*")
                R.id.action_clear -> confirmClear()
            }
            true
        }

        adapter = PhotoAdapter { item ->
            if (!saving) startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, item.id))
        }
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter

        findViewById<MaterialButton>(R.id.btnMore).setOnClickListener { finish() }
        btnSaveAll.setOnClickListener { onSaveAllClicked() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Session.items.sortBy { it.timeMillis }
        adapter.notifyDataSetChanged()
        val n = Session.items.size
        val unsaved = Session.items.count { !it.saved }
        toolbar.title = if (n == 1) "1 photo" else "$n photos"
        toolbar.subtitle = when {
            n == 0 -> null
            unsaved == 0 -> "All saved to gallery"
            unsaved == n -> "Not saved yet"
            else -> "$unsaved not saved yet"
        }
        empty.visibility = if (n == 0) View.VISIBLE else View.GONE
        btnSaveAll.isEnabled = n > 0 && !saving
        btnSaveAll.text = if (unsaved in 1 until n) "Save $unsaved to gallery" else "Save all to gallery"
    }

    // ---------- Bulk edits ----------

    private fun shiftAllTimes() {
        val items = Session.items
        if (items.isEmpty()) return
        val first = items.minOf { it.timeMillis }
        val cal = Calendar.getInstance().apply { timeInMillis = first }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                val delta = cal.timeInMillis - first
                items.forEach { it.timeMillis += delta; it.saved = false }
                Session.persist()
                refresh()
                toast("Times updated. Gaps between photos are kept.")
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).apply {
                setTitle("Time of the first photo")
            }.show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle("Date of the first photo")
        }.show()
    }

    private fun placeForAll() {
        val items = Session.items
        if (items.isEmpty()) return
        val ref = items.first()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val place = field(box, "Place (city, state, country)", ref.place)
        val address = field(box, "Address", ref.address)
        MaterialAlertDialogBuilder(this)
            .setTitle("Place for all photos")
            .setMessage("Each photo keeps its own GPS coordinates. Only the place and address text change.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply to all") { _, _ ->
                items.forEach {
                    it.place = place.text.toString().trim()
                    it.address = address.text.toString().trim()
                    it.saved = false
                }
                Session.persist()
                refresh()
            }
            .show()
    }

    private fun stampSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val brand = field(box, "Brand name (optional)", Prefs.brand(this))
        val map = CheckBox(this).apply { text = "Show map tile"; isChecked = Prefs.showMap(this@ReviewActivity) }
        box.addView(map)
        MaterialAlertDialogBuilder(this)
            .setTitle("Stamp settings")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                Prefs.set(this, brand.text.toString().trim(), map.isChecked)
                Session.items.forEach { it.saved = false }
                Session.persist()
                refresh()
            }
            .show()
    }

    private fun field(parent: LinearLayout, hint: String, value: String): EditText {
        val til = TextInputLayout(this)
        til.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        til.hint = hint
        val et = TextInputEditText(til.context)
        et.setText(value)
        til.addView(et)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
        parent.addView(til, lp)
        return et
    }

    private fun confirmClear() {
        if (Session.items.isEmpty()) return
        val unsaved = Session.items.count { !it.saved }
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove all photos?")
            .setMessage(
                if (unsaved > 0) "$unsaved photos are not saved to the gallery yet and will be lost."
                else "They are already saved in your gallery. This only clears them from the app."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> Session.clear(); refresh() }
            .show()
    }

    // ---------- Import ----------

    private fun importFromGallery(uris: List<Uri>) {
        toast("Adding ${uris.size} photos…")
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(it) } }
            added.forEach { item ->
                Session.add(item)
                val lat = item.lat
                val lng = item.lng
                if (lat != null && lng != null) {
                    Geo.reverse(this@ReviewActivity, lat, lng) { p, a ->
                        if (item.place.isBlank()) { item.place = p; item.address = a; Session.persist(); refresh() }
                    }
                }
            }
            refresh()
            val noGps = added.count { !it.hasLocation() }
            if (noGps > 0) toast("$noGps photos had no GPS data. Tap them to add a place.")
        }
    }

    private fun copyIn(uri: Uri): PhotoItem? {
        val out = Session.newPhotoFile()
        var src = uri
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            try { src = MediaStore.setRequireOriginal(uri) } catch (_: Exception) {}
        }
        val ok = copy(src, out) || copy(uri, out)
        if (!ok || ImageUtils.decode(out.absolutePath, 64) == null) { out.delete(); return null }

        var lat: Double? = null
        var lng: Double? = null
        var time = System.currentTimeMillis()
        try {
            val exif = ExifInterface(out.absolutePath)
            exif.latLong?.let { if (it[0] != 0.0 || it[1] != 0.0) { lat = it[0]; lng = it[1] } }
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.let { d -> time = d.time }
            }
        } catch (_: Exception) {}
        return PhotoItem(PhotoItem.newId(), out.absolutePath, lat, lng, "", "", time)
    }

    private fun copy(uri: Uri, out: File): Boolean = try {
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } != null
    } catch (_: Exception) { false }

    // ---------- Save ----------

    private fun onSaveAllClicked() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else saveAll()
    }

    private fun saveAll() {
        val todo = Session.items.filter { !it.saved }.ifEmpty { Session.items.toList() }
        if (todo.isEmpty()) return
        saving = true
        btnSaveAll.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = todo.size
        progress.progress = 0
        lifecycleScope.launch {
            var done = 0
            var failed = 0
            for (item in todo) {
                btnSaveAll.text = "Saving ${done + failed + 1} of ${todo.size}…"
                val info = Format.stampInfo(this@ReviewActivity, item)
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val lat = item.lat
                        val lng = item.lng
                        val tile = if (info.showMap && lat != null && lng != null)
                            MapTiles.terrain(this@ReviewActivity, lat, lng) else null
                        Exporter.save(this@ReviewActivity, item, info.copy(mapTile = tile))
                        true
                    } catch (_: Throwable) { false }
                }
                if (ok) { item.saved = true; done++ } else failed++
                progress.setProgressCompat(done + failed, true)
            }
            Session.persist()
            saving = false
            progress.visibility = View.GONE
            refresh()
            showSavedDialog(done, failed)
        }
    }

    private fun showSavedDialog(done: Int, failed: Int) {
        val msg = buildString {
            append("$done photos saved to Pictures/GeoStamp.")
            if (failed > 0) append(" $failed couldn't be saved. Check storage space and try again.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Saved to gallery")
            .setMessage(msg + "\n\nRemove saved photos from the app to start a fresh batch? They stay in your gallery.")
            .setNegativeButton("Keep here", null)
            .setPositiveButton("Start fresh") { _, _ ->
                Session.items.filter { it.saved }.forEach { Session.remove(it) }
                refresh()
                if (Session.items.isEmpty()) finish()
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Grid ----------

    private inner class PhotoAdapter(val onClick: (PhotoItem) -> Unit) : RecyclerView.Adapter<PhotoAdapter.VH>() {

        private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.image)
            val time: TextView = v.findViewById(R.id.time)
            val place: TextView = v.findViewById(R.id.place)
            val saved: TextView = v.findViewById(R.id.savedChip)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

        override fun getItemCount() = Session.items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = Session.items[position]
            h.time.text = Format.shortTime(item.timeMillis)
            h.place.text = when {
                item.place.isNotBlank() -> item.place
                item.hasLocation() -> String.format(Locale.US, "%.5f, %.5f", item.lat, item.lng)
                else -> "No location. Tap to add."
            }
            h.saved.visibility = if (item.saved) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onClick(item) }

            h.image.tag = item.id
            val cached = cache.get(item.id)
            if (cached != null) {
                h.image.setImageBitmap(cached)
            } else {
                h.image.setImageDrawable(null)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { ImageUtils.decode(item.file, 500) } ?: return@launch
                    cache.put(item.id, bmp)
                    if (h.image.tag == item.id) h.image.setImageBitmap(bmp)
                }
            }
        }
    }
}
