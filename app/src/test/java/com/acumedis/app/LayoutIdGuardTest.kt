package com.acumedis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard: setiap findViewById<T> di Activity harus cocok dengan tag di layout-nya.
 * Mencegah ClassCastException (bug nyata yang pernah terjadi di app MemberKit).
 */
class LayoutIdGuardTest {

    private fun layout(name: String) = java.io.File("src/main/res/layout/$name").readText()
    private fun activity(name: String) = java.io.File("src/main/java/com/acumedis/app/$name").readText()

    /** castTag: tipe yang dipakai findViewById di kode (default sama dengan xmlTag). */
    private fun assertCast(
        activityFile: String, layoutFile: String, id: String,
        xmlTag: String, castTag: String = ""
    ) {
        val a = activity(activityFile)
        val l = layout(layoutFile)
        val tag = xmlTag.substringAfterLast('.')
        val declared = Regex("""<${Regex.escape(xmlTag)}\b[^>]*@\+id/${Regex.escape(id)}""").containsMatchIn(l)
        assertTrue("$layoutFile: @+id/$id bukan <$xmlTag>", declared)

        val typed = Regex("""findViewById<([A-Za-z.]+)>\(R\.id\.${Regex.escape(id)}\)""").find(a)
        if (typed != null) {
            val expected = castTag.ifBlank { tag }.substringAfterLast('.')
            assertEquals(
                "$activityFile: findViewById<${typed.groupValues[1]}>(R.id.$id) vs layout <$xmlTag>",
                expected, typed.groupValues[1].substringAfterLast('.')
            )
        }
    }

    @Test
    fun loginCastsMatch() {
        assertCast("LoginActivity.kt", "activity_login.xml", "baseUrl", "EditText")
        assertCast("LoginActivity.kt", "activity_login.xml", "email", "EditText")
        assertCast("LoginActivity.kt", "activity_login.xml", "password", "EditText")
        assertCast("LoginActivity.kt", "activity_login.xml", "error", "TextView")
        assertCast("LoginActivity.kt", "activity_login.xml", "loginBtn", "TextView")
        assertCast("LoginActivity.kt", "activity_login.xml", "version", "TextView")
    }

    @Test
    fun mainCastsMatch() {
        assertCast("MainActivity.kt", "activity_main.xml", "queueList", "LinearLayout")
        assertCast("MainActivity.kt", "activity_main.xml", "userName", "TextView")
        assertCast("MainActivity.kt", "activity_main.xml", "avatar", "TextView")
        assertCast("MainActivity.kt", "activity_main.xml", "todayTotal", "TextView")
        assertCast("MainActivity.kt", "activity_main.xml", "statDone", "TextView")
        assertCast("MainActivity.kt", "activity_main.xml", "statWaiting", "TextView")
        assertCast("MainActivity.kt", "activity_main.xml", "statUnpaid", "TextView")
    }

    @Test
    fun detailCastsMatch() {
        assertCast("PatientDetailActivity.kt", "activity_patient_detail.xml", "historyList", "LinearLayout")
        assertCast("PatientDetailActivity.kt", "activity_patient_detail.xml", "title", "TextView")
        assertCast("PatientDetailActivity.kt", "activity_patient_detail.xml", "sub", "TextView")
        assertCast("PatientDetailActivity.kt", "activity_patient_detail.xml", "statTotal", "TextView")
        assertCast("PatientDetailActivity.kt", "activity_patient_detail.xml", "statUnpaid", "TextView")
    }

    @Test
    fun sessionFormCastsMatch() {
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "complaint", "EditText")
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "duration", "EditText")
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "notes", "EditText")
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "fee", "EditText")
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "error", "TextView")
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "painLabel", "TextView")
        assertCast("SessionFormActivity.kt", "activity_session_form.xml", "pain", "SeekBar")
    }

    @Test
    fun patientsCastsMatch() {
        assertCast("PatientsActivity.kt", "activity_patients.xml", "list", "androidx.recyclerview.widget.RecyclerView")
        assertCast("PatientsActivity.kt", "activity_patients.xml", "search", "EditText")
        assertCast("PatientsActivity.kt", "activity_patients.xml", "empty", "TextView", "View")
    }

    @Test
    fun everyLayoutHasUniqueIds() {
        val dir = java.io.File("src/main/res/layout")
        dir.listFiles { f -> f.name.endsWith(".xml") }!!.forEach { f ->
            val ids = Regex("""@\+id/([A-Za-z0-9_]+)""").findAll(f.readText())
                .map { it.groupValues[1] }.toList()
            assertEquals("${f.name}: id duplikat", ids.size, ids.toSet().size)
        }
    }
}
