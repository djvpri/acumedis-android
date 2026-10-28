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

    private fun conn(ctx: Context, path: String, method: String, auth: Boolean, timeoutMs: Int = TIMEOUT): HttpURLConnection {
        val base = baseUrl(ctx)
        require(base.isNotEmpty()) { "Alamat server belum diatur" }
        return (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = timeoutMs
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

    fun request(
        ctx: Context, path: String, method: String,
        body: JSONObject? = null, auth: Boolean = true, retried: Boolean = false
    ): String? {
        var c: HttpURLConnection? = null
        return try {
            val usedToken = if (auth) accessToken(ctx) else ""
            c = conn(ctx, path, method, auth)
            if (body != null && method != "GET") c.outputStream.use { it.write(body.toString().toByteArray()) }
            val (code, text) = read(c)

            // access token kedaluwarsa → coba refresh sekali
            if (code == 401 && auth && !retried && refresh(ctx, usedToken)) {
                return request(ctx, path, method, body, auth, retried = true)
            }
            if (code in 200..299) return text

            // kegagalan HTTP dicatat: tanpa ini toast "Gagal" tidak bisa ditelusuri
            Log.error("http", "$method $path → HTTP $code: " + text.take(300))
            null
        } catch (e: Exception) {
            Log.error("network", "request $method $path gagal: ${e.javaClass.simpleName}", e)
            null
        } finally {
            c?.disconnect()
        }
    }

    /**
     * Sama seperti [request] tapi mengembalikan kode HTTP + isi balasan apa adanya,
     * dipakai saat pemanggil perlu menampilkan pesan galat dari server (mis. analisis AI).
     */
    fun requestEx(
        ctx: Context, path: String, method: String, body: JSONObject?,
        timeoutMs: Int = TIMEOUT, retried: Boolean = false
    ): Pair<Int, String>? {
        var c: HttpURLConnection? = null
        return try {
            c = conn(ctx, path, method, true, timeoutMs)
            if (body != null && method != "GET") c.outputStream.use { it.write(body.toString().toByteArray()) }
            val r = read(c)
            if (r.first == 401 && !retried && refresh(ctx)) {
                return requestEx(ctx, path, method, body, timeoutMs, retried = true)
            }
            r
        } catch (e: Exception) {
            Log.error("network", "request $method $path gagal: ${e.javaClass.simpleName}", e)
            null
        } finally {
            c?.disconnect()
        }
    }

    /**
     * Rotasi token. True kalau dapat access token baru.
     *
     * Diserialkan: beberapa request paralel bisa kena 401 bersamaan. Tanpa kunci,
     * semuanya memakai refresh token yang sama — server merotasi (token lama
     * dicabut) dan yang kalah langsung 401. Kalau thread lain sudah memperbarui
     * token, cukup pakai hasilnya.
     */
    private val refreshLock = Any()

    fun refresh(ctx: Context, staleToken: String? = null): Boolean = synchronized(refreshLock) {
        if (staleToken != null && accessToken(ctx) != staleToken) return true

        val rt = refreshToken(ctx)
        if (rt.isEmpty()) return false
        val raw = postPublic(ctx, "/v1/auth/refresh", JSONObject().put("refreshToken", rt)) ?: return false
        runCatching {
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
        // server memakai batas hari UTC; tanpa offset, jadwal pagi masuk tanggal sebelumnya
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val path = if (date.isNullOrBlank()) "/v1/appointments"
        else "/v1/appointments?date=$date&tz=$tz"
        val raw = request(ctx, path, "GET") ?: return JSONArray()
        return runCatching { JSONObject(raw).optJSONArray("appointments") ?: JSONArray() }
            .getOrDefault(JSONArray())
    }

    /** Zona waktu perangkat dalam menit di timur UTC (WIB = 420). */
    fun tzMinutes(): Int =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    fun createAppointment(ctx: Context, body: JSONObject): Pair<Boolean, String?> {
        val tz = JSONObject().put("tz", tzMinutes())
        body.keys().forEach { k -> if (!body.has("tz")) tz.put(k, body.get(k)) }
        val r = requestEx(ctx, "/v1/appointments", "POST", tz) ?: return Pair(false, "Tidak bisa menghubungi server")
        if (r.first in 200..299) return Pair(true, null)
        val msg = runCatching { JSONObject(r.second).optString("error") }.getOrNull()
        return Pair(false, msg?.ifBlank { null } ?: "Gagal menyimpan jadwal (HTTP ${r.first})")
    }

    fun updateAppointment(ctx: Context, id: String, body: JSONObject): Pair<Boolean, String?> {
        body.put("tz", tzMinutes())
        val r = requestEx(ctx, "/v1/appointments/$id", "PATCH", body) ?: return Pair(false, "Tidak bisa menghubungi server")
        if (r.first in 200..299) return Pair(true, null)
        val msg = runCatching { JSONObject(r.second).optString("error") }.getOrNull()
        return Pair(false, msg?.ifBlank { null } ?: "Gagal memperbarui jadwal (HTTP ${r.first})")
    }

    fun deleteAppointment(ctx: Context, id: String): Boolean =
        request(ctx, "/v1/appointments/$id", "DELETE") != null

    fun createPatient(ctx: Context, body: JSONObject): Boolean =
        request(ctx, "/v1/patients", "POST", body) != null

    fun patient(ctx: Context, id: String): Patient? =
        request(ctx, "/v1/patients/$id", "GET")?.let {
            runCatching { Patient.from(JSONObject(it).getJSONObject("patient")) }.getOrNull()
        }

    fun updatePatient(ctx: Context, id: String, body: JSONObject): Boolean =
        request(ctx, "/v1/patients/$id", "PATCH", body) != null

    fun createSession(ctx: Context, body: JSONObject): Boolean =
        request(ctx, "/v1/sessions", "POST", body) != null

    fun setPayment(ctx: Context, sessionId: String, status: String): Boolean =
        request(ctx, "/v1/sessions/$sessionId/payment", "PATCH", JSONObject().put("payment_status", status)) != null

    fun updateSession(ctx: Context, id: String, body: JSONObject): Boolean =
        request(ctx, "/v1/sessions/$id", "PATCH", body) != null

    fun deleteSession(ctx: Context, id: String): Boolean =
        request(ctx, "/v1/sessions/$id", "DELETE") != null

    fun session(ctx: Context, id: String): Visit? =
        request(ctx, "/v1/sessions/$id", "GET")?.let {
            runCatching { Visit.from(JSONObject(it).getJSONObject("session")) }.getOrNull()
        }

    fun me(ctx: Context): JSONObject? =
        request(ctx, "/v1/me", "GET")?.let { runCatching { JSONObject(it) }.getOrNull() }
}

/**
 * `optString` mengembalikan string "null" kalau nilainya JSON null — itu
 * penyebab tulisan "null" muncul di layar. Helper ini menganggap null/kosong
 * sebagai tidak ada.
 */
internal fun JSONObject.str(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key)
    return if (v.isBlank() || v == "null") null else v
}

/** Pasien dari /v1/patients. */
data class Patient(
    val id: String,
    val name: String,
    val gender: String?,
    val phone: String?,
    val birthDate: String?,
    val totalSessions: Int,
    val lastSession: String?,
    val email: String? = null,
    val address: String? = null,
    val note: String? = null
) {
    fun initials(): String = name.trim().split(" ").filter { it.isNotBlank() }
        .take(2).joinToString("") { it.first().uppercase() }

    companion object {
        fun from(o: JSONObject) = Patient(
            id = o.optString("id"),
            name = o.optString("name"),
            gender = o.str("gender"),
            phone = o.str("phone"),
            birthDate = o.str("birth_date"),
            totalSessions = o.optInt("total_sessions", 0),
            lastSession = o.str("last_session_date"),
            email = o.str("email"),
            address = o.str("address"),
            note = o.str("note")
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
    val payment: String,
    val tongueColor: String? = null,
    val notes: String? = null,
    val painScale: Int? = null,
    val ai: AI? = null,
    val photos: List<Photo> = emptyList()
) {
    /** Hasil analisis lidah yang tersimpan bersama sesi. */
    data class AI(
        val tongue: String,
        val diagnosis: String,
        val treatment: String,
        val points: List<String>
    )

    data class Photo(val url: String, val analysis: String?)

    companion object {
        fun from(o: JSONObject): Visit {
            val arr = o.optJSONArray("points_used")
            val points = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) points.add(arr.optString(i))

            val photos = mutableListOf<Photo>()
            o.optJSONArray("photos")?.let { pa ->
                for (i in 0 until pa.length()) {
                    val p = pa.optJSONObject(i) ?: continue
                    p.str("url")?.let { photos.add(Photo(it, p.str("analysis"))) }
                }
            }

            val aiObj = o.optJSONObject("ai_recommendation")
            val ai = aiObj?.let {
                val pa = it.optJSONArray("points")
                val pts = mutableListOf<String>()
                if (pa != null) for (i in 0 until pa.length()) pts.add(pa.optString(i))
                AI(
                    tongue = it.optString("tongue"),
                    diagnosis = it.optString("diagnosis"),
                    treatment = it.optString("treatment"),
                    points = pts
                )
            }

            return Visit(
                id = o.optString("id"),
                patientId = o.optString("patient_id"),
                patientName = o.optString("patient_name"),
                date = o.optString("session_date").take(10),
                complaint = o.optString("chief_complaint"),
                diagnosis = o.str("tcm_diagnosis"),
                points = points,
                duration = if (o.isNull("duration_minutes")) null else o.optInt("duration_minutes"),
                fee = if (o.isNull("fee")) null else o.optDouble("fee"),
                payment = o.str("payment_status") ?: "unpaid",
                tongueColor = o.str("tongue_color"),
                notes = o.str("notes"),
                painScale = if (o.isNull("pain_scale")) null else o.optInt("pain_scale"),
                ai = ai,
                photos = photos
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

    /** "1990-05-17" → "34 th" (perkiraan; cukup untuk kartu pasien). */
    fun umur(iso: String?): String {
        if (iso.isNullOrBlank() || iso.length < 10) return "-"
        val y = iso.substring(0, 4).toIntOrNull() ?: return "-"
        val m = iso.substring(5, 7).toIntOrNull() ?: return "-"
        val d = iso.substring(8, 10).toIntOrNull() ?: return "-"
        val now = java.util.Calendar.getInstance()
        var age = now.get(java.util.Calendar.YEAR) - y
        val before = now.get(java.util.Calendar.MONTH) + 1 < m ||
            (now.get(java.util.Calendar.MONTH) + 1 == m && now.get(java.util.Calendar.DAY_OF_MONTH) < d)
        if (before) age--
        return if (age in 0..130) "$age th" else "-"
    }

    /** "2026-10-28T09:30:00.000Z" → "09:30" menurut jam perangkat. */
    fun jam(iso: String?): String {
        val ms = millis(iso) ?: return "-"
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /** ISO 8601 UTC → milidetik; null kalau tidak terbaca. */
    fun millis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    /** "Rab, 28 Okt 2026 · 09:30" menurut jam perangkat. */
    fun waktuLengkap(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val hari = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        return "$hari, ${c.get(java.util.Calendar.DAY_OF_MONTH)} ${bulan[c.get(java.util.Calendar.MONTH)]} " +
            "${c.get(java.util.Calendar.YEAR)} · " +
            String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /** Pilihan tanggal/jam lokal → ISO UTC, supaya server tidak menebak zona. */
    fun isoUtc(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
}
