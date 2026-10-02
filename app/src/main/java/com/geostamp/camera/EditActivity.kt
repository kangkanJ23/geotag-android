package com.geostamp.camera

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var item: PhotoItem
    private lateinit var preview: ImageView
    private lateinit var btnDate: MaterialButton
    private lateinit var btnTime: MaterialButton
    private lateinit var tzText: TextView
    private lateinit var etPlace: EditText
    private lateinit var etAddress: EditText
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var cbShiftAll: CheckBox
    private lateinit var cbPlaceAll: CheckBox

    private var base: Bitmap? = null
    private val cal = Calendar.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val renderRunnable = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        val found = Session.find(intent.getStringExtra(EXTRA_ID))
        if (found == null) { finish(); return }
        item = found
        setContentView(R.layout.activity_edit)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val index = Session.items.indexOf(item) + 1
        toolbar.subtitle = "Photo $index of ${Session.items.size}"

        preview = findViewById(R.id.preview)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        tzText = findViewById(R.id.tzText)
        etPlace = findViewById(R.id.etPlace)
        etAddress = findViewById(R.id.etAddress)
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        cbShiftAll = findViewById(R.id.cbShiftAll)
        cbPlaceAll = findViewById(R.id.cbPlaceAll)

        cal.timeInMillis = item.timeMillis
        etPlace.setText(item.place)
        etAddress.setText(item.address)
        item.lat?.let { etLat.setText(String.format(Locale.US, "%.6f", it)) }
        item.lng?.let { etLng.setText(String.format(Locale.US, "%.6f", it)) }
        val many = Session.items.size > 1
        cbShiftAll.visibility = if (many) View.VISIBLE else View.GONE
        cbPlaceAll.visibility = if (many) View.VISIBLE else View.GONE
        updateTimeButtons()

        btnDate.setOnClickListener { pickDate() }
        btnTime.setOnClickListener { pickTime() }
        findViewById<MaterialButton>(R.id.btnLookup).setOnClickListener { lookup() }
        findViewById<MaterialButton>(R.id.btnApply).setOnClickListener { applyChanges() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        listOf(etPlace, etAddress, etLat, etLng).forEach { it.doAfterTextChanged { scheduleRender() } }

        lifecycleScope.launch {
            base = withContext(Dispatchers.IO) { ImageUtils.decode(item.file, 1400) }
            render()
        }
    }

    private fun pickDate() {
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            updateTimeButtons(); scheduleRender()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, min ->
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            updateTimeButtons(); scheduleRender()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }

    private fun updateTimeButtons() {
        btnDate.text = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(cal.time)
        btnTime.text = SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
        tzText.text = "Time zone: ${TimeZone.getDefault().id} (GMT ${Stamper.formatOffset(Format.offsetMinutes(cal.timeInMillis))})"
    }

    private fun lookup() {
        val lat = parsedLat()
        val lng = parsedLng()
        if (lat == null || lng == null) {
            Toast.makeText(this, "Enter valid coordinates first.", Toast.LENGTH_SHORT).show()
            return
        }
        Geo.reverse(this, lat, lng) { p, a ->
            etPlace.setText(p)
            etAddress.setText(a)
        }
    }

    private fun parsedLat() = etLat.text.toString().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    private fun parsedLng() = etLng.text.toString().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }

    private fun draft(): PhotoItem {
        val lat = parsedLat()
        val lng = parsedLng()
        val both = lat != null && lng != null
        return item.copy(
            lat = if (both) lat else null,
            lng = if (both) lng else null,
            place = etPlace.text.toString().trim(),
            address = etAddress.text.toString().trim(),
            timeMillis = cal.timeInMillis
        )
    }

    private fun scheduleRender() {
        handler.removeCallbacks(renderRunnable)
        handler.postDelayed(renderRunnable, 150)
    }

    private fun render() {
        val b = base ?: return
        preview.setImageBitmap(Stamper.stamp(b, Format.stampInfo(this, draft())))
    }

    private fun applyChanges() {
        val d = draft()
        val delta = d.timeMillis - item.timeMillis
        if (cbShiftAll.isChecked && delta != 0L) {
            Session.items.forEach { if (it !== item) { it.timeMillis += delta; it.saved = false } }
        }
        if (cbPlaceAll.isChecked) {
            Session.items.forEach {
                it.lat = d.lat; it.lng = d.lng; it.place = d.place; it.address = d.address; it.saved = false
            }
        }
        item.lat = d.lat
        item.lng = d.lng
        item.place = d.place
        item.address = d.address
        item.timeMillis = d.timeMillis
        item.saved = false
        Session.persist()
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this photo?")
            .setMessage(if (item.saved) "The copy in your gallery stays." else "It isn't saved to your gallery yet, so it will be gone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> Session.remove(item); finish() }
            .show()
    }
}
