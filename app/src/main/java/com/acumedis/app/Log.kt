package com.acumedis.app

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Log error ke server: crash fatal + kegagalan non-fatal (jaringan/parse).
 *
 * - Antrean di filesDir/errorlog.jsonl (append satu baris JSON per entri),
 *   tahan app mati mendadak, cap 200 entri / 512 KB.
 * - Dikirim batch maks 50 tiap app start dan setelah error non-fatal.
 * - clientId unik → retry tidak menggandakan baris di server.
 * - PII (nama pasien, no HP, email, catatan) di-scrub SEBELUM ditulis ke antrean.
 */
object Log {

    private const val FILE = "errorlog.jsonl"
    private const val MAX_ENTRIES = 200
    private const val MAX_BYTES = 512 * 1024
    private const val BATCH = 50

    private lateinit var app: Application
    private var screen = ""

    fun init(a: Application) {
        app = a
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("fatal", "crash", e.message ?: e.javaClass.name, stack(e)) }
            // serahkan ke handler lama supaya dialog crash & Play Console tetap jalan
            if (prev != null) prev.uncaughtException(t, e) else android.os.Process.killProcess(android.os.Process.myPid())
        }
        // kirim sisa antrean dari sesi sebelumnya
        Thread { runCatching { flush() } }.start()
    }

    /** Catat layar yang sedang aktif, ikut terkirim saat crash. */
    fun screen(name: String) { screen = name }

    /** Error non-fatal: jaringan gagal, JSON tak terbaca, dan sejenisnya. */
    fun error(kind: String, message: String, e: Throwable? = null) {
        if (!::app.isInitialized) return
        runCatching { write("error", kind, message, e?.let { stack(it) }) }
        Thread { runCatching { flush() } }.start()
    }

    fun pending(): Int = runCatching { queue().readLines().count { it.isNotBlank() } }.getOrDefault(0)

    /** Antrean terakhir, untuk layar Diagnostik. */
    fun tail(n: Int = 20): List<String> = runCatching {
        queue().readLines().filter { it.isNotBlank() }.takeLast(n).mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            "${o.optString("occurredAt").take(19).replace('T', ' ')}  ${o.optString("level")}/${o.optString("kind")}  ${o.optString("message")}"
        }
    }.getOrDefault(emptyList())

    fun clearQueue() { runCatching { queue().writeText("") } }

    /** Kirim antrean sekarang. @return jumlah entri yang diterima server. */
    fun flush(): Int {
        if (!::app.isInitialized) return 0
        val base = Api.baseUrl(app)
        if (base.isBlank()) return 0

        val lines = runCatching { queue().readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (lines.isEmpty()) return 0

        var sent = 0
        lines.chunked(BATCH).forEach { chunk ->
            if (post(base, chunk)) sent += chunk.size
        }
        if (sent > 0) trim(sent)
        return sent
    }

    // ---------- internal ----------

    private fun post(base: String, lines: List<String>): Boolean {
        var c: HttpURLConnection? = null
        return try {
            val entries = JSONArray()
            lines.forEach { entries.put(JSONObject(it)) }
            val body = JSONObject()
                .put("deviceId", deviceId())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("entries", entries)

            c = (URL("$base/v1/logs").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Device-Id", deviceId().take(120))
                setRequestProperty("User-Agent", "AcuMedis/${BuildConfig.VERSION_NAME}")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }

    /** Buang entri yang sudah terkirim + jaga cap ukuran/jumlah. */
    private fun trim(sent: Int) {
        runCatching {
            val f = queue()
            val rest = f.readLines().filter { it.isNotBlank() }.drop(sent)
            val kept = rest.takeLast(MAX_ENTRIES)
            var out = kept
            while (out.isNotEmpty() && out.joinToString("\n").length > MAX_BYTES) {
                out = out.drop(1) // buang yang tertua
            }
            f.writeText(out.joinToString("\n", postfix = if (out.isEmpty()) "" else "\n"))
        }
    }

    private fun write(level: String, kind: String, message: String, trace: String?) {
        val o = JSONObject()
            .put("clientId", UUID.randomUUID().toString())
            .put("level", level)
            .put("kind", kind)
            .put("message", Scrub.clean(message).take(2000))
            .put("stackTrace", Scrub.clean(trace ?: "").take(8000))
            .put("screen", screen)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("androidVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("baseUrl", Api.baseUrl(app))
            .put("occurredAt", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString())

        val f = queue()
        f.appendText(o.toString() + "\n")
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.size > MAX_ENTRIES) f.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
    }

    private fun queue(): File = File(app.filesDir, FILE)

    private fun deviceId(): String {
        val p = app.getSharedPreferences("acumedis", Context.MODE_PRIVATE)
        p.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }

    private fun stack(e: Throwable): String =
        e.stackTrace.take(40).joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            .let { "${e.javaClass.name}: ${e.message}\n$it" }
}

/** Buang data pribadi sebelum log meninggalkan HP. */
object Scrub {
    private val phone = Regex("""(\+?62|0)8[0-9][0-9\-\s]{6,12}""")
    private val email = Regex("""[\w.+-]+@[\w-]+\.[\w.]{2,}""")
    // nama setelah label yang umum di app ini
    private val labelled = Regex("""(?i)("?)(nama_pasien|nama|patient|nama klinik|clinicName)("?\s*[:=]\s*)("?)([^",}\n]{2,40})""")

    fun clean(text: String): String = text
        .replace(phone, "***")
        .replace(email, "***")
        .replace(labelled) { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}\"***\"" }
}
