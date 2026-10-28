package com.acumedis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.json.JSONArray

/**
 * Grafik batang pendapatan vs pengeluaran per hari, digambar sendiri.
 * Tidak memakai library chart supaya APK tetap kecil dan tidak ada
 * ketergantungan yang harus ikut di-update.
 */
class DayChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val income = mutableListOf<Float>()
    private val expense = mutableListOf<Float>()
    private val labels = mutableListOf<String>()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.muted)
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.border)
        strokeWidth = 2f
    }

    /** Isi dari `days` pada /v1/finance. */
    fun setDays(days: JSONArray?) {
        income.clear(); expense.clear(); labels.clear()
        if (days != null) {
            for (i in 0 until days.length()) {
                val o = days.optJSONObject(i) ?: continue
                income.add(o.optDouble("income", 0.0).toFloat())
                expense.add(o.optDouble("expense", 0.0).toFloat())
                // "2026-10-28" → "28/10"
                labels.add(o.optString("date").takeLast(5).replace("-", "/"))
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (income.isEmpty()) return

        val labelH = 34f
        val chartBottom = height - labelH
        val chartH = chartBottom - 8f
        val maxV = maxOf(income.maxOrNull() ?: 0f, expense.maxOrNull() ?: 0f, 1f)

        // garis dasar
        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, gridPaint)

        val n = income.size
        val slot = width.toFloat() / n
        // batang renggang kalau harinya banyak, supaya tetap terbaca
        val barW = (slot * 0.34f).coerceAtMost(26f).coerceAtLeast(3f)

        for (i in 0 until n) {
            val cx = slot * i + slot / 2f

            val hIn = (income[i] / maxV) * chartH
            if (hIn > 0.5f) {
                barPaint.color = context.getColor(R.color.green)
                canvas.drawRoundRect(
                    RectF(cx - barW - 1f, chartBottom - hIn, cx - 1f, chartBottom),
                    barW / 2, barW / 2, barPaint
                )
            }
            val hOut = (expense[i] / maxV) * chartH
            if (hOut > 0.5f) {
                barPaint.color = context.getColor(R.color.red)
                canvas.drawRoundRect(
                    RectF(cx + 1f, chartBottom - hOut, cx + barW + 1f, chartBottom),
                    barW / 2, barW / 2, barPaint
                )
            }

            // label tanggal: hanya sebagian supaya tidak menumpuk
            val every = if (n > 10) n / 6 else 1
            if (i % every == 0) {
                canvas.drawText(labels[i], cx, height - 8f, labelPaint)
            }
        }
    }
}
