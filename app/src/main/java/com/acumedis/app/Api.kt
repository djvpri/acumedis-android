package com.acumedis.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Klien acumedis-api (Fastify + JWT).
 * Auth: access token (30 menit) + refresh token (30 hari, rotasi).
 * Tanpa dependensi jaringan tambahan — HttpURLConnection saja.
 */
object Api {

    private const val TIMEOUT = 20_000
    val io = Executors.newFixedThreadPool(3)

    /** Server bawaan; dipakai kalau pengguna belum pernah mengisi alamat. */
    const val DEFAULT_BASE = "https://fctqy79kuiw9c7cddjfdcsgg.103.93.129.94.sslip.io"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("acumedis", Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString("base_url", BuildConfig.BASE_URL)?.trimEnd('/') ?: ""

    fun setBaseUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("base_url", url.trim().trimEnd('/'))
            .remove("access_token").remove("refresh_token").apply()
    }

    fun accessToken(ctx: Context): String = prefs(ctx).getString("access_token", "") ?: ""
    fun refreshToken(ctx: Context): String = prefs(ctx).getString("refresh_token", "") ?: ""
    fun userName(ctx: Context): String = prefs(ctx).getString("user_name", "") ?: ""
    fun clinicName(ctx: Context): String = prefs(ctx).getString("clinic_name", "") ?: ""

    fun isLoggedIn(ctx: Context): Boolean =
        baseUrl(ctx).isNotEmpty() && (accessToken(ctx).isNotEmpty() || refreshToken(ctx).isNotEmpty())

    fun logout(ctx: Context) {
        val rt = refreshToken(ctx)
        if (rt.isNotEmpty()) {
            io.execute { runCatching { postPublic(ctx, "/v1/auth/logout", JSONObject().put("refreshToken", rt)) } }
        }
        prefs(ctx).edit().remove("access_token").remove("refresh_token")
            .remove("user_name").remove("clinic_name").apply()
    }

    // ---------- HTTP dasar ----------

    private fun conn(ctx: Context, path: String, method: String, auth: Boolean): HttpURLConnection {
        val base = baseUrl(ctx)
        require(base.isNotEmpty()) { "Alamat server belum diatur" }
        return (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AcuMedis/${BuildConfig.VERSION_NAME}")
            if (auth) {
                val t = accessToken(ctx)
                if (t.isNotEmpty()) setRequestProperty("Authorization", "Bearer $t")
            }
            if (method != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
    }

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun request(
        ctx: Context, path: String, method: String,
        body: JSONObject? = null, auth: Boolean = true, retried: Boolean = false
    ): String? {
        var c: HttpURLConnection? = null
        return try {
            c = conn(ctx, path, method, auth)
            if (body != null && method != "GET") c.outputStream.use { it.write(body.toString().toByteArray()) }
            val (code, text) = read(c)

            // access token kedaluwarsa → coba refresh sekali
            if (code == 401 && auth && !retried && refresh(ctx)) {
                return request(ctx, path, method, body, auth, retried = true)
            }
            JSONObject(text)
            if (code in 200..299) text else null
        } catch (e: Exception) {
            Log.error("network", "request $method $path gagal: ${e.javaClass.simpleName}", e)
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Rotasi token. True kalau dapat access token baru. */
    fun refresh(ctx: Context): Boolean {
        val rt = refreshToken(ctx)
        if (rt.isEmpty()) return false
        val raw = postPublic(ctx, "/v1/auth/refresh", JSONObject().put("refreshToken", rt)) ?: return false
        return runCatching {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return false
            prefs(ctx).edit()
                .putString("access_token", o.optString("accessToken"))
                .putString("refresh_token", o.optString("refreshToken", rt))
                .apply()
            true
        }.getOrDefault(false)
    }

    private fun postPublic(ctx: Context, path: String, body: JSONObject): String? {
        var c: HttpURLConnection? = null
        return try {
            c = conn(ctx, path, "POST", auth = false)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val (code, text) = read(c)
            if (code in 200..299) text else null
        } catch (e: Exception) {
            Log.error("network", "postPublic $path gagal: ${e.javaClass.simpleName}", e)
            null
        } finally {
            c?.disconnect()
        }
    }

    // ---------- auth ----------

    /** @return null kalau sukses, selain itu pesan error. */
    fun login(ctx: Context, email: String, password: String): String? {
        val raw = postPublic(
            ctx, "/v1/auth/login",
            JSONObject().put("email", email).put("password", password)
                .put("deviceId", android.os.Build.MODEL)
        ) ?: return "Server tidak merespons atau kredensial salah"

        return runCatching {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return o.optString("error", "Login gagal")
            val u = o.getJSONObject("user")
            prefs(ctx).edit()
                .putString("access_token", o.getString("accessToken"))
                .putString("refresh_token", o.getString("refreshToken"))
                .putString("user_name", u.optString("name"))
                .putString("clinic_name", u.optString("clinicName"))
                .apply()
            null
        }.getOrElse { "Balasan server tidak dikenali" }
    }

    // ---------- SSO Z One (device code) ----------

    /** Minta kode perangkat. @return JSONObject {code, verifyUrl, expiresIn} atau null. */
    fun ssoStart(ctx: Context): JSONObject? {
        val raw = postPublic(ctx, "/v1/auth/sso/start", JSONObject().put("deviceId", android.os.Build.MODEL))
            ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * Cek status kode.
     * @return pasangan (status, pesan); status: "pending" | "ok" | "error"
     */
    fun ssoPoll(ctx: Context, code: String): Pair<String, String?> {
        var c: HttpURLConnection? = null
        return try {
            c = conn(ctx, "/v1/auth/sso/poll?code=" + java.net.URLEncoder.encode(code, "UTF-8"), "GET", auth = false)
            val (status, text) = read(c)
            val o = runCatching { JSONObject(text) }.getOrNull()
                ?: return Pair("error", "Balasan server tidak dikenali")
            when {
                status == 202 || o.optBoolean("pending") -> Pair("pending", null)
                status in 200..299 && o.optBoolean("ok") -> {
                    val u = o.getJSONObject("user")
                    prefs(ctx).edit()
                        .putString("access_token", o.getString("accessToken"))
                        .putString("refresh_token", o.getString("refreshToken"))
                        .putString("user_name", u.optString("name"))
                        .putString("clinic_name", u.optString("clinicName"))
                        .apply()
                    Pair("ok", null)
                }
                else -> Pair("error", o.optString("error", "Gagal masuk"))
            }
        } catch (e: Exception) {
            Pair("error", "Tidak dapat menghubungi server")
        } finally {
            c?.disconnect()
        }
    }

    /**
     * Tukar JWT Z One (dari deep link `acumedis://sso?token=`) jadi sesi app.
     * @return null kalau sukses, selain itu pesan error.
     */
    fun ssoExchange(ctx: Context, zoneToken: String): String? {
        val raw = postPublic(
            ctx, "/v1/auth/sso/exchange",
            JSONObject().put("token", zoneToken).put("deviceId", android.os.Build.MODEL)
        ) ?: return "Tidak dapat menghubungi server"

        return runCatching {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok")) return o.optString("error", "Gagal masuk")
            val u = o.getJSONObject("user")
            prefs(ctx).edit()
                .putString("access_token", o.getString("accessToken"))
                .putString("refresh_token", o.getString("refreshToken"))
                .putString("user_name", u.optString("name"))
                .putString("clinic_name", u.optString("clinicName"))
                .apply()
            null
        }.getOrElse { "Balasan server tidak dikenali" }
    }

    // ---------- endpoint ----------

    fun summary(ctx: Context): JSONObject? =
        request(ctx, "/v1/summary", "GET")?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun patients(ctx: Context, query: String? = null): JSONArray {
        val path = if (query.isNullOrBlank()) "/v1/patients"
        else "/v1/patients?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val raw = request(ctx, path, "GET") ?: return JSONArray()
        return runCatching { JSONObject(raw).optJSONArray("patients") ?: JSONArray() }
            .getOrDefault(JSONArray())
    }

    fun sessions(ctx: Context, patientId: String? = null): JSONArray {
        val path = if (patientId.isNullOrBlank()) "/v1/sessions"
        else "/v1/sessions?patientId=" + java.net.URLEncoder.encode(patientId, "UTF-8")
        val raw = request(ctx, path, "GET") ?: return JSONArray()
        return runCatching { JSONObject(raw).optJSONArray("sessions") ?: JSONArray() }
            .getOrDefault(JSONArray())
    }

    fun appointments(ctx: Context, date: String? = null): JSONArray {
        val path = if (date.isNullOrBlank()) "/v1/appointments" else "/v1/appointments?date=$date"
        val raw = request(ctx, path, "GET") ?: return JSONArray()
        return runCatching { JSONObject(raw).optJSONArray("appointments") ?: JSONArray() }
            .getOrDefault(JSONArray())
    }

    fun createPatient(ctx: Context, body: JSONObject): Boolean =
        request(ctx, "/v1/patients", "POST", body) != null

    fun createSession(ctx: Context, body: JSONObject): Boolean =
        request(ctx, "/v1/sessions", "POST", body) != null

    fun setPayment(ctx: Context, sessionId: String, status: String): Boolean =
        request(ctx, "/v1/sessions/$sessionId/payment", "PATCH", JSONObject().put("payment_status", status)) != null

    fun session(ctx: Context, id: String): Visit? =
        request(ctx, "/v1/sessions/$id", "GET")?.let {
            runCatching { Visit.from(JSONObject(it).getJSONObject("session")) }.getOrNull()
        }

    fun me(ctx: Context): JSONObject? =
        request(ctx, "/v1/me", "GET")?.let { runCatching { JSONObject(it) }.getOrNull() }
}

/** Pasien dari /v1/patients. */
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

/** Sesi terapi dari /v1/sessions. */
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
            return Visit(
                id = o.optString("id"),
                patientId = o.optString("patient_id"),
                patientName = o.optString("patient_name"),
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

/** Format tanggal & uang gaya Indonesia. */
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
