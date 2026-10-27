package com.acumedis.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var fBase: EditText
    private lateinit var fEmail: EditText
    private lateinit var fPass: EditText
    private lateinit var fError: TextView
    private lateinit var btn: TextView
    private lateinit var ssoBtn: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var ssoDialog: AlertDialog? = null
    private var ssoCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Log.screen("LoginActivity")

        fBase = findViewById(R.id.baseUrl)
        fEmail = findViewById(R.id.email)
        fPass = findViewById(R.id.password)
        fError = findViewById(R.id.error)
        btn = findViewById(R.id.loginBtn)
        ssoBtn = findViewById(R.id.ssoBtn)
        findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME}"

        fBase.setText(Api.baseUrl(this).ifBlank { "https://fctqy79kuiw9c7cddjfdcsgg.103.93.129.94.sslip.io" })
        btn.setOnClickListener { doLogin() }
        ssoBtn.setOnClickListener { doSso() }

        if (Api.isLoggedIn(this)) goMain()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        ssoDialog?.dismiss()
        super.onDestroy()
    }

    private fun doLogin() {
        val base = fBase.text.toString().trim().trimEnd('/')
        val email = fEmail.text.toString().trim()
        val pass = fPass.text.toString()

        when {
            !base.startsWith("http") -> return fail("Alamat server harus diawali https://")
            email.isEmpty() -> return fail("Email wajib diisi")
            pass.isEmpty() -> return fail("Password wajib diisi")
        }

        fError.visibility = View.GONE
        btn.isEnabled = false
        btn.text = "Menghubungkan…"
        Api.setBaseUrl(this, base)

        Api.io.execute {
            val err = Api.login(this, email, pass)
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Masuk"
                if (err == null) goMain() else fail(err)
            }
        }
    }

    /** Device-code: minta kode → user setujui di Z One → polling sampai token terbit. */
    private fun doSso() {
        val base = fBase.text.toString().trim().trimEnd('/')
        if (!base.startsWith("http")) return fail("Alamat server harus diawali https://")
        Api.setBaseUrl(this, base)
        fError.visibility = View.GONE
        ssoBtn.isEnabled = false
        ssoBtn.text = "Menyiapkan kode…"

        Api.io.execute {
            val o = Api.ssoStart(this)
            runOnUiThread {
                ssoBtn.isEnabled = true
                ssoBtn.text = "Masuk dengan Z One"
                if (o == null) return@runOnUiThread fail("Tidak dapat meminta kode ke server")
                showSsoDialog(o.getString("code"), o.getString("verifyUrl"))
            }
        }
    }

    private fun showSsoDialog(code: String, verifyUrl: String) {
        ssoCode = code
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        box.addLine(this, "Kode perangkat", 11f, R.color.muted, bold = true)
        box.addLine(this, code, 30f, R.color.primary_deep, bold = true).apply {
            letterSpacing = 0.18f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        box.addLine(
            this,
            "1. Tekan \"Buka Z One\", masuk dengan akunmu\n" +
                "2. Pastikan kodenya sama, lalu setujui\n" +
                "3. Aplikasi masuk otomatis",
            12.5f, R.color.fg2
        )

        val open = TextView(this).apply {
            text = "Buka Z One"
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = getDrawable(R.drawable.bg_btn)
            isClickable = true
            isFocusable = true
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * resources.displayMetrics.density).toInt()
        )
        lp.topMargin = pad
        box.addView(open, lp)
        open.setOnClickListener { openZOne(verifyUrl) }

        val status = TextView(this).apply {
            text = "Menunggu persetujuan…"
            setTextColor(getColor(R.color.muted))
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(status)

        ssoDialog = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Batal") { _, _ -> ssoCode = null }
            .setOnDismissListener { ssoCode = null }
            .show()

        pollSso(code, status, attempts = 0)
    }

    private fun pollSso(code: String, status: TextView, attempts: Int) {
        if (ssoCode != code) return
        if (attempts > 100) { // ~5 menit
            status.text = "Kode kedaluwarsa. Ulangi."
            return
        }
        ui.postDelayed({
            if (ssoCode != code) return@postDelayed
            Api.io.execute {
                val (state, msg) = Api.ssoPoll(this, code)
                runOnUiThread {
                    when (state) {
                        "ok" -> {
                            ssoCode = null
                            ssoDialog?.dismiss()
                            goMain()
                        }
                        "error" -> status.text = msg ?: "Gagal masuk"
                        else -> pollSso(code, status, attempts + 1)
                    }
                }
            }
        }, 3000)
    }

    private fun openZOne(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun fail(msg: String) {
        fError.text = msg
        fError.visibility = View.VISIBLE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

private fun LinearLayout.addLine(
    ctx: android.content.Context, text: String, size: Float, color: Int, bold: Boolean = false
): TextView {
    val tv = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(ctx.getColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    addView(tv)
    return tv
}
