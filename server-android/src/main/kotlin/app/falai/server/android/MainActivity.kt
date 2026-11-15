package app.falai.server.android

import app.falai.protocol.*

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela simples do servidor: status, código do administrador e atalhos.
 * Sem Compose de propósito: é um app de bastidor, quanto menos peças, melhor.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.START }
        root.addView(status)
        root.addView(button("Ligar servidor") { ServerService.start(this) })
        root.addView(button("Desligar servidor") { ServerService.stop(this) })
        root.addView(button("Não otimizar bateria (recomendado)") { askIgnoreBatteryOptimizations() })
        setContentView(root)

        ServerService.start(this)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun render() {
        val (running, adminCode, error) = Status.read(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "liberada" else "OTIMIZADA (pode desligar o servidor)"
        status.text = buildString {
            appendLine(if (running) "✅ Servidor ligado em 127.0.0.1:${ServerService.PORT}" else "⛔ Servidor desligado")
            if (adminCode != null) {
                appendLine()
                appendLine("Código do administrador (vale 24h):")
                appendLine(adminCode)
            }
            if (error != null) { appendLine(); appendLine("Erro: $error") }
            appendLine()
            appendLine("Bateria: $battery")
        }
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimizations() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }
}
