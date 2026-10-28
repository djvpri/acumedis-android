package com.acumedis.app

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * Satu formulir dipakai untuk pasien baru dan ubah data pasien, supaya
 * kolomnya tidak berbeda antara dua jalur.
 */
object PatientForm {

    fun show(act: Activity, existing: Patient?, onSaved: (Patient?) -> Unit) {
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        fun field(hint: String, value: String?, phone: Boolean = false, lines: Int = 1) =
            EditText(act).apply {
                this.hint = hint
                setText(value ?: "")
                if (phone) inputType = InputType.TYPE_CLASS_PHONE
                if (lines > 1) { minLines = lines; inputType = inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            }

        val fName = field("Nama lengkap", existing?.name)
        val fPhone = field("Nomor telepon", existing?.phone, phone = true)
        val fBirth = field("Tanggal lahir (mis. 1990-05-17)", existing?.birthDate)
        val fEmail = field("Email (opsional)", existing?.email)
        val fAddress = field("Alamat (opsional)", existing?.address, lines = 2)
        val fNote = field("Catatan (opsional)", existing?.note, lines = 2)

        // jenis kelamin: 3 pilihan datar, kosong = tidak dicatat
        val gender = RadioGroup(act).apply { orientation = RadioGroup.HORIZONTAL }
        val bMale = RadioButton(act).apply { text = "Laki-laki"; id = 1 }
        val bFemale = RadioButton(act).apply { text = "Perempuan"; id = 2 }
        gender.addView(bMale)
        gender.addView(bFemale)
        when (existing?.gender) {
            "male" -> gender.check(1)
            "female" -> gender.check(2)
        }

        listOf(fName, fPhone, fBirth, fEmail, fAddress, fNote).forEach(box::addView)
        box.addView(gender)

        AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Pasien baru" else "Ubah data pasien")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val name = fName.text.toString().trim()
                if (name.isEmpty()) {
                    toast(act, "Nama wajib diisi")
                    return@setPositiveButton
                }

                val genderValue = when (gender.checkedRadioButtonId) {
                    1 -> "male"
                    2 -> "female"
                    else -> ""
                }

                val body = JSONObject()
                    .put("name", name)
                    .put("phone", fPhone.text.toString().trim())
                    .put("birth_date", fBirth.text.toString().trim())
                    .put("email", fEmail.text.toString().trim())
                    .put("address", fAddress.text.toString().trim())
                    .put("note", fNote.text.toString().trim())
                // gender kosong tidak dikirim: server hanya menerima male/female
                if (genderValue.isNotEmpty()) body.put("gender", genderValue)

                Api.io.execute {
                    val ok = if (existing == null) Api.createPatient(act, body)
                    else Api.updatePatient(act, existing.id, body)
                    val saved = if (ok && existing != null) Api.patient(act, existing.id) else null
                    act.runOnUiThread {
                        if (act.isFinishing) return@runOnUiThread
                        if (!ok) return@runOnUiThread toast(act, "Gagal menyimpan. Periksa koneksi.")
                        toast(act, if (existing == null) "Pasien ditambahkan" else "Data pasien diperbarui")
                        onSaved(saved)
                    }
                }
            }
            .show()
    }

    private fun toast(act: Activity, msg: String) =
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
}
