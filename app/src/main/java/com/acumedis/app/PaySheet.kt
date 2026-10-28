package com.acumedis.app

import android.app.Activity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * Catat pembayaran. Sesi bisa dibayar bertahap, jadi yang diisi adalah jumlah
 * uang yang diterima sekarang — bukan menandai "lunas" lalu berharap benar.
 */
object PaySheet {

    private val METHODS = arrayOf("cash" to "Tunai", "transfer" to "Transfer", "qris" to "QRIS")

    fun show(act: Activity, v: Visit, onPaid: () -> Unit) {
        val pad = (18 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val sisa = if (v.balance > 0) v.balance else (v.fee ?: 0.0) - v.paidAmount

        box.addView(TextView(act).apply {
            text = "Biaya " + Fmt.rupiah(v.fee) + " · sudah dibayar " + Fmt.rupiah(v.paidAmount)
            setTextColor(act.getColor(R.color.fg2))
            textSize = 12.5f
        })
        box.addView(TextView(act).apply {
            text = "Sisa tagihan " + Fmt.rupiah(sisa)
            setTextColor(act.getColor(R.color.amber))
            textSize = 13f
            setPadding(0, pad / 4, 0, pad / 2)
        })

        // jumlah: terisi sisa tagihan, tinggal ubah kalau bayar sebagian
        val amount = EditText(act).apply {
            hint = "Jumlah dibayar"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (sisa > 0) sisa.toLong().toString() else "")
            setBackgroundResource(R.drawable.bg_input_focus)
            val p = (12 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        box.addView(amount)

        val group = RadioGroup(act).apply { orientation = RadioGroup.HORIZONTAL }
        METHODS.forEachIndexed { i, (_, label) ->
            group.addView(RadioButton(act).apply {
                id = i + 1
                text = label
                textSize = 13f
                isChecked = i == 0
            })
        }
        box.addView(group)

        val note = EditText(act).apply {
            hint = "Catatan (opsional)"
            setBackgroundResource(R.drawable.bg_input_focus)
            val p = (12 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        box.addView(note)

        val err = TextView(act).apply {
            setTextColor(act.getColor(R.color.red))
            textSize = 12f
            visibility = android.view.View.GONE
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(err)

        val dlg = AlertDialog.Builder(act)
            .setTitle("Terima pembayaran")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                err.visibility = android.view.View.GONE
                val nominal = amount.text.toString().trim().toDoubleOrNull()
                if (nominal == null || nominal <= 0) {
                    err.text = "Jumlah harus lebih dari 0"
                    err.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                val method = METHODS[(group.checkedRadioButtonId - 1).coerceIn(0, METHODS.size - 1)].first

                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Api.io.execute {
                    val (ok, msg) = Api.pay(act, v.id, nominal, method, note.text.toString().trim())
                    act.runOnUiThread {
                        if (act.isFinishing) return@runOnUiThread
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (ok) {
                            dlg.dismiss()
                            Toast.makeText(act, "Pembayaran tercatat", Toast.LENGTH_SHORT).show()
                            onPaid()
                        } else {
                            // pesan server ditampilkan apa adanya: "melebihi sisa 50.000"
                            err.text = msg
                            err.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            }
        }
        dlg.show()
    }
}
