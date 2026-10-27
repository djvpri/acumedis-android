package com.acumedis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bentuk JSON diambil dari respons nyata acumedis-api
 * (/v1/patients, /v1/sessions, /v1/summary, /v1/appointments, /v1/auth/login).
 */
class ApiParsingTest {

    private val patientJson = """
    {"id":"cmvq3o5hf000610u21rfrsz0n","name":"Budi Santoso","gender":"male",
     "birth_date":"1985-11-02","phone":"081298765432","email":null,"address":"Badau",
     "note":null,"total_sessions":1,"last_session_date":"2026-10-27T03:02:28.823Z",
     "updated_at":"2026-10-27T03:02:28.804Z"}
    """.trimIndent()

    private val sessionJson = """
    {"id":"cmvq3pggc0005b9qvqc4rduq8","patient_id":"cmvq3pg3r0003b9qvrk4jvze7",
     "patient_name":"Rina Putri","practitioner_id":"cmvq3o5gj000210u2ach9cx2r",
     "session_date":"2026-10-27T03:03:29.674Z","chief_complaint":"Migrain berulang",
     "tongue_color":"Merah","tongue_coating":null,"pulse_quality":null,"pain_scale":7,
     "tcm_diagnosis":null,"points_used":["GB20","LI4"],"duration_minutes":30,
     "notes":null,"ai_recommendation":null,"fee":150000,"payment_status":"unpaid",
     "photos":[],"created_at":"2026-10-27T03:03:29.674Z","updated_at":"2026-10-27T03:03:29.674Z"}
    """.trimIndent()

    private val appointmentJson = """
    {"id":"cmvq3o5i7000b10u2uhs7pv24","scheduled_at":"2026-10-27T01:30:00.000Z",
     "duration_minutes":60,"reason":"Kontrol nyeri punggung","status":"confirmed",
     "queue_number":1,"notes":null,"patient_id":"cmvq3o5h8000410u26hfefm5o",
     "patient_name":"Siti Rahma","external_name":null,"external_phone":null,
     "updated_at":"2026-10-27T02:58:00.000Z"}
    """.trimIndent()

    @Test
    fun patientParsesFromApiShape() {
        val p = Patient.from(JSONObject(patientJson))
        assertEquals("cmvq3o5hf000610u21rfrsz0n", p.id)
        assertEquals("Budi Santoso", p.name)
        assertEquals("male", p.gender)
        assertEquals("081298765432", p.phone)
        assertEquals("1985-11-02", p.birthDate)
        assertEquals(1, p.totalSessions)
        assertEquals("BS", p.initials())
        assertNotNull(p.lastSession)
    }

    @Test
    fun sessionParsesFromApiShape() {
        val v = Visit.from(JSONObject(sessionJson))
        assertEquals("cmvq3pg3r0003b9qvrk4jvze7", v.patientId)
        assertEquals("Rina Putri", v.patientName)
        assertEquals("2026-10-27", v.date)
        assertEquals("Migrain berulang", v.complaint)
        assertEquals(listOf("GB20", "LI4"), v.points)
        assertEquals(30, v.duration)
        assertEquals(150000.0, v.fee!!, 0.001)
        assertEquals("unpaid", v.payment)
        assertEquals(null, v.diagnosis)
    }

    @Test
    fun appointmentFieldsForHomeScreen() {
        val o = JSONObject(appointmentJson)
        val at = o.optString("scheduled_at")
        assertEquals("01:30", at.substring(11, 16))
        assertEquals(1, o.optInt("queue_number"))
        assertEquals("Siti Rahma", o.optString("patient_name"))
        assertEquals("confirmed", o.optString("status"))
        assertEquals("2026-10-27", at.take(10))
    }

    @Test
    fun summaryFieldsForHeader() {
        val sum = JSONObject(
            """{"ok":true,"date":"2026-10-27","appointments_total":2,"appointments_done":0,
               "appointments_waiting":2,"sessions_today":2,"income_today":150000,
               "expense_today":85000,"unpaid_count":1,"unpaid_amount":150000}""".trimIndent()
        )
        assertEquals(0, sum.optInt("appointments_done"))
        assertEquals(2, sum.optInt("appointments_waiting"))
        assertEquals(1, sum.optInt("unpaid_count"))
        assertTrue(sum.optInt("income_today") > 0)
    }

    @Test
    fun loginResponseShape() {
        val o = JSONObject(
            """{"ok":true,"accessToken":"eyJhbG...","refreshToken":"a825a15b",
               "user":{"id":"u1","name":"dr. Ayu Lestari","email":"admin@acumedis.app",
               "role":"owner","tenantId":"t1","clinicName":"Klinik AcuMedis",
               "currency":"IDR","defaultFee":150000}}""".trimIndent()
        )
        assertTrue(o.optBoolean("ok"))
        assertEquals("dr. Ayu Lestari", o.getJSONObject("user").optString("name"))
        assertEquals("Klinik AcuMedis", o.getJSONObject("user").optString("clinicName"))
        assertTrue(o.optString("accessToken").isNotEmpty())
        assertTrue(o.optString("refreshToken").isNotEmpty())
    }

    @Test
    fun formatUntukTampilan() {
        assertEquals("Rp 150.000", Fmt.rupiah(150000.0))
        assertEquals("27 Okt 2026", Fmt.tanggal("2026-10-27T03:02:28.823Z"))
        assertEquals("27 Okt", Fmt.pendek("2026-10-27T03:02:28.823Z"))
    }
}
