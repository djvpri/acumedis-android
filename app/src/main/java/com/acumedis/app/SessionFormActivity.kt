package com.acumedis.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class SessionFormActivity : AppCompatActivity() {

    private var patientId: String = ""
    private var patientName: String = ""
    private var tongue: String = "Normal"
    private var paid: Boolean = false
    private val points = linkedSetOf<String>()

    private lateinit var fComplaint: EditText
    private lateinit var fDuration: EditText
    private lateinit var fNotes: EditText
    private lateinit var fFee: EditText
    private lateinit var fError: TextView
    private lateinit var fPainLabel: TextView
    private lateinit var pain: SeekBar

    private val pointIds = listOf("LI4", "GB34", "BL40", "ST36", "SP6", "CV4", "GV20", "LU7")
    private val tongueIds = listOf("Pucat", "Merah", "Ungu", "Normal")

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

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.pickPatient).setOnClickListener { choosePatient() }
        findViewById<View>(R.id.save).setOnClickListener { save() }

        pain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                fPainLabel.text = "$progress/10"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val tongueMap = mapOf(
            "Pucat" to R.id.tPucat, "Merah" to R.id.tMerah,
            "Ungu" to R.id.tUngu, "Normal" to R.id.tNormal
        )
        tongueMap.forEach { (name, id) ->
            findViewById<TextView>(id).setOnClickListener {
                tongue = name
                paintTongue(tongueMap)
            }
        }
        paintTongue(tongueMap)

        val pointMap = mapOf(
            "LI4" to R.id.pLI4, "GB34" to R.id.pGB34, "BL40" to R.id.pBL40,
            "ST36" to R.id.pST36, "SP6" to R.id.pSP6, "CV4" to R.id.pCV4,
            "GV20" to R.id.pGV20, "LU7" to R.id.pLU7
        )
        pointMap.forEach { (p, id) ->
            findViewById<TextView>(id).setOnClickListener {
                if (!points.add(p)) points.remove(p)
                paintPoints(pointMap)
            }
        }

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

    private fun paintTongue(map: Map<String, Int>) {
        map.forEach { (name, id) ->
            val v = findViewById<TextView>(id)
            v.isSelected = name == tongue
            v.setTextColor(getColor(if (name == tongue) android.R.color.white else R.color.fg2))
        }
    }

    private fun paintPoints(map: Map<String, Int>) {
        map.forEach { (p, id) ->
            val v = findViewById<TextView>(id)
            v.isSelected = points.contains(p)
            v.setTextColor(getColor(if (points.contains(p)) android.R.color.white else R.color.fg2))
        }
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

    private fun save() {
        val complaint = fComplaint.text.toString().trim()
        when {
            patientId.isBlank() -> return fail("Pilih pasien dulu")
            complaint.isEmpty() -> return fail("Keluhan utama wajib diisi")
        }
        fError.visibility = View.GONE
        findViewById<View>(R.id.save).isEnabled = false

        val body = JSONObject()
            .put("patient_id", patientId)
            .put("chief_complaint", complaint)
            .put("tongue_color", tongue)
            .put("pain_scale", pain.progress)
            .put("points_used", JSONArray(points.toList()))
            .put("duration_minutes", fDuration.text.toString().trim().toIntOrNull() ?: 30)
            .put("notes", fNotes.text.toString().trim())
            .put("payment_status", if (paid) "paid" else "unpaid")

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
