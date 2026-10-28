package com.acumedis.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pratinjau nota + aksi cetak & kirim WhatsApp.
 * Satu bitmap 384 px dipakai dua-duanya, jadi yang dikirim ke WA sama persis
 * dengan yang tercetak.
 */
object NotaSheet {

    fun show(ctx: Context, patient: Patient, v: Visit) {
        val clinic = Creds.clinicName(ctx).ifBlank { Api.clinicName(ctx) }
        val bmp = Nota.build(patient, v, clinic, Creds.clinicAddress(ctx), Creds.clinicPhone(ctx))
        val pad = (18 * ctx.resources.displayMetrics.density).toInt()

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val img = ImageView(ctx).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        col.addView(
            img,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val size = TextView(ctx).apply {
            text = "Lebar 384 px (58 mm) — pas untuk printer thermal 58/80 mm"
            setTextColor(ctx.getColor(R.color.muted))
            textSize = 10.5f
            setPadding(0, pad / 2, 0, 0)
        }
        col.addView(size)

        val scroll = ScrollView(ctx).apply { addView(col) }

        val dlg = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setNegativeButton("Tutup", null)
            .setNeutralButton("WhatsApp", null)
            .setPositiveButton("Cetak", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val f = Nota.png(ctx, bmp, "nota-${v.id}.png")
                Nota.share(ctx, f, toWhatsapp = true)
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Nota.print(ctx, bmp, "Nota ${patient.name}")
            }
        }
        dlg.show()
    }

    /** Pratinjau tanpa aksi (dipakai kalau nanti butuh lihat saja). */
    fun preview(ctx: Context, bmp: Bitmap): ImageView = ImageView(ctx).apply {
        setImageBitmap(bmp)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
}
