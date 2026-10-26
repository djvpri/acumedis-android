package com.acumedis.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var fBase: EditText
    private lateinit var fEmail: EditText
    private lateinit var fPass: EditText
    private lateinit var fError: TextView
    private lateinit var btn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        fBase = findViewById(R.id.baseUrl)
        fEmail = findViewById(R.id.email)
        fPass = findViewById(R.id.password)
        fError = findViewById(R.id.error)
        btn = findViewById(R.id.loginBtn)
        findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME}"

        fBase.setText(Api.baseUrl(this).ifBlank { BuildConfig.BASE_URL })
        btn.setOnClickListener { doLogin() }

        if (Api.isLoggedIn(this)) goMain()
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

    private fun fail(msg: String) {
        fError.text = msg
        fError.visibility = View.VISIBLE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
