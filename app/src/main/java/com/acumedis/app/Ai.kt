package com.acumedis.app

import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * Analisis foto lidah lewat server (Gemini). Kunci API tinggal di server —
 * APK bisa dibongkar, jadi kunci tidak boleh ikut dikirim ke aplikasi.
 */
object Ai {

    data class Tongue(
        val tongue: String,
        val diagnosis: String,
        val treatment: String,
        val points: List<String>,
        val notes: String
    )

    /** Kompres + perkecil sebelum kirim: foto kamera 4-6 MB terlalu berat. */
    fun toJpegBase64(bytes: ByteArray, maxSide: Int = 1024, quality: Int = 80): Pair<String, String>? {
        val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
    }

    fun analyzeTongue(
        ctx: android.content.Context,
        jpegBase64: String,
        mime: String,
        complaint: String
    ): Tongue? {
        val body = JSONObject().put("image", jpegBase64).put("mime", mime)
        if (complaint.isNotBlank()) body.put("complaint", complaint)

        val raw = Api.request(ctx, "/v1/ai/tongue", "POST", body) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!o.optBoolean("ok")) return null

        val arr = o.optJSONArray("points")
        val points = mutableListOf<String>()
        if (arr != null) for (i in 0 until arr.length()) points.add(arr.optString(i))

        return Tongue(
            tongue = o.optString("tongue"),
            diagnosis = o.optString("diagnosis"),
            treatment = o.optString("treatment"),
            points = points,
            notes = o.optString("notes")
        )
    }
}
