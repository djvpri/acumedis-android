package com.acumedis.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PatientDetailActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var patientId: String = ""
    private var patientName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_detail)
        Log.screen("PatientDetailActivity")

        patientId = intent.getStringExtra("patient_id") ?: ""
        patientName = intent.getStringExtra("patient_name") ?: "Pasien"

        list = findViewById(R.id.historyList)
        findViewById<TextView>(R.id.title).text = patientName
        findViewById<TextView>(R.id.sub).text = "Rekam medis"

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.edit).setOnClickListener { editPatient() }
        findViewById<View>(R.id.newSession).setOnClickListener {
            startActivity(
                Intent(this, SessionFormActivity::class.java)
                    .putExtra("patient_id", patientId)
                    .putExtra("patient_name", patientName)
            )
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** Ubah data pasien; judul ikut diperbarui kalau namanya berubah. */
    private fun editPatient() {
        Api.io.execute {
            val p = Api.patient(this, patientId)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (p == null) {
                    android.widget.Toast
                        .makeText(this, "Data pasien tidak terbaca", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                PatientForm.show(this, p) { saved ->
                    if (saved != null) {
                        patientId = saved.id
                        patientName = saved.name
                        findViewById<TextView>(R.id.title).text = saved.name
                        showMeta(saved)
                    }
                    load()
                }
            }
        }
    }

    /** Baris kecil di bawah nama: jenis kelamin, umur, telepon. */
    private fun showMeta(p: Patient) {
        val bits = mutableListOf<String>()
        p.gender?.let { bits.add(if (it == "male") "Laki-laki" else "Perempuan") }
        p.birthDate?.let { bits.add(Fmt.umur(it)) }
        p.phone?.let { bits.add(it) }
        findViewById<TextView>(R.id.sub).text =
            if (bits.isEmpty()) "Rekam medis" else bits.joinToString(" · ")
    }

    private fun load() {
        Api.io.execute {
            val arr = Api.sessions(this)
            val visits = mutableListOf<Visit>()
            var total = 0
            var unpaid = 0
            for (i in 0 until arr.length()) {
                val v = Visit.from(arr.getJSONObject(i))
                if (v.patientId != patientId) continue
                total++
                if (v.payment != "paid") unpaid++
                visits.add(v)
            }
            val sorted = visits.sortedByDescending { it.date }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                findViewById<TextView>(R.id.statTotal).text = total.toString()
                findViewById<TextView>(R.id.statUnpaid).text = unpaid.toString()
                findViewById<View>(R.id.empty).visibility =
                    if (sorted.isEmpty()) View.VISIBLE else View.GONE

                list.removeAllViews()
                sorted.forEach { v ->
                    val row = LayoutInflater.from(this)
                        .inflate(R.layout.item_visit, list, false)
                    row.findViewById<TextView>(R.id.date).text = Fmt.pendek(v.date)
                    row.findViewById<TextView>(R.id.complaint).text = v.complaint

                    val bits = mutableListOf<String>()
                    if (v.points.isNotEmpty()) bits.add(v.points.joinToString(", "))
                    v.duration?.let { bits.add("$it menit") }
                    row.findViewById<TextView>(R.id.meta).text =
                        if (bits.isEmpty()) "—" else bits.joinToString(" · ")

                    val diag = row.findViewById<TextView>(R.id.diagnosis)
                    if (v.diagnosis.isNullOrBlank()) diag.visibility = View.GONE
                    else {
                        diag.visibility = View.VISIBLE
                        diag.text = v.diagnosis
                    }

                    val pay = row.findViewById<TextView>(R.id.pay)
                    val paid = v.payment == "paid"
                    pay.text = if (paid) "Lunas" else "Belum"
                    pay.backgroundTintList = getColorStateList(
                        if (paid) R.color.green_soft else R.color.amber_soft
                    )
                    pay.setTextColor(getColor(if (paid) R.color.green else R.color.amber))

                    // tombol nota: satu baris riwayat = satu kwitansi
                    val notaBtn = row.findViewById<TextView>(R.id.notaBtn)
                    notaBtn.visibility = View.VISIBLE
                    notaBtn.setOnClickListener {
                        NotaSheet.show(
                            this,
                            Patient(patientId, patientName, null, null, null, 0, null),
                            v
                        )
                    }

                    list.addView(row)
                }
            }
        }
    }
}
