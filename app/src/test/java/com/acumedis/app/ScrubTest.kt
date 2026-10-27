package com.acumedis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scrub PII sebelum log keluar dari HP. */
class ScrubTest {

    @Test
    fun `nomor HP Indonesia disamarkan`() {
        val raw = "pasien 081234567890 tidak ditemukan / +6281234567890"
        val out = Scrub.clean(raw)
        assertFalse(out.contains("081234567890"))
        assertFalse(out.contains("6281234567890"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `email disamarkan`() {
        val out = Scrub.clean("login gagal untuk ayu.lestari@klinik.co.id")
        assertFalse(out.contains("ayu.lestari@klinik.co.id"))
        assertFalse(out.contains("@klinik"))
    }

    @Test
    fun `field nama pada JSON disamarkan`() {
        val raw = """{"nama_pasien":"Siti Rahma","usia":31}"""
        val out = Scrub.clean(raw)
        assertFalse(out.contains("Siti Rahma"))
        assertTrue(out.contains("***"))
        assertTrue(out.contains("usia"))
    }

    @Test
    fun `teks teknis tidak dirusak`() {
        val raw = "java.net.SocketTimeoutException: timeout after 20000ms at com.acumedis.app.Api.read(Api.kt:73)"
        assertEquals(raw, Scrub.clean(raw))
    }

    @Test
    fun `angka biasa tidak dianggap nomor HP`() {
        val raw = "HTTP 500 fee=150000 petugas 12 orang"
        assertEquals(raw, Scrub.clean(raw))
    }
}
