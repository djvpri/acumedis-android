package com.acumedis.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Klien HTTP ke server AcuMedis (NextAuth cookie-based). Tanpa dependensi tambahan. */
object Api {

    private const val TIMEOUT = 15_000
    val io = Executors.newFixedThreadPool(3)

    /** Simpan cookie sesi next-auth antar request. */
    private val cookies = HashMap<String, String>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("acumedis", Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String = prefs(ctx).getString("base_url", "")?.trimEnd('/') ?: ""

    fun setBaseUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("base_url", url.trim().trimEnd('/')).apply()
        cookies.clear()
    }

    fun saveCookies(ctx: Context) {
        val sb = StringBuilder()
        cookies.forEach { (k, v) -> sb.append(k).append('=').append(v).append(';') }
        prefs(ctx).edit().putString("cookies", sb.toString()).apply()
    }

    fun loadCookies(ctx: Context) {
        cookies.clear()
        val raw = prefs(ctx).getString("cookies", "") ?: return
        raw.split(';').forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) cookies[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
    }

    fun isLoggedIn(ctx: Context): Boolean {
        loadCookies(ctx)
        return baseUrl(ctx).isNotEmpty() && cookies.keys.any { it.contains("session-token") }
    }

    fun logout(ctx: Context) {
        cookies.clear()
        prefs(ctx).edit().remove("cookies").apply()
    }

    fun userName(ctx: Context): String = prefs(ctx).getString("user_name", "") ?: ""

    private fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun absorbCookies(conn: HttpURLConnection) {
        conn.headerFields?.forEach { (k, v) ->
            if (k != null && k.equals("Set-Cookie", true)) {
                v.forEach { c ->
                    val first = c.substringBefore(';')
                    val i = first.indexOf('=')
                    if (i > 0) cookies[first.substring(0, i).trim()] = first.substring(i + 1).trim()
                }
            }
        }
    }

    private fun open(ctx: Context, path: String, method: String): HttpURLConnection {
        val base = baseUrl(ctx)
        require(base.isNotEmpty()) { "Server belum diatur" }
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AcuMedis/${BuildConfig.VERSION_NAME}")
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookieHeader())
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return conn
    }

    fun get(ctx: Context, path: String): String? = runCatching {
        val conn = open(ctx, path, "GET")
        absorbCookies(conn)
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code in 200..299) text else null
    }.getOrNull()

    fun post(ctx: Context, path: String, body: JSONObject): String? = runCatching {
        val conn = open(ctx, path, "POST")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        absorbCookies(conn)
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code in 200..299) text else null
    }.getOrNull()

    /** Login NextAuth: ambil csrf → POST callback credentials. */
    fun login(ctx: Context, email: String, password: String): String? {
        val csrfRaw = get(ctx, "/api/auth/csrf") ?: return "Server tidak merespons"
        val csrf = runCatching { JSONObject(csrfRaw).optString("csrfToken") }.getOrNull()
        if (csrf.isNullOrBlank()) return "Gagal mengambil token CSRF"

        val body = buildString {
            append("csrfToken=").append(URLEncoder.encode(csrf, "UTF-8"))
            append("&email=").append(URLEncoder.encode(email, "UTF-8"))
            append("&password=").append(URLEncoder.encode(password, "UTF-8"))
            append("&json=true")
        }

        conn0@ run {
            val conn = open(ctx, "/api/auth/callback/credentials?json=true", "POST")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            absorbCookies(conn)
            val code = conn.responseCode
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
        }

        saveCookies(ctx)
        val me = get(ctx, "/api/me")
        if (me == null) return "Email atau password salah"
        runCatching {
            val o = JSONObject(me)
            val name = o.optString("name").ifBlank { email }
            prefs(ctx).edit().putString("user_name", name).apply()
        }
        return null
    }

    // ---------- endpoint helpers ----------

    fun patients(ctx: Context): JSONArray {
        val raw = get(ctx, "/api/patients") ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun sessions(ctx: Context): JSONArray {
        val raw = get(ctx, "/api/sessions") ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun appointments(ctx: Context): JSONArray {
        val raw = get(ctx, "/api/appointments") ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun createSession(ctx: Context, body: JSONObject): Boolean = post(ctx, "/api/sessions", body) != null

    fun createPatient(ctx: Context, body: JSONObject): Boolean = post(ctx, "/api/patients", body) != null
}

/** Model ringan hasil parsing JSON. */
data class Patient(
    val id: String,
    val name: String,
    val gender: String?,
    val phone: String?,
    val birthDate: String?,
    val totalSessions: Int,
    val lastSession: String?
) {
    fun initials(): String = name.trim().split(" ").filter { it.isNotBlank() }
        .take(2).joinToString("") { it.first().uppercase() }

    companion object {
        fun from(o: JSONObject) = Patient(
            id = o.optString("id"),
            name = o.optString("name"),
            gender = o.optString("gender").ifBlank { null },
            phone = o.optString("phone").ifBlank { null },
            birthDate = o.optString("birth_date").ifBlank { null },
            totalSessions = o.optInt("total_sessions", 0),
            lastSession = o.optString("last_session_date").ifBlank { null }
        )
    }
}

data class Visit(
    val id: String,
    val patientId: String,
    val patientName: String,
    val date: String,
    val complaint: String,
    val diagnosis: String?,
    val points: List<String>,
    val duration: Int?,
    val fee: Double?,
    val payment: String
) {
    companion object {
        fun from(o: JSONObject): Visit {
            val arr = o.optJSONArray("points_used")
            val points = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) points.add(arr.optString(i))
            val p = o.optJSONObject("patient")
            return Visit(
                id = o.optString("id"),
                patientId = o.optString("patient_id"),
                patientName = p?.optString("name") ?: "",
                date = o.optString("session_date").take(10),
                complaint = o.optString("chief_complaint"),
                diagnosis = o.optString("tcm_diagnosis").ifBlank { null },
                points = points,
                duration = if (o.isNull("duration_minutes")) null else o.optInt("duration_minutes"),
                fee = if (o.isNull("fee")) null else o.optDouble("fee"),
                payment = o.optString("payment_status")
            )
        }
    }
}

/** Format tanggal ISO → "24 Okt 2026". */
object Fmt {
    private val bulan = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
        "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    fun tanggal(iso: String?): String {
        if (iso.isNullOrBlank() || iso.length < 10) return "-"
        val y = iso.substring(0, 4).toIntOrNull() ?: return iso
        val m = iso.substring(5, 7).toIntOrNull() ?: return iso
        val d = iso.substring(8, 10).toIntOrNull() ?: return iso
        return "$d ${bulan[m - 1]} $y"
    }

    fun pendek(iso: String?): String {
        if (iso.isNullOrBlank() || iso.length < 10) return "-"
        val m = iso.substring(5, 7).toIntOrNull() ?: return iso
        val d = iso.substring(8, 10).toIntOrNull() ?: return iso
        return "$d ${bulan[m - 1]}"
    }

    fun rupiah(v: Double?): String {
        if (v == null) return "-"
        val n = v.toLong()
        return "Rp " + n.toString().reversed().chunked(3).joinToString(".").reversed()
    }
}
