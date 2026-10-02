package com.geostamp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashOverlay: View
    private lateinit var permissionText: TextView
    private lateinit var gpsDot: View
    private lateinit var gpsText: TextView
    private lateinit var placeText: TextView
    private lateinit var btnFlash: TextView
    private lateinit var thumb: ImageView
    private lateinit var countBadge: TextView
    private lateinit var btnDone: MaterialButton

    private var imageCapture: ImageCapture? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private var bestLocation: Location? = null
    private var currentPlace = ""
    private var currentAddress = ""
    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = r
            }
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        Session.init(this)

        previewView = findViewById(R.id.previewView)
        flashOverlay = findViewById(R.id.flashOverlay)
        permissionText = findViewById(R.id.permissionText)
        gpsDot = findViewById(R.id.gpsDot)
        gpsText = findViewById(R.id.gpsText)
        placeText = findViewById(R.id.placeText)
        btnFlash = findViewById(R.id.btnFlash)
        thumb = findViewById(R.id.thumb)
        countBadge = findViewById(R.id.countBadge)
        btnDone = findViewById(R.id.btnDone)

        findViewById<ImageButton>(R.id.shutter).setOnClickListener { takePhoto() }
        btnDone.setOnClickListener { openReview() }
        findViewById<View>(R.id.thumbBox).setOnClickListener { if (Session.items.isNotEmpty()) openReview() }
        btnFlash.setOnClickListener { cycleFlash() }

        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 29) needed += Manifest.permission.ACCESS_MEDIA_LOCATION
        if (needed.take(3).all { hasPerm(it) }) onPermissionsResult()
        else permissions.launch(needed.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
        refreshThumb()
    }

    override fun onPause() {
        locationManager?.removeUpdates(locationListener)
        super.onPause()
    }

    private fun onPermissionsResult() {
        if (hasPerm(Manifest.permission.CAMERA)) {
            permissionText.visibility = View.GONE
            startCamera()
        } else {
            permissionText.visibility = View.VISIBLE
        }
        startLocationUpdates()
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the camera.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        btnFlash.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "Flash auto"
            ImageCapture.FLASH_MODE_ON -> "Flash on"
            else -> "Flash off"
        }
        imageCapture?.flashMode = flashMode
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = Session.newPhotoFile()
        val takenAt = System.currentTimeMillis()
        val loc = bestLocation
        val place = currentPlace
        val address = currentAddress

        previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.7f
        flashOverlay.animate().alpha(0f).setDuration(180).start()

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val item = PhotoItem(
                    id = PhotoItem.newId(),
                    file = file.absolutePath,
                    lat = loc?.latitude,
                    lng = loc?.longitude,
                    place = place,
                    address = address,
                    timeMillis = takenAt
                )
                Session.add(item)
                refreshThumb()
                // If the address wasn't known yet for this spot, fill it in when it arrives
                if (loc != null && place.isBlank()) {
                    Geo.reverse(this@CameraActivity, loc.latitude, loc.longitude) { p, a ->
                        if (item.place.isBlank()) { item.place = p; item.address = a; Session.persist() }
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                file.delete()
                Toast.makeText(this@CameraActivity, "Couldn't take the photo. Try again.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun refreshThumb() {
        val n = Session.items.size
        btnDone.isEnabled = n > 0
        countBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        countBadge.text = n.toString()
        val last = Session.items.lastOrNull()
        if (last == null) {
            thumb.setImageDrawable(null)
            return
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageUtils.decode(last.file, 240) }
            if (bmp != null) thumb.setImageBitmap(bmp)
        }
    }

    private fun openReview() {
        startActivity(Intent(this, ReviewActivity::class.java))
    }

    // ---------- Location ----------

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            gpsText.text = "Location permission is off"
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { onNewLocation(it) }
                    lm.requestLocationUpdates(p, 2000L, 0f, locationListener, Looper.getMainLooper())
                    any = true
                }
            } catch (_: Exception) {}
        }
        if (!any) gpsText.text = "Turn on location in phone settings"
    }

    private fun onNewLocation(loc: Location) {
        val cur = bestLocation
        val isBetter = cur == null ||
            loc.time > cur.time + 10_000 ||
            (loc.hasAccuracy() && (!cur.hasAccuracy() || loc.accuracy <= cur.accuracy)) ||
            loc.time > cur.time && loc.provider == LocationManager.GPS_PROVIDER
        if (!isBetter) return
        bestLocation = loc

        val acc = if (loc.hasAccuracy()) String.format(Locale.US, " ±%d m", loc.accuracy.toInt()) else ""
        gpsText.text = String.format(Locale.US, "%.5f, %.5f%s", loc.latitude, loc.longitude, acc)
        val good = loc.hasAccuracy() && loc.accuracy <= 30f
        gpsDot.setBackgroundResource(if (good) R.drawable.dot_green else R.drawable.dot_amber)

        Geo.reverse(this, loc.latitude, loc.longitude) { p, a ->
            currentPlace = p
            currentAddress = a
            placeText.text = if (a.isNotBlank()) a else p
            placeText.visibility = if (placeText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
