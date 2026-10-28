package com.acumedis.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.ProgressBar
import android.widget.Toast

/**
 * UI alur update: dialog versi baru → unduh dengan progres → buka pemasang.
 * Dipakai dari Pengaturan (tombol manual) dan dari beranda (cek otomatis).
 */
object Updates {

    fun toast(a: Activity, msg: String) =
        Toast.makeText(a, msg, Toast.LENGTH_LONG).show()

    /** Cek manual: selalu memberi tahu hasil, termasuk "sudah terbaru". */
    fun check(a: Activity, onDone: () -> Unit = {}) {
        Thread {
            val latest = Updater.fetchLatest()
            a.runOnUiThread {
                onDone()
                when {
                    latest == null -> toast(a, "Tidak dapat memeriksa update. Cek koneksi.")
                    Updater.isNewer(latest.version, BuildConfig.VERSION_NAME) -> offer(a, latest)
                    else -> toast(a, "Sudah versi terbaru (v${BuildConfig.VERSION_NAME})")
                }
            }
        }.start()
    }

    /** Cek otomatis: diam kalau sudah terbaru. */
    fun checkSilently(a: Activity) {
        Thread {
            val latest = Updater.fetchLatest() ?: return@Thread
            if (!Updater.isNewer(latest.version, BuildConfig.VERSION_NAME)) return@Thread
            a.runOnUiThread { offer(a, latest) }
        }.start()
    }

    fun offer(a: Activity, r: Updater.Release) {
        val notes = r.notes.lines().filter { it.isNotBlank() }.take(6)
            .joinToString("\n") { "• " + it.trim().removePrefix("-").trim() }
            .ifBlank { "Perbaikan dan peningkatan." }

        AlertDialog.Builder(a)
            .setTitle("Versi ${r.version} tersedia")
            .setMessage("Kamu memakai v${BuildConfig.VERSION_NAME}.\n\n$notes")
            .setNegativeButton("Nanti", null)
            .setPositiveButton("Unduh & pasang") { _, _ -> download(a, r) }
            .show()
    }

    private fun download(a: Activity, r: Updater.Release) {
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(pad, pad, pad, pad)
        }
        val dlg = AlertDialog.Builder(a)
            .setTitle("Mengunduh v${r.version}")
            .setView(bar)
            .setCancelable(false)
            .setNegativeButton("Batal", null)
            .create()
        dlg.show()

        Thread {
            val apk = Updater.download(a, r) { pct -> a.runOnUiThread { bar.progress = pct } }
            a.runOnUiThread {
                dlg.dismiss()
                if (apk == null) {
                    toast(a, "Unduhan gagal. Coba lagi.")
                } else {
                    runCatching { Updater.install(a, apk) }.onFailure {
                        toast(a, "Tidak bisa membuka pemasang. Aktifkan \"Instal aplikasi tak dikenal\" untuk AcuMedis.")
                    }
                }
            }
        }.start()
    }
}
