package com.acumedis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perbandingan versi: menentukan kapan tombol update menawarkan unduhan. */
class UpdaterTest {

    @Test
    fun `versi lebih tinggi terdeteksi`() {
        assertTrue(Updater.isNewer("1.7.0", "1.6.0"))
        assertTrue(Updater.isNewer("2.0.0", "1.99.99"))
        assertTrue(Updater.isNewer("1.6.1", "1.6.0"))
    }

    @Test
    fun `versi sama bukan update`() {
        assertFalse(Updater.isNewer("1.6.0", "1.6.0"))
        assertFalse(Updater.isNewer("v1.6.0", "1.6.0"))
    }

    @Test
    fun `versi lebih rendah tidak menawarkan unduhan`() {
        assertFalse(Updater.isNewer("1.5.9", "1.6.0"))
        assertFalse(Updater.isNewer("0.9.0", "1.0.0"))
    }

    @Test
    fun `awalan v dan jumlah segmen berbeda tetap benar`() {
        assertTrue(Updater.isNewer("v1.7", "1.6.0"))
        assertFalse(Updater.isNewer("v1.6", "1.6.0"))
        assertTrue(Updater.isNewer("1.6.0.1", "1.6.0"))
    }
}
