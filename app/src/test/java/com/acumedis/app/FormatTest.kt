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
