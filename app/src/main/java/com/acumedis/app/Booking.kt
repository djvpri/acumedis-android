package com.acumedis.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Satu jadwal (booking) dari /v1/appointments.
 * Waktu dikirim server sebagai UTC; [waktu] menampilkannya menurut zona perangkat.
 */
data class Booking(
    val id: String,
    val at: String,
    val duration: Int,
    val reason: String?,
    val status: String,
    val queue: Int?,
    val patientId: String?,
    val patientName: String?,
    val externalName: String?,
    val externalPhone: String?,
    val notes: String?
) {
    val name: String get() = patientName ?: externalName ?: "Pasien"
    val isNewPatient: Boolean get() = patientId == null

    /** "08:30" menurut jam perangkat, bukan UTC. */
    val waktu: String get() = Fmt.jam(at)

    companion object {
        fun from(o: org.json.JSONObject) = Booking(
            id = o.optString("id"),
            at = o.optString("scheduled_at"),
            duration = if (o.isNull("duration_minutes")) 60 else o.optInt("duration_minutes"),
            reason = o.str("reason"),
            status = o.str("status") ?: "confirmed",
            queue = if (o.isNull("queue_number")) null else o.optInt("queue_number"),
            patientId = o.str("patient_id"),
            patientName = o.str("patient_name"),
            externalName = o.str("external_name"),
            externalPhone = o.str("external_phone"),
            notes = o.str("notes")
        )
    }
}

/** Formulir buat/ubah jadwal. */
object BookingForm {

    fun show(act: Activity, existing: Booking?, onSaved: () -> Unit) {
        val d = androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Jadwal baru" else "Ubah jadwal")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        val pad = (18 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        // siapa: pasien terdaftar, atau nama bebas untuk yang belum terdaftar
        var patientId: String? = existing?.patientId
        var externalName: String? = existing?.externalName
        val who = TextView(act).apply {
            text = existing?.name ?: "Pilih pasien"
            textSize = 14f
            setTextColor(act.getColor(R.color.fg))
            setBackgroundResource(R.drawable.bg_input_focus)
            val p = (12 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            isClickable = true
        }
        box.addView(fieldLabel(act, "Pasien"))
        box.addView(who)

        val extName = edit(act, "Atau ketik nama pasien baru", externalName)
        val extPhone = edit(act, "No. HP (opsional)", existing?.externalPhone)
        extName.visibility = if (existing != null && existing.isNewPatient) android.view.View.VISIBLE
            else android.view.View.GONE
        box.addView(extName)
        box.addView(extPhone)

        who.setOnClickListener {
            Api.io.execute {
                val arr = Api.patients(act)
                val names = mutableListOf("+ Pasien baru (ketik nama)")
                val ids = mutableListOf<String?>(null)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    names.add(o.optString("name"))
                    ids.add(o.optString("id"))
                }
                act.runOnUiThread {
                    if (act.isFinishing) return@runOnUiThread
                    androidx.appcompat.app.AlertDialog.Builder(act)
                        .setTitle("Pilih pasien")
                        .setItems(names.toTypedArray()) { _, which ->
                            patientId = ids[which]
                            who.text = names[which]
                            val isNew = ids[which] == null
                            extName.visibility = if (isNew) android.view.View.VISIBLE else android.view.View.GONE
                            if (isNew) extName.requestFocus()
                        }
                        .show()
                }
            }
        }

        // waktu: tanggal + jam dipilih, bukan diketik
        val now = java.util.Calendar.getInstance()
        if (existing != null) {
            java.util.Calendar.getInstance().apply {
                timeInMillis = Fmt.millis(existing.at) ?: System.currentTimeMillis()
            }.let { c ->
                now.set(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH),
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
            }
        }

        val when_ = TextView(act).apply {
            text = Fmt.waktuLengkap(now.timeInMillis)
            textSize = 14f
            setTextColor(act.getColor(R.color.fg))
            setBackgroundResource(R.drawable.bg_input_focus)
            val p = (12 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            isClickable = true
        }
        box.addView(fieldLabel(act, "Tanggal & jam"))
        box.addView(when_)

        when_.setOnClickListener {
            android.app.DatePickerDialog(act, { _, y, m, dd ->
                now.set(y, m, dd)
                android.app.TimePickerDialog(act, { _, h, mi ->
                    now.set(java.util.Calendar.HOUR_OF_DAY, h)
                    now.set(java.util.Calendar.MINUTE, mi)
                    when_.text = Fmt.waktuLengkap(now.timeInMillis)
                }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
            }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH),
                now.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }

        val dur = edit(act, "Durasi (menit)", (existing?.duration ?: 60).toString())
        dur.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val reason = edit(act, "Keperluan (mis. kontrol, nyeri lutut)", existing?.reason)
        val notes = edit(act, "Catatan (opsional)", existing?.notes)

        box.addView(fieldLabel(act, "Durasi & keperluan"))
        box.addView(dur)
        box.addView(reason)
        box.addView(notes)

        val err = TextView(act).apply {
            setTextColor(act.getColor(R.color.red))
            textSize = 12f
            visibility = android.view.View.GONE
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(err)

        d.setView(box)
        d.setOnShowListener {
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                err.visibility = android.view.View.GONE

                val name = extName.text.toString().trim()
                if (patientId == null && name.isEmpty()) {
                    err.text = "Pilih pasien, atau ketik nama pasien baru"
                    err.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                if (patientId == null) externalName = name else externalName = null

                val body = org.json.JSONObject()
                    // waktu lokal → ISO UTC, supaya server tidak menebak zona
                    .put("scheduled_at", Fmt.isoUtc(now.timeInMillis))
                    .put("duration_minutes", dur.text.toString().trim().toIntOrNull() ?: 60)
                    .put("reason", reason.text.toString().trim())
                    .put("notes", notes.text.toString().trim())

                if (patientId != null) body.put("patient_id", patientId)
                else {
                    body.put("external_name", externalName)
                    body.put("external_phone", extPhone.text.toString().trim())
                }

                d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Api.io.execute {
                    val (ok, msg) = if (existing == null) Api.createAppointment(act, body)
                    else Api.updateAppointment(act, existing.id, body)
                    act.runOnUiThread {
                        if (act.isFinishing) return@runOnUiThread
                        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (ok) {
                            d.dismiss()
                            onSaved()
                        } else {
                            // pesan bentrok dari server ditampilkan apa adanya
                            err.text = msg
                            err.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            }
        }
        d.show()
    }

    private fun edit(act: Context, hint: String, value: String?) =
        android.widget.EditText(act).apply {
            this.hint = hint
            setText(value ?: "")
            textSize = 14f
            setBackgroundResource(R.drawable.bg_input_focus)
            val p = (12 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

    private fun fieldLabel(act: Context, s: String) = TextView(act).apply {
        text = s
        textSize = 11f
        setTextColor(act.getColor(R.color.muted))
        setPadding(0, (14 * act.resources.displayMetrics.density).toInt(), 0, 4)
    }
}
