package com.acumedis.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.net.URL

/**
 * Panel detail satu sesi terapi: dibuka dengan mengetuk baris riwayat.
 *
 * Isinya rekam medis + foto lidah + saran AI, dan aksi yang paling sering
 * dipakai: tandai lunas, cetak nota, ubah, hapus.
 */
object SessionSheet {

    fun show(act: Activity, patient: Patient, v: Visit, onChanged: () -> Unit) {
        val d = BottomSheetDialog(act)
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // --- kepala: tanggal + status bayar ---
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(act).apply {
                text = Fmt.tanggal(v.date)
                setTextColor(act.getColor(R.color.fg))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(chip(act, if (v.payment == "paid") "Lunas" else "Belum"))
        col.addView(head)

        col.addView(text(act, v.complaint, 13.5f, R.color.fg2, top = 10))

        // --- rekam medis ---
        v.diagnosis?.let { col.addView(label(act, "Diagnosis")); col.addView(text(act, it, 13f, R.color.fg2)) }
        if (v.points.isNotEmpty()) {
            col.addView(label(act, "Titik terapi"))
            col.addView(text(act, v.points.joinToString(", "), 13f, R.color.fg2))
        }
        v.tongueColor?.let { col.addView(label(act, "Lidah")); col.addView(text(act, it, 13f, R.color.fg2)) }

        val meta = mutableListOf<String>()
        v.duration?.let { meta.add("$it menit") }
        v.painScale?.let { meta.add("nyeri $it/10") }
        if (meta.isNotEmpty()) {
            col.addView(label(act, "Pelaksanaan"))
            col.addView(text(act, meta.joinToString(" · "), 13f, R.color.fg2))
        }
        col.addView(label(act, "Biaya"))
        col.addView(
            text(act, Fmt.rupiah(v.fee), 14f, R.color.fg, bold = true)
        )
        // sesi yang dibayar bertahap harus terlihat sisanya, bukan cuma "sebagian"
        if (v.paidAmount > 0 && v.balance > 0) {
            col.addView(text(act, "Dibayar " + Fmt.rupiah(v.paidAmount) + " · sisa " + Fmt.rupiah(v.balance), 12f, R.color.amber))
        }
        v.notes?.let { col.addView(label(act, "Catatan")); col.addView(text(act, it, 13f, R.color.fg2)) }

        // --- foto lidah ---
        val photo = v.photos.firstOrNull()
        if (photo != null) {
            col.addView(label(act, "Foto lidah"))
            val img = ImageView(act).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(act.getColor(R.color.border))
                contentDescription = "Foto lidah"
            }
            val h = (190 * act.resources.displayMetrics.density).toInt()
            col.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            loadImage(act, Api.baseUrl(act) + photo.url, img) { bitmap ->
                img.setOnClickListener { zoom(act, bitmap) }
            }
        }

        // --- saran AI ---
        val ai = v.ai
        if (ai != null && (ai.tongue.isNotBlank() || ai.treatment.isNotBlank())) {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_ai)
                val p = (12 * act.resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
            box.addView(text(act, "Saran AI", 11f, R.color.muted, bold = true))
            if (ai.tongue.isNotBlank()) box.addView(text(act, "Lidah: " + ai.tongue, 12.5f, R.color.fg2))
            if (ai.diagnosis.isNotBlank()) box.addView(text(act, "Pola: " + ai.diagnosis, 12.5f, R.color.fg2))
            if (ai.treatment.isNotBlank()) box.addView(text(act, "Terapi: " + ai.treatment, 12.5f, R.color.fg2))
            if (ai.points.isNotEmpty()) box.addView(text(act, "Titik: " + ai.points.joinToString(", "), 12.5f, R.color.fg2))
            box.addView(text(act, "Saran bantu, bukan diagnosis final.", 10.5f, R.color.muted))
            col.addView(box, lp(act, 12))
        }

        // --- aksi ---
        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(
            button(act, if (v.payment == "paid") "Sudah lunas" else "Bayar", primary = v.payment != "paid") {
                if (v.payment == "paid") {
                    toast(act, "Sesi ini sudah lunas")
                } else {
                    PaySheet.show(act, v) { d.dismiss(); onChanged() }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        )
        row1.addView(button(act, "Nota") { d.dismiss(); NotaSheet.show(act, patient, v) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row1, lp(act, 16))

        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button(act, "Ubah") {
            d.dismiss()
            SessionFormActivity.startEdit(act, v.id)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        row2.addView(button(act, "Hapus", danger = true) { confirmDelete(act, d, v, onChanged) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row2, lp(act, 8))

        val scroll = ScrollView(act).apply { addView(col) }
        d.setContentView(scroll)
        d.show()
    }

    /**
     * Hapus = soft delete. Rekam medis tetap tersimpan di server, hanya hilang
     * dari daftar — karena itu teksnya menjelaskan hal itu, bukan sekadar "Yakin?".
     */
    private fun confirmDelete(act: Activity, sheet: BottomSheetDialog, v: Visit, onChanged: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle("Hapus sesi ${Fmt.tanggal(v.date)}?")
            .setMessage("Sesi ini hilang dari riwayat dan tidak dihitung di laporan. Catatan medisnya tetap tersimpan di server dan masih bisa dipulihkan lewat admin.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                Api.io.execute {
                    val ok = Api.deleteSession(act, v.id)
                    act.runOnUiThread {
                        if (act.isFinishing) return@runOnUiThread
                        toast(act, if (ok) "Sesi dihapus" else "Gagal menghapus")
                        if (ok) { sheet.dismiss(); onChanged() }
                    }
                }
            }
            .show()
    }

    // ---------- komponen kecil ----------

    private fun lp(act: Context, top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = (top * act.resources.displayMetrics.density).toInt()
        return p
    }

    private fun label(act: Context, s: String) = TextView(act).apply {
        text = s
        setTextColor(act.getColor(R.color.muted))
        textSize = 11f
        setPadding(0, (14 * act.resources.displayMetrics.density).toInt(), 0, 2)
    }

    private fun text(act: Context, s: String, size: Float, color: Int, top: Int = 0, bold: Boolean = false) =
        TextView(act).apply {
            text = s
            setTextColor(act.getColor(color))
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (top > 0) setPadding(0, (top * act.resources.displayMetrics.density).toInt(), 0, 0)
        }

    private fun chip(act: Context, s: String) = TextView(act).apply {
        text = s
        textSize = 11.5f
        setTextColor(act.getColor(if (s == "Lunas") R.color.green else R.color.amber))
        setBackgroundResource(if (s == "Lunas") R.drawable.bg_pill else R.drawable.bg_pill_amber)
        val p = (10 * act.resources.displayMetrics.density).toInt()
        setPadding(p, p / 3, p, p / 3)
    }

    private fun button(act: Context, s: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
        TextView(act).apply {
            text = s
            textSize = 13f
            gravity = Gravity.CENTER
            val pad = (14 * act.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            minHeight = (44 * act.resources.displayMetrics.density).toInt()
            when {
                primary -> {
                    setBackgroundResource(R.drawable.bg_btn)
                    setTextColor(android.graphics.Color.WHITE)
                }
                danger -> {
                    setBackgroundResource(R.drawable.bg_soft_red)
                    setTextColor(act.getColor(R.color.red))
                }
                else -> {
                    setBackgroundResource(R.drawable.bg_chip)
                    setTextColor(act.getColor(R.color.fg2))
                }
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Foto diunduh di thread latar; /uploads tidak butuh token. */
    private fun loadImage(act: Activity, url: String, into: ImageView, then: (Bitmap) -> Unit) {
        Api.io.execute {
            val bmp = runCatching {
                (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                into.setImageBitmap(bmp)
                then(bmp)
            }
        }
    }

    private fun zoom(act: Activity, bmp: Bitmap) {
        val img = ImageView(act).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val d = AlertDialog.Builder(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(img)
            .create()
        img.setOnClickListener { d.dismiss() }
        d.show()
    }

    private fun toast(act: Activity, s: String) =
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
