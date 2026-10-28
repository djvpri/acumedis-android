package com.acumedis.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PatientsActivity : AppCompatActivity() {

    private var all: List<Patient> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patients)
        Log.screen("PatientsActivity")

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.addBtn).setOnClickListener { addPatient() }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                render(list)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        load(list)
    }

    override fun onResume() {
        super.onResume()
        load(findViewById(R.id.list))
    }

    private fun load(list: RecyclerView) {
        Api.io.execute {
            val arr = Api.patients(this)
            val items = mutableListOf<Patient>()
            for (i in 0 until arr.length()) items.add(Patient.from(arr.getJSONObject(i)))
            all = items
            runOnUiThread { if (!isFinishing) render(list) }
        }
    }

    private fun render(list: RecyclerView) {
        val shown = all.filter {
            query.isEmpty() || it.name.contains(query, true) || (it.phone ?: "").contains(query)
        }

        findViewById<View>(R.id.empty).visibility =
            if (shown.isEmpty()) View.VISIBLE else View.GONE

        list.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_patient, parent, false)
                return Holder(v)
            }

            override fun getItemCount() = shown.size

            override fun onBindViewHolder(h: Holder, position: Int) {
                val p = shown[position]
                h.avatar.text = p.initials()
                h.name.text = p.name
                val bits = mutableListOf<String>()
                p.gender?.let { bits.add(if (it == "female") "Perempuan" else "Laki-laki") }
                p.phone?.let { bits.add(it) }
                if (bits.isEmpty()) bits.add("Belum ada kontak")
                h.sub.text = bits.joinToString(" · ")
                h.count.text = "${p.totalSessions}x"
                h.itemView.setOnClickListener {
                    startActivity(
                        Intent(this@PatientsActivity, PatientDetailActivity::class.java)
                            .putExtra("patient_id", p.id)
                            .putExtra("patient_name", p.name)
                    )
                }
            }
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val count: TextView = v.findViewById(R.id.count)
    }

    private fun addPatient() {
        PatientForm.show(this, null) { load(findViewById(R.id.list)) }
    }
}
