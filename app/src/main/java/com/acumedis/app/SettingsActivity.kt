package com.acumedis.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pengaturan: nama tempat praktek, alamat, no HP, dan daftar praktisi
 * (bisa lebih dari satu). Data disimpan di server, jadi ikut ke perangkat lain
 * dan dipakai sebagai kepala nota.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var fName: EditText
    private lateinit var fAddress: EditText
    private lateinit var fPhone: EditText
    private lateinit var staffList: LinearLayout
    private var staff: JSONArray = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.screen("SettingsActivity")

        fName = findViewById(R.id.clinicName)
        fAddress = findViewById(R.id.clinicAddress)
        fPhone = findViewById(R.id.clinicPhone)
        staffList = findViewById(R.id.staffList)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.saveBtn).setOnClickListener { saveClinic() }
        findViewById<View>(R.id.addStaff).setOnClickListener { editStaff(null) }

        val updateBtn = findViewById<TextView>(R.id.updateBtn)
        updateBtn.setOnClickListener {
            updateBtn.text = "Memeriksa…"
            Updates.check(this) { updateBtn.text = "Cek update terbaru" }
        }
        findViewById<View>(R.id.diagBtn).setOnClickListener { showDiagnostics() }

        load()
    }

    /** Diagnostik: antrean log lokal, kirim paksa, hapus antrean. */
    private fun showDiagnostics() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val info = TextView(this).apply {
            setTextColor(getColor(R.color.fg2))
            textSize = 12.5f
        }
        box.addView(info)

        val logs = TextView(this).apply {
            setTextColor(getColor(R.color.muted))
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(logs)

        fun refresh() {
            val n = Log.pending()
            info.text = if (n == 0) "Antrean log kosong. v${BuildConfig.VERSION_NAME}"
            else "Antrean: $n log menunggu dikirim.  v${BuildConfig.VERSION_NAME}"
            logs.text = Log.tail(8).joinToString("\n\n").ifBlank { "Tidak ada log lokal." }
        }
        refresh()

        val dlg = AlertDialog.Builder(this)
            .setTitle("Diagnostik")
            .setView(box)
            .setPositiveButton("Kirim sekarang") { _, _ ->
                Thread {
                    val sent = Log.flush()
                    runOnUiThread {
                        refresh()
                        toast(if (sent > 0) "$sent log terkirim" else "Belum ada yang terkirim")
                    }
                }
            }
            .setNeutralButton("Hapus antrean") { _, _ ->
                Log.clearQueue()
                refresh()
            }
            .setNegativeButton("Tutup", null)
            .create()
        dlg.setOnShowListener { refresh() }
        dlg.show()
    }

    private fun load() {
        Api.io.execute {
            val raw = Api.request(this, "/v1/settings", "GET")
            val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            runOnUiThread {
                if (o == null) {
                    toast("Gagal memuat pengaturan. Cek koneksi.")
                    return@runOnUiThread
                }
                val c = o.optJSONObject("clinic")
                fName.setText(c?.optString("clinicName").orEmpty())
                fAddress.setText(c?.optString("address").orEmpty())
                fPhone.setText(c?.optString("phone").orEmpty())
                findViewById<TextView>(R.id.sub).text =
                    c?.optString("name").orEmpty().ifBlank { "Tempat praktek & praktisi" }

                staff = o.optJSONArray("practitioners") ?: JSONArray()
                renderStaff()
            }
        }
    }

    private fun renderStaff() {
        staffList.removeAllViews()
        for (i in 0 until staff.length()) {
            val p = staff.getJSONObject(i)
            val row = LayoutInflater.from(this).inflate(R.layout.item_staff, staffList, false)
            val name = p.optString("name")
            row.findViewById<TextView>(R.id.avatar).text =
                name.trim().split(" ").filter { it.isNotBlank() }.take(2)
                    .joinToString("") { it.first().uppercase() }.ifBlank { "P" }
            row.findViewById<TextView>(R.id.name).text = name

            val bits = mutableListOf<String>()
            p.optString("role").takeIf { it.isNotBlank() }?.let {
                bits.add(it.replaceFirstChar { ch -> ch.uppercase() })
            }
            p.optString("phone").takeIf { it.isNotBlank() }?.let { bits.add(it) }
            if (!p.optBoolean("active", true)) bits.add("nonaktif")
            row.findViewById<TextView>(R.id.meta).text =
                bits.joinToString(" · ").ifBlank { p.optString("email") }

            row.findViewById<View>(R.id.edit).setOnClickListener { editStaff(p) }
            staffList.addView(row)
        }
        if (staff.length() == 0) {
            val t = TextView(this).apply {
                text = "Belum ada praktisi."
                setTextColor(getColor(R.color.muted))
                textSize = 12f
            }
            staffList.addView(t)
        }
    }

    private fun saveClinic() {
        val name = fName.text.toString().trim()
        if (name.isEmpty()) return toast("Nama tempat praktek wajib diisi")

        val body = JSONObject()
            .put("clinicName", name)
            .put("address", fAddress.text.toString().trim())
            .put("phone", fPhone.text.toString().trim())

        Api.io.execute {
            val ok = Api.request(this, "/v1/settings", "PATCH", body) != null
            runOnUiThread {
                if (ok) {
                    // kepala nota langsung memakai nilai baru
                    Creds.saveClinic(this, name, fAddress.text.toString(), fPhone.text.toString())
                    toast("Tempat praktek disimpan")
                } else {
                    toast("Gagal menyimpan. Hanya owner/admin yang bisa mengubah.")
                }
            }
        }
    }

    /** null = tambah praktisi baru; selain itu ubah praktisi yang ada. */
    private fun editStaff(p: JSONObject?) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                setText(value)
                inputType = type
            }
            box.addView(e)
            return e
        }

        val fNameStaff = field("Nama praktisi", p?.optString("name").orEmpty(), android.text.InputType.TYPE_CLASS_TEXT)
        val fPhoneStaff = field("No HP praktisi", p?.optString("phone").orEmpty(), android.text.InputType.TYPE_CLASS_PHONE)

        val fEmail = if (p == null) {
            field("Email (untuk login)", "", android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        } else null
        val fPass = if (p == null) {
            field("Password awal (min 8)", "", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        } else null

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (p == null) "Tambah praktisi" else "Ubah praktisi")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = fNameStaff.text.toString().trim()
                if (name.isEmpty()) return@setOnClickListener toast("Nama wajib diisi")

                Api.io.execute {
                    val ok: Boolean
                    if (p == null) {
                        val email = fEmail?.text.toString().trim()
                        val pass = fPass?.text.toString() ?: ""
                        if (email.isEmpty() || pass.length < 8) {
                            runOnUiThread { toast("Email wajib, password minimal 8 karakter") }
                            return@execute
                        }
                        val body = JSONObject()
                            .put("name", name)
                            .put("email", email)
                            .put("password", pass)
                            .put("phone", fPhoneStaff.text.toString().trim())
                        ok = Api.request(this, "/v1/practitioners", "POST", body) != null
                    } else {
                        val body = JSONObject()
                            .put("name", name)
                            .put("phone", fPhoneStaff.text.toString().trim())
                        ok = Api.request(this, "/v1/practitioners/${p.optString("id")}", "PATCH", body) != null
                    }
                    runOnUiThread {
                        if (ok) {
                            dlg.dismiss()
                            toast("Tersimpan")
                            load()
                        } else {
                            toast("Gagal menyimpan. Cek email (mungkin sudah dipakai).")
                        }
                    }
                }
            }
        }
        dlg.show()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
}
