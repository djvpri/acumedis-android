package com.acumedis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Uji jalur parsing terhadap bentuk JSON asli server AcuMedis
 * (bentuk diambil dari respons nyata /api/patients, /api/sessions, /api/appointments).
 */
class ApiParsingTest {

    private val patientJson = """
    {"id":"cmvq1fxlh0001p7bu6llp0pxs","tenantId":"t-acumedis","name":"Siti Rahma",
     "gender":"female","birthDate":"1991-04-12T00:00:00.000Z","phone":"081234567890",
     "email":null,"address":"Badau","avatarBase64":null,
     "avatar_url":null,"birth_date":"1991-04-12","total_sessions":1,
     "last_session_date":"2026-10-27T02:00:06.946Z","createdAt":"2026-10-27T02:00:06.058Z"}
    """.trimIndent()

    private val sessionJson = """
    {"id":"cmvq1fy910003p7builf9b2s5","patient_id":"cmvq1fxlh0001p7bu6llp0pxs",
     "session_date":"2026-10-27T02:00:06.946Z","chief_complaint":"Nyeri punggung bawah",
     "tongue_color":"Pucat","pain_scale":6,"tcm_diagnosis":null,
     "points_used":["LI4","GB34","BL40"],"duration_minutes":30,"notes":"Tes",
     "fee":150000,"payment_status":"paid",
     "patient":{"id":"cmvq1fxlh0001p7bu6llp0pxs","name":"Siti Rahma","gender":"female"}}
    """.trimIndent()

    private val appointmentJson = """
    {"id":"ap1","scheduledAt":"2026-10-27T02:30:00.000Z","durationMinutes":60,
     "reason":"Kontrol","status":"confirmed","queueNumber":3,
     "patient":{"id":"cmvq1fxlh0001p7bu6llp0pxs","name":"Siti Rahma"}}
    """.trimIndent()

    @Test
    fun patientParsesFromRealShape() {
        val p = Patient.from(org.json.JSONObject(patientJson))
        assertEquals("cmvq1fxlh0001p7bu6llp0pxs", p.id)
        assertEquals("Siti Rahma", p.name)
        assertEquals("female", p.gender)
        assertEquals("081234567890", p.phone)
        assertEquals("1991-04-12", p.birthDate)
        assertEquals(1, p.totalSessions)
        assertEquals("SR", p.initials())
        assertNotNull(p.lastSession)
    }

    @Test
    fun sessionParsesFromRealShape() {
        val v = Visit.from(org.json.JSONObject(sessionJson))
        assertEquals("cmvq1fxlh0001p7bu6llp0pxs", v.patientId)
        assertEquals("Siti Rahma", v.patientName)
        assertEquals("2026-10-27", v.date)
        assertEquals("Nyeri punggung bawah", v.complaint)
        assertEquals(listOf("LI4", "GB34", "BL40"), v.points)
        assertEquals(30, v.duration)
        assertEquals(150000.0, v.fee!!, 0.001)
        assertEquals("paid", v.payment)
        // tcm_diagnosis null → harus null, bukan string "null"
        assertEquals(null, v.diagnosis)
    }

    @Test
    fun appointmentFieldsAccessible() {
        val o = org.json.JSONObject(appointmentJson)
        assertEquals("2026-10-27T02:30:00.000Z", o.optString("scheduledAt"))
        assertEquals(3, o.optInt("queueNumber"))
        assertEquals("Siti Rahma", o.optJSONObject("patient")?.optString("name"))
        // tanggal dipakai untuk filter "hari ini"
        assertEquals("2026-10-27", o.optString("scheduledAt").take(10))
    }

    @Test
    fun jamDariIso() {
        val at = "2026-10-27T02:30:00.000Z"
        assertEquals("02:30", at.substring(11, 16))
    }

    @Test
    fun formatRupiahDanTanggalUntukNota() {
        assertEquals("Rp 150.000", Fmt.rupiah(150000.0))
        assertEquals("27 Okt 2026", Fmt.tanggal("2026-10-27T02:00:06.946Z"))
    }
}
