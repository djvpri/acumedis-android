package com.acumedis.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cek update dari GitHub Releases repo publik (tanpa token, tanpa server sendiri).
 * Alur: fetch releases/latest → bandingkan versi → unduh asset .apk → buka installer sistem.
 */
object Updater {

    private const val API = "https://api.github.com/repos/djvpri/acumedis-android/releases/latest"

    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val pageUrl: String
    )

    /** true kalau [latest] lebih baru dari [current]. Tahan "v" prefix dan jumlah segmen beda. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun fetchLatest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "AcuMedis")
        return try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val page = o.optString("html_url")
            val notes = o.optString("body")
            val assets = o.optJSONArray("assets") ?: return null
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            if (url.isEmpty()) return null
            Release(o.optString("tag_name").removePrefix("v"), url, notes, page)
        } catch (e: Exception) {
            Log.error("update", "cek rilis gagal: ${e.javaClass.simpleName}", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File? {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "acumedis-${r.version}.apk")

        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "AcuMedis")
        return try {
            if (conn.responseCode != 200) null
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            out
        } catch (e: Exception) {
            Log.error("update", "unduh APK gagal: ${e.javaClass.simpleName}", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
