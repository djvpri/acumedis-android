package com.acumedis.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionFormActivity : AppCompatActivity() {

    private var patientId: String = ""
    private var patientName: String = ""
    private var paid: Boolean = false

    private lateinit var fComplaint: EditText
    private lateinit var fTongue: EditText
    private lateinit var fPoints: EditText
    private lateinit var fDuration: EditText
    private lateinit var fNotes: EditText
    private lateinit var fFee: EditText
    private lateinit var fError: TextView
    private lateinit var fPainLabel: TextView
    private lateinit var pain: SeekBar
    private lateinit var preview: ImageView
    private lateinit var aiBox: View
    private lateinit var aiText: TextView
    private lateinit var aiUse: TextView

    /** hasil analisis terakhir, dipakai tombol "Pakai saran ini" */
    private var ai: Ai.Tongue? = null
    private var photoFile: File? = null

    /** JPEG yang sudah dikecilkan — dikirim ulang saat menyimpan sesi */
    private var capturedJpeg: String? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { analyzeUri(it) }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoFile?.let { analyzeUri(Uri.fromFile(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_form)
        Log.screen("SessionFormActivity")

        patientId = intent.getStringExtra("patient_id") ?: ""
        patientName = intent.getStringExtra("patient_name") ?: ""

        fComplaint = findViewById(R.id.complaint)
        fDuration = findViewById(R.id.duration)
        fNotes = findViewById(R.id.notes)
        fFee = findViewById(R.id.fee)
        fError = findViewById(R.id.error)
        fPainLabel = findViewById(R.id.painLabel)
        pain = findViewById(R.id.pain)
        preview = findViewById(R.id.tonguePreview)
        aiBox = findViewById(R.id.aiBox)
        aiText = findViewById(R.id.aiText)
        aiUse = findViewById(R.id.aiUse)

        fTongue = findViewById(R.id.tongue)
        fPoints = findViewById(R.id.points)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.pickPatient).setOnClickListener { choosePatient() }
        findViewById<View>(R.id.save).setOnClickListener { save() }
        findViewById<View>(R.id.takePhoto).setOnClickListener { shoot() }
        findViewById<View>(R.id.pickPhoto).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<View>(R.id.suggestPoints).setOnClickListener { suggestPoints() }
        aiUse.setOnClickListener { applyAi() }

        pain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                fPainLabel.text = "$progress/10"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<TextView>(R.id.paid).setOnClickListener {
            paid = true
            paintPaid()
        }
        findViewById<TextView>(R.id.unpaid).setOnClickListener {
            paid = false
            paintPaid()
        }
        paintPaid()

        if (patientId.isNotBlank()) setPatient(patientId, patientName)
    }

    private fun setPatient(id: String, name: String) {
        patientId = id
        patientName = name
        findViewById<TextView>(R.id.pickPatient).text = name
        findViewById<TextView>(R.id.sub).text = name
    }

    private fun paintPaid() {
        val a = findViewById<TextView>(R.id.paid)
        val b = findViewById<TextView>(R.id.unpaid)
        a.isSelected = paid
        b.isSelected = !paid
        a.setTextColor(getColor(if (paid) android.R.color.white else R.color.fg2))
        b.setTextColor(getColor(if (!paid) android.R.color.white else R.color.fg2))
    }

    private fun choosePatient() {
        Api.io.execute {
            val arr = Api.patients(this)
            val names = mutableListOf<String>()
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                names.add(o.optString("name"))
                ids.add(o.optString("id"))
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (names.isEmpty()) {
                    toast("Belum ada pasien. Tambah dulu di menu Pasien.")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Pilih pasien")
                    .setItems(names.toTypedArray()) { _, which ->
                        setPatient(ids[which], names[which])
                    }
                    .show()
            }
        }
    }

    // ---------- foto lidah + analisis ----------

    private fun shoot() {
        val dir = File(cacheDir, "tongue").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "tongue-${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        runCatching { takePhoto.launch(uri) }
            .onFailure { toast("Kamera tidak tersedia. Pakai \"Dari galeri\".") }
    }

    private fun analyzeUri(uri: Uri) {
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
        if (bytes == null || bytes.isEmpty()) return toast("Gambar tidak terbaca")

        val packed = Ai.toJpegBase64(bytes) ?: return toast("Format gambar tidak didukung")
        capturedJpeg = packed.first
        preview.setImageURI(null)
        preview.setImageURI(uri)
        preview.visibility = View.VISIBLE

        aiBox.visibility = View.VISIBLE
        aiUse.visibility = View.GONE
        aiText.text = "Menganalisis foto lidah…"

        Api.io.execute {
            val res = Ai.analyzeTongue(this, packed.first, packed.second, fComplaint.text.toString())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (res == null) {
                    aiText.text = "Analisis gagal. Periksa koneksi atau kunci AI di server."
                    return@runOnUiThread
                }
                ai = res
                aiText.text = buildString {
                    if (res.tongue.isNotBlank()) append("Lidah: ").append(res.tongue).append("\n\n")
                    if (res.diagnosis.isNotBlank()) append("Pola: ").append(res.diagnosis).append("\n\n")
                    if (res.treatment.isNotBlank()) append("Saran terapi: ").append(res.treatment).append("\n\n")
                    if (res.points.isNotEmpty()) append("Titik: ").append(res.points.joinToString(", ")).append("\n\n")
                    append(res.notes)
                }.trim()
                aiUse.visibility = View.VISIBLE
            }
        }
    }

    /** Isi kolom lidah + titik dari hasil analisis AI. */
    private fun applyAi() {
        val r = ai ?: return
        if (r.tongue.isNotBlank()) fTongue.setText(r.tongue)
        if (r.points.isNotEmpty()) fPoints.setText(r.points.joinToString(", "))
        toast("Saran AI dimasukkan ke kolom")
    }

    private fun suggestPoints() {
        val r = ai
        if (r != null && r.points.isNotEmpty()) {
            fPoints.setText(r.points.joinToString(", "))
            toast("Titik dari analisis foto dimasukkan")
        } else {
            toast("Analisis foto lidah dulu supaya titik bisa disarankan.")
        }
    }

    private fun save() {
        val complaint = fComplaint.text.toString().trim()
        when {
            patientId.isBlank() -> return fail("Pilih pasien dulu")
            complaint.isEmpty() -> return fail("Keluhan utama wajib diisi")
        }
        fError.visibility = View.GONE
        findViewById<View>(R.id.save).isEnabled = false

        // titik ditulis bebas ("LI4, LI15") → dirapikan jadi array
        val pointList = fPoints.text.toString()
            .split(',', ';', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        val body = JSONObject()
            .put("patient_id", patientId)
            .put("chief_complaint", complaint)
            .put("tongue_color", fTongue.text.toString().trim())
            .put("pain_scale", pain.progress)
            .put("points_used", JSONArray(pointList))
            .put("duration_minutes", fDuration.text.toString().trim().toIntOrNull() ?: 30)
            .put("notes", fNotes.text.toString().trim())
            .put("payment_status", if (paid) "paid" else "unpaid")

        // foto lidah & hasil analisis ikut tersimpan bersama rekam medis
        capturedJpeg?.let {
            body.put("photo_base64", it)
            body.put("photo_mime", "image/jpeg")
        }
        ai?.let { r ->
            body.put("ai_text", aiText.text.toString())
            body.put("ai_recommendation", JSONObject()
                .put("tongue", r.tongue)
                .put("diagnosis", r.diagnosis)
                .put("treatment", r.treatment)
                .put("points", JSONArray(r.points)))
        }

        fFee.text.toString().trim().toDoubleOrNull()?.let { body.put("fee", it) }

        Api.io.execute {
            val ok = Api.createSession(this, body)
            runOnUiThread {
                findViewById<View>(R.id.save).isEnabled = true
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    toast("Sesi tersimpan")
                    finish()
                } else {
                    fail("Gagal menyimpan. Periksa koneksi atau login.")
                }
            }
        }
    }

    private fun fail(msg: String) {
        fError.text = msg
        fError.visibility = View.VISIBLE
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}
