package com.acumedis.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Nota kwitansi klinik, gaya printer thermal: 32 kolom monospace, lebar 384 px
 * (58 mm @ 203 dpi). Satu bitmap dipakai untuk dua tujuan — share PNG ke
 * WhatsApp dan cetak (PDF lewat PrintManager, jadi printer thermal apa pun yang
 * terpasang di Android bisa dipakai, termasuk via aplikasi vendor).
 */
object Nota {

    private const val W = 384
    private const val PAD = 14
    private const val COLS = 32
    private const val LINE_H = 26
    private const val TEXT = 19f
    private const val TOP = 18
    private const val BOTTOM = 22

    private fun center(s: String): String {
        val t = s.take(COLS)
        val off = (COLS - t.length) / 2
        return " ".repeat(maxOf(0, off)) + t
    }

    private fun row(l: String, r: String): String {
        val space = COLS - l.length - r.length
        return if (space >= 1) l + " ".repeat(space) + r
        else l.take(maxOf(0, COLS - r.length - 1)) + " " + r
    }

    private fun wrap(s: String, width: Int = COLS): List<String> {
        if (s.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (word in s.trim().split(" ")) {
            if (cur.isEmpty()) {
                if (word.length <= width) cur.append(word) else {
                    var rest = word
                    while (rest.length > width) { out += rest.substring(0, width); rest = rest.substring(width) }
                    cur.append(rest)
                }
            } else if (cur.length + 1 + word.length <= width) {
                cur.append(' ').append(word)
            } else {
                out += cur.toString(); cur = StringBuilder(word)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun money(v: Long): String = String.format(Locale("id", "ID"), "%,d", v).replace(',', '.')

    fun money(v: Double?): String = money((v ?: 0.0).toLong())

    fun receiptNo(v: Visit): String {
        val day = v.date.take(10).replace("-", "").takeLast(6)
        return "AM-$day-${v.id.takeLast(4).uppercase()}"
    }

    /** Isi nota sebagai baris teks 32 kolom. */
    fun lines(patient: Patient, v: Visit, clinic: String, address: String?, phone: String?): List<String> {
        val sep = "-".repeat(COLS)
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yy", Locale("id", "ID"))
        val tanggal = runCatching { java.time.LocalDate.parse(v.date.take(10)).format(fmt) }
            .getOrDefault(v.date.take(10))

        val b = mutableListOf<String>()
        b += center(clinic.ifBlank { "KLINIK" }.uppercase())
        if (!address.isNullOrBlank()) wrap(address).forEach { b += center(it) }
        if (!phone.isNullOrBlank()) b += center("Telp $phone")
        b += sep
        b += row("No.", receiptNo(v))
        b += row("Tgl cetak", now)
        b += row("Tgl terapi", tanggal)
        b += sep
        b += wrap("Pasien : ${patient.name}")
        if (!patient.phone.isNullOrBlank()) b += wrap("HP     : ${patient.phone}")
        b += sep
        b += wrap("Tindakan: ${v.complaint}")
        if (!v.diagnosis.isNullOrBlank()) b += wrap("Diagnosa: ${v.diagnosis}")
        if (v.points.isNotEmpty()) wrap("Titik   : ${v.points.joinToString(", ")}").forEach { b += it }
        if (v.duration != null) b += row("Durasi", "${v.duration} menit")
        b += sep
        b += row("BIAYA", "Rp " + money(v.fee))
        b += row("BAYAR", "Rp " + money(v.fee))
        b += row("KEMBALI", "Rp 0")
        b += sep
        b += center(if (v.payment == "paid") "LUNAS" else "BELUM BAYAR")
        b += center("Terima kasih")
        return b
    }

    fun build(patient: Patient, v: Visit, clinic: String, address: String?, phone: String?): Bitmap {
        val rows = lines(patient, v, clinic, address, phone)
        val h = TOP + rows.size * LINE_H + BOTTOM
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT
            typeface = Typeface.MONOSPACE
        }
        rows.forEachIndexed { i, line ->
            c.drawText(line, PAD.toFloat(), (TOP + (i + 1) * LINE_H - 7).toFloat(), p)
        }
        return bmp
    }

    fun png(ctx: Context, bmp: Bitmap, name: String): File {
        val dir = File(ctx.cacheDir, "nota").apply { mkdirs() }
        val f = File(dir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun uri(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    /** Bagikan gambar nota; [toWhatsapp] langsung ke aplikasi WhatsApp kalau terpasang. */
    fun share(ctx: Context, f: File, toWhatsapp: Boolean) {
        // FileProvider bisa gagal (path tak terdaftar) → jangan biarkan app mati
        val shareUri = runCatching { uri(ctx, f) }.getOrNull() ?: run {
            android.widget.Toast.makeText(ctx, "Gagal menyiapkan gambar nota", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TEXT, "Nota terapi")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val target = if (toWhatsapp) Intent(send).setPackage("com.whatsapp") else send
        runCatching { ctx.startActivity(target) }
            .onFailure { ctx.startActivity(Intent.createChooser(send, "Kirim nota")) }
    }

    /** Cetak: PrintManager Android, tanpa margin, skala menyesuaikan lebar kertas. */
    fun print(ctx: Context, bmp: Bitmap, jobName: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()

        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?,
                new: PrintAttributes?,
                cancel: CancellationSignal?,
                cb: LayoutResultCallback,
                extras: Bundle?
            ) {
                cb.onLayoutFinished(
                    PrintDocumentInfo.Builder("$jobName.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build(), true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                dest: ParcelFileDescriptor,
                cancel: CancellationSignal?,
                cb: WriteResultCallback
            ) {
                val pdf = PrintedPdfDocument(ctx, attrs)
                try {
                    val page = pdf.startPage(0)
                    val cv = page.canvas
                    val margin = 24f
                    val availW = cv.width - margin * 2
                    val scale = minOf(1f, availW / bmp.width)
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    val dst = android.graphics.RectF(
                        (cv.width - dw) / 2f, margin, (cv.width + dw) / 2f, margin + dh
                    )
                    cv.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                    FileOutputStream(dest.fileDescriptor).use { pdf.writeTo(it) }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed(e.message)
                } finally {
                    pdf.close()
                }
            }
        }, attrs)
    }
}
