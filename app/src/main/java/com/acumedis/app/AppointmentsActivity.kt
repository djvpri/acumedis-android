package com.acumedis.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate

/**
 * Daftar jadwal per hari. Booking dibuat, diubah, dan diselesaikan di sini;
 * "Selesai" membuat jadwal jadi done, "Mulai sesi" langsung membuka form sesi.
 */
class AppointmentsActivity : AppCompatActivity() {

    private var date: LocalDate = LocalDate.now()
    private var rows: List<Booking> = emptyList()
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appointments)
        Log.screen("AppointmentsActivity")

        list = findViewById(R.id.list)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.add).setOnClickListener { newBooking() }
        findViewById<View>(R.id.emptyAdd).setOnClickListener { newBooking() }
        findViewById<View>(R.id.prevDay).setOnClickListener { shift(-1) }
        findViewById<View>(R.id.nextDay).setOnClickListener { shift(1) }
        // ketuk tanggal = lompat ke hari ini
        findViewById<View>(R.id.date).setOnClickListener {
            date = LocalDate.now()
            load()
        }
        load()
    }

    private fun shift(days: Long) {
        date = date.plusDays(days)
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.date).text = when (date) {
            LocalDate.now() -> "Hari ini · " + Fmt.waktuLengkap(midnight()).substringBefore(" ·")
            else -> Fmt.waktuLengkap(midnightAt(date)).substringBefore(" ·")
        }
        findViewById<TextView>(R.id.sub).text = "${rows.size} jadwal"

        Api.io.execute {
            val arr = Api.appointments(this, date.toString())
            val out = mutableListOf<Booking>()
            for (i in 0 until arr.length()) {
                val b = runCatching { Booking.from(arr.getJSONObject(i)) }.getOrNull() ?: continue
                if (b.status == "cancelled") continue
                out.add(b)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rows = out.sortedBy { it.at }
                findViewById<TextView>(R.id.sub).text = "${rows.size} jadwal"
                render()
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        findViewById<View>(R.id.empty).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        rows.forEach { b ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val p = (14 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                isClickable = true
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (10 * resources.displayMetrics.density).toInt()

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = b.waktu
                setTextColor(getColor(R.color.primary_deep))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (b.queue != null && b.queue > 0) top.addView(chip("A-%02d".format(b.queue), R.color.soft, R.color.primary_deep))
            top.addView(chip(
                when (b.status) {
                    "done" -> "Selesai"
                    "pending" -> "Menunggu konfirmasi"
                    else -> "Terjadwal"
                },
                if (b.status == "done") R.color.green_soft else R.color.amber_soft,
                if (b.status == "done") R.color.green else R.color.amber
            ))
            card.addView(top)

            card.addView(TextView(this).apply {
                text = b.name
                setTextColor(getColor(R.color.fg))
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })

            val bits = mutableListOf<String>()
            b.reason?.let { bits.add(it) }
            bits.add("${b.duration} menit")
            if (b.isNewPatient) b.externalPhone?.let { bits.add(it) }
            card.addView(TextView(this).apply {
                text = bits.joinToString(" · ")
                setTextColor(getColor(R.color.fg2))
                textSize = 12.5f
            })

            card.addView(TextView(this).apply {
                text = "Ketuk untuk ubah, selesai, atau batalkan"
                setTextColor(getColor(R.color.muted))
                textSize = 10.5f
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            })

            card.setOnClickListener { openSheet(b) }
            list.addView(card, lp)
        }
    }

    /** Aksi satu jadwal: mulai sesi, selesai, ubah, batal. */
    private fun openSheet(b: Booking) {
        val actions = mutableListOf<String>()
        if (b.patientId != null) actions.add("Mulai sesi terapi")
        if (b.status != "done") actions.add("Tandai selesai")
        actions.add("Ubah jadwal")
        actions.add("Batalkan jadwal")

        AlertDialog.Builder(this)
            .setTitle("${b.waktu} · ${b.name}")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Mulai sesi terapi" -> startSession(b)
                    "Tandai selesai" -> setStatus(b, "done")
                    "Ubah jadwal" -> BookingForm.show(this, b) { load() }
                    "Batalkan jadwal" -> confirmCancel(b)
                }
            }
            .show()
    }

    /**
     * Buka form sesi dengan pasien dan keluhan sudah terisi, lalu tandai
     * jadwalnya selesai. Ini yang menghilangkan langkah pilih-pasien manual.
     */
    private fun startSession(b: Booking) {
        val pid = b.patientId ?: return
        startActivity(
            Intent(this, SessionFormActivity::class.java)
                .putExtra("patient_id", pid)
                .putExtra("patient_name", b.name)
                .putExtra("complaint", b.reason ?: "")
                .putExtra("appointment_id", b.id)
        )
    }

    override fun onResume() {
        super.onResume()
        // status bisa berubah di layar lain (mis. sesi selesai menandai jadwal done)
        load()
    }

    private fun setStatus(b: Booking, status: String) {
        Api.io.execute {
            val (ok, msg) = Api.updateAppointment(this, b.id, org.json.JSONObject().put("status", status))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!ok) toast(msg ?: "Gagal memperbarui")
                load()
            }
        }
    }

    private fun confirmCancel(b: Booking) {
        AlertDialog.Builder(this)
            .setTitle("Batalkan jadwal ${b.name}?")
            .setMessage("Jadwal hilang dari daftar dan tidak dihitung di laporan. Catatan jadwalnya tetap tersimpan di server.")
            .setNegativeButton("Tidak", null)
            .setPositiveButton("Batalkan") { _, _ ->
                Api.io.execute {
                    val ok = Api.deleteAppointment(this, b.id)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (!ok) toast("Gagal membatalkan")
                        load()
                    }
                }
            }
            .show()
    }

    private fun newBooking() {
        BookingForm.show(this, null) { load() }
    }

    // ---------- util ----------

    private fun midnight(): Long = midnightAt(LocalDate.now())

    private fun midnightAt(d: LocalDate): Long =
        java.util.Calendar.getInstance().apply {
            set(d.year, d.monthValue - 1, d.dayOfMonth, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun chip(text: String, bg: Int, fg: Int) = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(getColor(fg))
        setBackgroundColor(getColor(bg))
        val p = (8 * resources.displayMetrics.density).toInt()
        val v = (3 * resources.displayMetrics.density).toInt()
        setPadding(p, v, p, v)
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
