package app.falai.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * O que o editor de fotos faz na imagem de verdade (girar, recortar, riscar, escrever).
 * Tudo trabalha num Bitmap que pode ser alterado.
 */
object ImageEditOps {
    /** Lado maior da foto enquanto edita (fotos maiores são reduzidas, como no WhatsApp). */
    const val MAX_SIDE = 2048

    /** Uma cópia que pode ser alterada. */
    fun editable(b: Bitmap): Bitmap =
        if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, true)

    fun copy(b: Bitmap): Bitmap = b.copy(Bitmap.Config.ARGB_8888, true)

    /** Gira 90° para a direita. */
    fun rotate90(b: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return editable(Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true))
    }

    fun crop(b: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val cx = x.coerceIn(0, b.width - 1)
        val cy = y.coerceIn(0, b.height - 1)
        val cw = w.coerceIn(1, b.width - cx)
        val ch = h.coerceIn(1, b.height - cy)
        return copy(Bitmap.createBitmap(b, cx, cy, cw, ch))
    }

    /** Grossura do risco, proporcional ao tamanho da foto. */
    fun strokeWidth(b: Bitmap): Float = maxOf(4f, maxOf(b.width, b.height) / 150f)

    private fun pen(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    fun drawLine(b: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        Canvas(b).drawLine(x0, y0, x1, y1, pen(color, width))
    }

    fun drawDot(b: Bitmap, x: Float, y: Float, color: Int, width: Float) {
        val p = pen(color, width).apply { style = Paint.Style.FILL }
        Canvas(b).drawCircle(x, y, width / 2f, p)
    }

    /** Escreve um texto (ou emoji) centralizado em (cx, cy). Texto ganha contorno para ler em qualquer fundo. */
    fun drawText(b: Bitmap, text: String, cx: Float, cy: Float, size: Float, color: Int, emoji: Boolean) {
        val canvas = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            this.color = if (emoji) 0xFF000000.toInt() else color
        }
        val outline = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, size / 8f)
            strokeJoin = Paint.Join.ROUND
            this.color = if (color == 0xFF000000.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val lines = text.trimEnd('\n').split("\n")
        val lh = size * 1.15f
        val fm = fill.fontMetrics
        val centerShift = -(fm.ascent + fm.descent) / 2f
        val first = cy - (lines.size - 1) * lh / 2f + centerShift
        lines.forEachIndexed { i, line ->
            val y = first + i * lh
            if (!emoji) canvas.drawText(line, cx, y, outline)
            canvas.drawText(line, cx, y, fill)
        }
    }

    /** Grava a foto pronta: PNG se a original era PNG, senão JPEG. */
    fun save(b: Bitmap, out: File, png: Boolean) {
        out.outputStream().use {
            if (png) b.compress(Bitmap.CompressFormat.PNG, 100, it) else b.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
    }
}
