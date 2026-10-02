package com.geostamp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var emptyText: TextView
    private lateinit var locStatus: TextView
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etPlace: EditText
    private lateinit var etAddress: EditText
    private lateinit var etBrand: EditText
    private lateinit var cbMap: CheckBox
    private lateinit var etWeather: EditText
    private lateinit var cbWeather: CheckBox
    private lateinit var weatherStatus: TextView
    private lateinit var btnDate: MaterialButton
    private lateinit var btnTime: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var tzText: TextView
    private lateinit var msg: TextView

    private var source: Bitmap? = null
    private var previewSource: Bitmap? = null
    private var fileBase = "photo"
    private var captureUri: Uri? = null
    private val cal: Calendar = Calendar.getInstance()

    private val handler = Handler(Looper.getMainLooper())
    private val renderRunnable = Runnable { renderPreview() }
    private val weatherRunnable = Runnable { fetchWeather() }
    private var weatherRequest = 0

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) loadImage(uri, fromCamera = true)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri, fromCamera = false)
    }

    private val locationPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { fetchDeviceLocation() }

    private val storagePerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) save() else msg.text = "Storage permission is needed to save on this Android version."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        emptyText = findViewById(R.id.emptyText)
        locStatus = findViewById(R.id.locStatus)
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etPlace = findViewById(R.id.etPlace)
        etAddress = findViewById(R.id.etAddress)
        etBrand = findViewById(R.id.etBrand)
        cbMap = findViewById(R.id.cbMap)
        etWeather = findViewById(R.id.etWeather)
        cbWeather = findViewById(R.id.cbWeather)
        weatherStatus = findViewById(R.id.weatherStatus)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        btnSave = findViewById(R.id.btnSave)
        tzText = findViewById(R.id.tzText)
        msg = findViewById(R.id.msg)

        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<MaterialButton>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }
        findViewById<MaterialButton>(R.id.btnLocate).setOnClickListener { requestLocation() }
        btnDate.setOnClickListener { pickDate() }
        btnTime.setOnClickListener { pickTime() }
        btnSave.setOnClickListener { onSaveClicked() }

        listOf(etLat, etLng, etPlace, etAddress, etBrand, etWeather).forEach { it.doAfterTextChanged { scheduleRender() } }
        listOf(etLat, etLng).forEach { it.doAfterTextChanged { scheduleWeather() } }
        cbMap.setOnCheckedChangeListener { _, _ -> scheduleRender() }
        cbWeather.setOnCheckedChangeListener { _, _ -> scheduleRender() }

        updateDateTimeButtons()
        requestLocation()
    }

    // ---------- Photo input ----------

    private fun openCamera() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        captureUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            msg.text = "No camera app found on this phone."
        }
    }

    private fun loadImage(uri: Uri, fromCamera: Boolean) {
        msg.text = "Loading photo…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { decode(uri, fromCamera) }
            if (result == null) {
                msg.text = "This photo couldn't be opened. Try a JPG photo."
                return@launch
            }
            source = result.bitmap
            previewSource = scaleDown(result.bitmap, 1400)
            fileBase = if (fromCamera) "GeoStamp" else (result.name ?: "photo")
            msg.text = ""
            emptyText.visibility = View.GONE
            btnSave.isEnabled = true

            if (fromCamera) {
                cal.timeInMillis = System.currentTimeMillis()
                updateDateTimeButtons()
                requestLocation()
            } else {
                result.taken?.let { cal.timeInMillis = it; updateDateTimeButtons() }
                val ll = result.latLng
                if (ll != null) {
                    setLocation(ll.first, ll.second, "Location found in photo")
                } else {
                    locStatus.text = "No GPS in this photo. Using your current location."
                    requestLocation()
                }
            }
            renderPreview()
        }
    }

    private data class Decoded(val bitmap: Bitmap, val latLng: Pair<Double, Double>?, val taken: Long?, val name: String?)

    private fun decode(uri: Uri, fromCamera: Boolean): Decoded? {
        return try {
            var readUri = uri
            if (!fromCamera && Build.VERSION.SDK_INT >= 29 &&
                hasPerm(Manifest.permission.ACCESS_MEDIA_LOCATION) &&
                uri.authority == MediaStore.AUTHORITY
            ) {
                try { readUri = MediaStore.setRequireOriginal(uri) } catch (_: Exception) {}
            }

            var exif: ExifInterface? = null
            try {
                contentResolver.openInputStream(readUri)?.use { exif = ExifInterface(it) }
            } catch (_: Exception) {
                contentResolver.openInputStream(uri)?.use { exif = ExifInterface(it) }
            }

            val latLng = exif?.latLong?.let { arr ->
                if (arr[0] == 0.0 && arr[1] == 0.0) null else Pair(arr[0], arr[1])
            }
            val taken = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                try { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time } catch (_: Exception) { null }
            }
            val rotation = exif?.rotationDegrees ?: 0

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            if (rotation != 0) {
                val mtx = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                ?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.take(40)
            Decoded(bmp, latLng, taken, name)
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val k = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    // ---------- Location ----------

    private fun requestLocation() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 29) perms += Manifest.permission.ACCESS_MEDIA_LOCATION
        if (perms.take(2).any { hasPerm(it) }) fetchDeviceLocation()
        else locationPerms.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun fetchDeviceLocation() {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            locStatus.text = "Location permission is off. Turn it on in Settings, or type the coordinates."
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            locStatus.text = "Location is turned off on this phone. Turn it on and tap \"Use my current location\"."
            return
        }
        locStatus.text = "Finding your location…"

        // Quick fill from the last known fix, then refine with a fresh one
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null) setLocation(last.latitude, last.longitude, "Approximate location (updating…)")

        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, mainExecutor) { loc: Location? ->
                if (loc != null) setLocation(loc.latitude, loc.longitude, "Location from GPS")
                else if (last == null) locStatus.text = "Couldn't get a GPS fix. Try outdoors, or type the coordinates."
            }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    setLocation(location.latitude, location.longitude, "Location from GPS")
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }, Looper.getMainLooper())
        }
    }

    private fun setLocation(lat: Double, lng: Double, status: String) {
        locStatus.text = status
        etLat.setText(String.format(Locale.US, "%.6f", lat))
        etLng.setText(String.format(Locale.US, "%.6f", lng))
        reverseGeocode(lat, lng)
    }

    private fun reverseGeocode(lat: Double, lng: Double) {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(this, Locale.getDefault())
        val applyResult: (List<Address>?) -> Unit = { list ->
            val a = list?.firstOrNull()
            if (a != null) runOnUiThread {
                val place = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                    .distinct().joinToString(", ")
                if (place.isNotBlank()) etPlace.setText(place)
                a.getAddressLine(0)?.let { etAddress.setText(it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = applyResult(addresses)
                override fun onError(errorMessage: String?) {}
            })
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val list = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)
                } catch (_: Exception) { null }
                applyResult(list)
            }
        }
    }

    // ---------- Date and time ----------

    private fun pickDate() {
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            updateDateTimeButtons(); scheduleRender()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, min ->
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0)
            updateDateTimeButtons(); scheduleRender()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }

    private fun offsetMinutes(): Int = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000

    private fun updateDateTimeButtons() {
        btnDate.text = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(cal.time)
        btnTime.text = SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
        tzText.text = "Time zone: ${TimeZone.getDefault().id} (GMT ${Stamper.formatOffset(offsetMinutes())})"
        scheduleWeather()
    }

    // ---------- Weather ----------

    private fun scheduleWeather() {
        handler.removeCallbacks(weatherRunnable)
        handler.postDelayed(weatherRunnable, 800)
    }

    private fun fetchWeather() {
        val lat = etLat.text.toString().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lng = etLng.text.toString().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        if (lat == null || lng == null) return
        val at = cal.timeInMillis
        val id = ++weatherRequest
        weatherStatus.text = "Getting weather for this place and time…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Weather.fetch(lat, lng, at) } catch (_: Exception) { null }
            }
            if (id != weatherRequest) return@launch
            if (result != null) {
                etWeather.setText(result.text)
                weatherStatus.text = "Weather for the chosen date and time"
            } else {
                weatherStatus.text = if (at > System.currentTimeMillis() + 15L * 24 * 60 * 60 * 1000)
                    "No forecast that far ahead. You can type the weather."
                else "Couldn't get weather. Check your internet, or type it."
            }
        }
    }

    private fun timeLine(): String =
        SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.US).format(cal.time) +
            " GMT " + Stamper.formatOffset(offsetMinutes())

    // ---------- Render and save ----------

    private fun stampInfo(): StampInfo {
        val lat = etLat.text.toString().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lng = etLng.text.toString().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        val ok = lat != null && lng != null
        return StampInfo(
            lat = if (ok) lat else null,
            lng = if (ok) lng else null,
            place = etPlace.text.toString().trim(),
            address = etAddress.text.toString().trim(),
            timeLine = timeLine(),
            weather = if (cbWeather.isChecked) etWeather.text.toString().trim() else "",
            brand = etBrand.text.toString().trim(),
            showMap = cbMap.isChecked
        )
    }

    private fun scheduleRender() {
        handler.removeCallbacks(renderRunnable)
        handler.postDelayed(renderRunnable, 150)
    }

    private fun renderPreview() {
        val src = previewSource ?: return
        preview.setImageBitmap(Stamper.stamp(src, stampInfo()))
    }

    private fun onSaveClicked() {
        if (Build.VERSION.SDK_INT < 29 && !hasPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else save()
    }

    private fun save() {
        val src = source ?: return
        val info = stampInfo()
        val takenAt = cal.timeInMillis
        val offset = offsetMinutes()
        btnSave.isEnabled = false
        msg.text = "Saving…"
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                try { writePhoto(src, info, takenAt, offset) } catch (e: Exception) { null }
            }
            btnSave.isEnabled = true
            msg.text = if (name != null) "Saved to Pictures/GeoStamp as $name" else "Couldn't save the photo. Check storage space and try again."
        }
    }

    private fun writePhoto(src: Bitmap, info: StampInfo, takenAt: Long, offset: Int): String {
        val stamped = Stamper.stamp(src, info)
        val tmp = File(cacheDir, "stamped.jpg")
        FileOutputStream(tmp).use { stamped.compress(Bitmap.CompressFormat.JPEG, 92, it) }

        val local = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(takenAt)
        val utcDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(takenAt)
        val utcTime = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(takenAt)
        val off = Stamper.formatOffset(offset)

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
            if (info.lat != null && info.lng != null) {
                setLatLong(info.lat, info.lng)
                setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utcDate)
                setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utcTime)
            }
            saveAttributes()
        }

        val stampName = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(takenAt)
        val displayName = "${fileBase}_$stampName.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoStamp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: throw IllegalStateException("insert failed")
        contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("open failed")
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        tmp.delete()
        return displayName
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
