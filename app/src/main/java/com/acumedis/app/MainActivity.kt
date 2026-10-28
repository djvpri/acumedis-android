package com.acumedis.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate

private data class QueueRow(
    val time: String,
    val name: String,
    val reason: String,
    val tag: String,
    val done: Boolean,
    val patientId: String?
)

class MainActivity : AppCompatActivity() {

    private lateinit var queue: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.screen("MainActivity")

        queue = findViewById(R.id.queueList)

        val name = Api.userName(this).ifBlank { "Praktisi" }
        findViewById<TextView>(R.id.userName).text = name.take(24)
        findViewById<TextView>(R.id.avatar).text =
            name.trim().split(" ").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }.ifBlank { "A" }

        findViewById<View>(R.id.qPatients).setOnClickListener {
            startActivity(Intent(this, PatientsActivity::class.java))
        }
        findViewById<View>(R.id.qSession).setOnClickListener {
            startActivity(Intent(this, SessionFormActivity::class.java))
        }
        findViewById<View>(R.id.qRefresh).setOnClickListener { load() }
        findViewById<View>(R.id.seeAll).setOnClickListener {
            startActivity(Intent(this, PatientsActivity::class.java))
        }
        findViewById<View>(R.id.logout).setOnClickListener { confirmLogout() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        Updates.checkSilently(this)
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
                        android.widget.Toast.makeText(
                            this, if (sent > 0) "$sent log terkirim" else "Belum ada yang terkirim",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
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

    override fun onResume() {
        super.onResume()
        if (!Api.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // isi identitas klinik dari server (dipakai di kepala nota)
        Thread {
            val me = Api.me(this) ?: return@Thread
            val t = me.optJSONObject("tenant") ?: return@Thread
            Creds.saveClinic(
                this,
                t.str("clinicName") ?: t.str("name") ?: "",
                t.str("address") ?: "",
                t.str("phone") ?: ""
            )
        }.start()

        load()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Keluar akun?")
            .setMessage("Sesi di perangkat ini dihapus. Data tetap aman di server.")
            .setNegativeButton("Batal", null)
            .setNeutralButton("Keluar & hapus sandi") { _, _ ->
                Api.logout(this)
                Creds.clear(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setPositiveButton("Keluar") { _, _ ->
                Api.logout(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .show()
    }

    private fun load() {
        val today = LocalDate.now().toString()
        Api.io.execute {
            val appts = Api.appointments(this, today)
            val sum = Api.summary(this)

            val rows = mutableListOf<QueueRow>()
            for (i in 0 until appts.length()) {
                val o = appts.getJSONObject(i)
                val status = o.optString("status")
                if (status == "cancelled") continue

                val at = o.optString("scheduled_at")
                val qn = if (o.isNull("queue_number")) 0 else o.optInt("queue_number")
                val time = if (at.length >= 16) at.substring(11, 16) else "-"
                val isDone = status == "done"
                val tag = when {
                    isDone -> "Selesai"
                    qn > 0 -> "A-%02d".format(qn)
                    else -> "Antre"
                }
                rows.add(
                    QueueRow(
                        time,
                        o.optString("patient_name").ifBlank {
                            o.optString("external_name").ifBlank { "Pasien" }
                        },
                        o.optString("reason").ifBlank { "Konsultasi" },
                        tag, isDone,
                        o.optString("patient_id").ifBlank { null }
                    )
                )
            }

            val sorted = rows.sortedBy { it.time }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread

                val total = sorted.size
                val done = sum?.optInt("appointments_done", 0) ?: sorted.count { it.done }
                val waiting = sum?.optInt("appointments_waiting", 0) ?: (total - done)
                val unpaid = sum?.optInt("unpaid_count", 0) ?: 0

                findViewById<TextView>(R.id.todayTotal).text = "$total pasien"
                findViewById<TextView>(R.id.statDone).text = done.toString()
                findViewById<TextView>(R.id.statWaiting).text = waiting.toString()
                findViewById<TextView>(R.id.statUnpaid).text = unpaid.toString()

                queue.removeAllViews()
                findViewById<View>(R.id.empty).visibility =
                    if (sorted.isEmpty()) View.VISIBLE else View.GONE

                sorted.take(4).forEach { r ->
                    val v = LayoutInflater.from(this)
                        .inflate(R.layout.item_queue, queue, false)
                    v.findViewById<TextView>(R.id.time).text = r.time
                    v.findViewById<TextView>(R.id.name).text = r.name
                    v.findViewById<TextView>(R.id.sub).text = r.reason
                    val tag = v.findViewById<TextView>(R.id.tag)
                    tag.text = r.tag
                    tag.backgroundTintList = getColorStateList(
                        if (r.done) R.color.green_soft else R.color.soft
                    )
                    tag.setTextColor(getColor(if (r.done) R.color.green else R.color.primary_deep))
                    v.setOnClickListener {
                        val pid = r.patientId
                        if (pid != null) {
                            startActivity(
                                Intent(this, PatientDetailActivity::class.java)
                                    .putExtra("patient_id", pid)
                                    .putExtra("patient_name", r.name)
                            )
                        } else {
                            startActivity(Intent(this, SessionFormActivity::class.java))
                        }
                    }
                    queue.addView(v)
                }
            }
        }
    }
}

