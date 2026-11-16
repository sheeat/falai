package app.falai.server.android

import app.falai.protocol.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.falai.server.FalaiServer
import app.falai.server.ServerConfig
import app.falai.server.Store
import java.io.File

/**
 * Mantém o servidor do Falaí ligado em primeiro plano, com wake lock parcial
 * (a CPU não dorme; a tela pode apagar normalmente).
 */
class ServerService : Service() {

    private var server: FalaiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification("Iniciando…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "falai:server")
            .apply { setReferenceCounted(false); acquire() }

        // O servidor sobe numa thread própria para não travar a interface.
        Thread {
            try {
                val dbFile = File(filesDir, "falai.db")
                val store = Store(AndroidDb(dbFile.path))
                // Mídia no cartão microSD, se houver (o último da lista); senão, na memória interna.
                // Atenção: esta pasta é apagada se o app do servidor for desinstalado.
                val storageRoot = getExternalFilesDirs(null).filterNotNull().lastOrNull() ?: filesDir
                val mediaDir = File(storageRoot, "media")
                Log.i(TAG, "Mídia em: ${mediaDir.absolutePath}")
                val s = FalaiServer(
                    store,
                    ServerConfig(host = "127.0.0.1", port = PORT, mediaDir = mediaDir),
                    log = { Log.i(TAG, it) },
                )
                val adminCode = s.start(wait = false)
                server = s
                current = s
                currentMediaDir = mediaDir.absolutePath
                Status.update(this, running = true, adminCode = adminCode, error = null)
                notify(if (adminCode != null) "Ligado · código do admin: $adminCode" else "Ligado na porta $PORT")
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao iniciar", t)
                Status.update(this, running = false, adminCode = null, error = t.toString())
                notify("Erro: ${t.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        current = null
        wakeLock?.release()
        Status.update(this, running = false, adminCode = null, error = null)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Servidor", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Falaí Servidor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val TAG = "FalaiServer"
        const val PORT = 8443

        /** O servidor rodando agora (para a tela mostrar aparelhos conectados). */
        @Volatile var current: FalaiServer? = null
        @Volatile var currentMediaDir: String? = null
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}

/** Estado do servidor para a tela, guardado em SharedPreferences. */
object Status {
    private const val PREFS = "status"

    fun update(context: Context, running: Boolean, adminCode: String?, error: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("running", running)
            .putString("adminCode", adminCode)
            .putString("error", error)
            .apply()
    }

    fun read(context: Context): Triple<Boolean, String?, String?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(p.getBoolean("running", false), p.getString("adminCode", null), p.getString("error", null))
    }
}
