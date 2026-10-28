package com.acumedis.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.time.LocalDate

/**
 * Laporan keuangan: pendapatan, pengeluaran, laba, piutang.
 *
 * Semua angka berasal dari tabel Payment (bukan dari status sesi), jadi
 * uang yang dihitung adalah uang yang benar-benar diterima.
 */
class FinanceActivity : AppCompatActivity() {

    private var from: LocalDate = LocalDate.now()
    private var to: LocalDate = LocalDate.now()
    private var activePeriod = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finance)
        Log.screen("FinanceActivity")

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.pToday).setOnClickListener { setPeriod(0) }
        findViewById<View>(R.id.pWeek).setOnClickListener { setPeriod(1) }
        findViewById<View>(R.id.pMonth).setOnClickListener { setPeriod(2) }
        findViewById<View>(R.id.addExpense).setOnClickListener { addExpense() }

        setPeriod(0)
    }

    private fun setPeriod(which: Int) {
        activePeriod = which
        val today = LocalDate.now()
        when (which) {
            0 -> { from = today; to = today }
            1 -> { from = today.minusDays(6); to = today }
            else -> { from = today.withDayOfMonth(1); to = today }
        }
        paintPeriods()
        load()
    }

    private fun paintPeriods() {
        val ids = intArrayOf(R.id.pToday, R.id.pWeek, R.id.pMonth)
        ids.forEachIndexed { i, id ->
            val v = findViewById<TextView>(id)
            val on = i == activePeriod
            v.isSelected = on
            v.setBackgroundResource(if (on) R.drawable.bg_btn else R.drawable.bg_chip)
            v.setTextColor(getColor(if (on) android.R.color.white else R.color.fg2))
        }
    }

    private fun load() {
        findViewById<TextView>(R.id.period).text =
            if (from == to) Fmt.waktuLengkap(midnightAt(from)).substringBefore(" ·")
            else Fmt.tanggal(from.toString()) + " – " + Fmt.tanggal(to.toString())

        Api.io.execute {
            val j = Api.finance(this, from.toString(), to.toString())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (j == null) {
                    findViewById<TextView>(R.id.profit).text = "Gagal memuat"
                    return@runOnUiThread
                }
                render(j)
            }
        }
    }

    private fun render(j: JSONObject) {
        findViewById<TextView>(R.id.profit).text = Fmt.rupiah(j.optDouble("profit", 0.0))
        findViewById<TextView>(R.id.income).text = Fmt.rupiah(j.optDouble("income", 0.0))
        findViewById<TextView>(R.id.expense).text = Fmt.rupiah(j.optDouble("expense", 0.0))
        findViewById<TextView>(R.id.receivable).text = Fmt.rupiah(j.optDouble("receivables_total", 0.0))

        // grafik harian
        findViewById<DayChartView>(R.id.chart).setDays(j.optJSONArray("days"))

        // pendapatan per praktisi
        val prac = findViewById<LinearLayout>(R.id.practitioners)
        prac.removeAllViews()
        val bp = j.optJSONObject("by_practitioner")
        var anyPrac = false
        if (bp != null) {
            for (k in bp.keys()) {
                val amount = bp.optDouble(k, 0.0)
                if (amount <= 0) continue
                anyPrac = true
                prac.addView(row(k, Fmt.rupiah(amount), R.color.fg, bold = false))
            }
        }
        if (!anyPrac) prac.addView(empty("Belum ada pendapatan di periode ini"))

        // cara bayar
        val methods = findViewById<LinearLayout>(R.id.methods)
        methods.removeAllViews()
        val bm = j.optJSONObject("by_method")
        val labels = mapOf("cash" to "Tunai", "transfer" to "Transfer", "qris" to "QRIS", "other" to "Lain")
        var any = false
        if (bm != null) {
            for (k in bm.keys()) {
                val amount = bm.optDouble(k, 0.0)
                if (amount <= 0) continue
                any = true
                methods.addView(row(labels[k] ?: k, Fmt.rupiah(amount), R.color.fg, bold = false))
            }
        }
        if (!any) methods.addView(empty("Belum ada pembayaran di periode ini"))

        // pengeluaran: daftar baris, ketuk untuk ubah atau hapus
        val exp = findViewById<LinearLayout>(R.id.expenses)
        exp.removeAllViews()
        val arrExp = j.optJSONArray("expenses")
        if (arrExp == null || arrExp.length() == 0) {
            exp.addView(empty("Belum ada pengeluaran dicatat"))
        } else {
            for (i in 0 until arrExp.length()) {
                val o = arrExp.optJSONObject(i) ?: continue
                val desc = o.optString("description")
                val amount = o.optDouble("amount", 0.0)
                val cat = o.optString("category")
                val date = Fmt.tanggal(o.optString("date"))
                val r = row("$desc · $cat · $date", Fmt.rupiah(amount), R.color.red, bold = false)
                r.isClickable = true
                r.setOnClickListener { editExpense(o) }
                exp.addView(r)
            }
        }

        // piutang
        val rec = findViewById<LinearLayout>(R.id.receivables)
        rec.removeAllViews()
        val arr = j.optJSONArray("receivables")
        if (arr == null || arr.length() == 0) {
            rec.addView(empty("Semua sesi sudah lunas"))
        } else {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("patient_name").ifBlank { "Pasien" }
                val balance = o.optDouble("balance", 0.0)
                val date = Fmt.tanggal(o.optString("session_date"))
                // ketuk piutang → tawarkan tagih lewat WhatsApp
                val r = row("$name · $date", Fmt.rupiah(balance), R.color.amber, bold = false)
                val phone = o.optString("phone")
                if (phone.isNotBlank()) {
                    r.isClickable = true
                    r.setOnClickListener { remind(name, phone, balance) }
                }
                rec.addView(r)
            }
        }
    }

    private fun row(label: String, value: String, color: Int, bold: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
        addView(TextView(this@FinanceActivity).apply {
            text = label
            setTextColor(getColor(R.color.fg2))
            textSize = 12.5f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@FinanceActivity).apply {
            text = value
            setTextColor(getColor(color))
            textSize = 12.5f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun empty(s: String) = TextView(this).apply {
        text = s
        setTextColor(getColor(R.color.muted))
        textSize = 12f
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    /**
     * Tagih lewat WhatsApp. wa.me membuka WhatsApp dengan pesan siap kirim —
     * pengguna tetap menekan kirim sendiri, jadi tidak ada pesan terkirim tanpa
     * sepengetahuannya, dan tidak perlu API berbayar.
     */
    private fun remind(name: String, phone: String, balance: Double) {
        val bersih = phone.replace(Regex("[^0-9+]"), "")
        val nomor = when {
            bersih.startsWith("+62") -> bersih.substring(1)
            bersih.startsWith("62") -> bersih
            bersih.startsWith("0") -> "62" + bersih.substring(1)
            else -> bersih
        }
        val clinic = Creds.clinicName(this).ifBlank { Api.clinicName(this) }
        val pesan = "Halo $name, terima kasih sudah berobat di $clinic. " +
            "Ada sisa pembayaran sebesar ${Fmt.rupiah(balance)} yang belum tercatat lunas. " +
            "Silakan diselesaikan saat kunjungan berikutnya. Terima kasih."

        val wa = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://wa.me/$nomor?text=" + android.net.Uri.encode(pesan))
        )
        // kalau WhatsApp tidak terpasang, jangan matikan app
        runCatching { startActivity(wa) }
            .onFailure {
                android.widget.Toast.makeText(this, "WhatsApp tidak terpasang", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    /** Catat pengeluaran; kategori dipilih supaya laporan bisa dijumlah. */
    private fun addExpense() {
        editExpense(null)
    }

    /**
     * Satu form untuk mencatat dan mengoreksi pengeluaran. Dulu hanya bisa
     * ditambah, jadi salah ketik tidak bisa diperbaiki dari app.
     */
    private fun editExpense(existing: JSONObject?) {
        val categories = arrayOf("bahan", "sewa", "listrik", "gaji", "lain-lain")
        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val desc = android.widget.EditText(this).apply {
            hint = "Keterangan (mis. beli jarum sekali pakai)"
            setText(existing?.optString("description") ?: "")
            setBackgroundResource(R.drawable.bg_input_focus)
            setPadding(pad, pad, pad, pad)
        }
        val amount = android.widget.EditText(this).apply {
            hint = "Jumlah (Rp)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (existing != null && existing.optDouble("amount", 0.0) > 0) {
                setText(existing.optDouble("amount", 0.0).toLong().toString())
            }
            setBackgroundResource(R.drawable.bg_input_focus)
            setPadding(pad, pad, pad, pad)
        }
        val cat = TextView(this).apply {
            text = existing?.optString("category")?.ifBlank { categories[0] } ?: categories[0]
            setTextColor(getColor(R.color.fg))
            setBackgroundResource(R.drawable.bg_input_focus)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }
        cat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Kategori")
                .setItems(categories) { _, w -> cat.text = categories[w] }
                .show()
        }
        box.addView(desc)
        box.addView(amount)
        box.addView(cat)

        val err = TextView(this).apply {
            setTextColor(getColor(R.color.red))
            textSize = 12f
            visibility = View.GONE
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(err)

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Catat pengeluaran" else "Ubah pengeluaran")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                err.visibility = View.GONE
                val d = desc.text.toString().trim()
                val a = amount.text.toString().trim().toDoubleOrNull()
                if (d.isEmpty() || a == null || a <= 0) {
                    err.text = "Keterangan dan jumlah wajib diisi"
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val body = JSONObject()
                    .put("description", d)
                    .put("amount", a)
                    .put("category", cat.text.toString())
                if (existing == null) body.put("date", Fmt.isoUtc(midnightAt(LocalDate.now())))

                Api.io.execute {
                    val ok = if (existing == null) Api.addExpense(this, body)
                    else Api.updateExpense(this, existing.optString("id"), body)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (ok) { dlg.dismiss(); load() }
                        else { err.text = "Gagal menyimpan"; err.visibility = View.VISIBLE }
                    }
                }
            }
        }

        // hapus hanya untuk data yang sudah ada
        if (existing != null) {
            dlg.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Hapus") { _, _ ->
                confirmDeleteExpense(existing.optString("id"), existing.optString("description"))
            }
        }
        dlg.show()
    }

    private fun confirmDeleteExpense(id: String, desc: String) {
        AlertDialog.Builder(this)
            .setTitle("Hapus pengeluaran?")
            .setMessage("\"$desc\" akan dihapus permanen dan laba periode ini naik. Tidak bisa dibatalkan.")
            .setNegativeButton("Tidak", null)
            .setPositiveButton("Hapus") { _, _ ->
                Api.io.execute {
                    val ok = Api.deleteExpense(this, id)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (ok) load()
                        else android.widget.Toast
                            .makeText(this, "Gagal menghapus", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun midnightAt(d: LocalDate): Long =
        java.util.Calendar.getInstance().apply {
            set(d.year, d.monthValue - 1, d.dayOfMonth, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
}
