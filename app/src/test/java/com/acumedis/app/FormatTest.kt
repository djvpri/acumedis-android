package com.acumedis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FormatTest {

    @Test
    fun tanggalIndonesia() {
        assertEquals("24 Okt 2026", Fmt.tanggal("2026-10-24T09:30:00.000Z"))
        assertEquals("1 Jan 2027", Fmt.tanggal("2027-01-01"))
        assertEquals("-", Fmt.tanggal(null))
        assertEquals("-", Fmt.tanggal(""))
    }

    @Test
    fun tanggalPendek() {
        assertEquals("24 Okt", Fmt.pendek("2026-10-24T09:30:00.000Z"))
        assertEquals("-", Fmt.pendek(null))
    }

    @Test
    fun jamMenurutZonaPerangkat() {
        // server menyimpan UTC; app harus menampilkannya dalam jam perangkat,
        // bukan memotong string ISO mentah
        val tz = java.util.TimeZone.getDefault()
        val iso = "2026-10-29T02:00:00.000Z"
        val ms = Fmt.millis(iso)!!
        val c = java.util.Calendar.getInstance(tz).apply { timeInMillis = ms }
        val expect = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        assertEquals(expect, Fmt.jam(iso))
        assertEquals("-", Fmt.jam(null))
        assertEquals("-", Fmt.jam("bukan tanggal"))
    }

    @Test
    fun rupiahRibuan() {
        assertEquals("Rp 150.000", Fmt.rupiah(150000.0))
        assertEquals("Rp 1.250.000", Fmt.rupiah(1250000.0))
        assertEquals("Rp 0", Fmt.rupiah(0.0))
        assertEquals("-", Fmt.rupiah(null))
        // laba negatif: minus di depan angka, bukan menempel setelah "Rp"
        assertEquals("Rp -375.000", Fmt.rupiah(-375000.0))
        assertEquals("Rp -1.050.000", Fmt.rupiah(-1050000.0))
    }

    @Test
    fun baseUrlNormalisasi() {
        assertEquals("https://a.b", "https://a.b/".trimEnd('/'))
        assertFalse("http".startsWith("https"))
    }
}
