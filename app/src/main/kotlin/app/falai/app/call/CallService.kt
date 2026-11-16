package app.falai.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.falai.app.FalaiApp
import app.falai.app.MainActivity
import app.falai.protocol.CallDto

/**
 * Mantém o Falaí vivo com o app fechado:
 * - **Em chamada**: o Android só deixa usar o microfone, a câmera e a tela com o app fechado
 *   se houver um aviso fixo ("Em chamada com…").
 * - **Sempre conectado** (opcional, ligado de fábrica): um aviso discreto "Falaí conectado", para
 *   a conexão com o servidor não cair e as chamadas tocarem mesmo com o app fechado.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!refresh()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Põe o aviso certo (e os "tipos" certos). Devolve false se não há mais motivo para ficar ligado. */
    fun refresh(): Boolean {
        val inCall = Companion.inCall
        if (!inCall && !standby) return false
        createChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, if (inCall) CHANNEL_CALL else CHANNEL_STANDBY)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
        if (inCall) {
            b.setContentTitle(if (screen) "Mostrando a tela · $title" else "Em chamada · $title")
                .setContentText("Toque para voltar à chamada")
                .setCategory(Notification.CATEGORY_CALL)
                .setUsesChronometer(true)
                .addAction(
                    Notification.Action.Builder(null, "Desligar", actionIntent(this, CallActionReceiver.HANGUP)).build()
                )
        } else {
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Falaí conectado")
                .setContentText("Pronto para receber chamadas. Dá para esconder este aviso nas configurações do Android.")
                .setCategory(Notification.CATEGORY_SERVICE)
        }
        val n = b.build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, types()) else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // Android não deixou (ex.: app em segundo plano no Android 14). A chamada segue enquanto o app estiver aberto.
            android.util.Log.w("Falai", "Serviço da chamada: ${e.message}")
            return inCall
        }
        return true
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun types(): Int {
        var t = 0
        if (Build.VERSION.SDK_INT >= 34 && standby) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        if (Companion.inCall && Build.VERSION.SDK_INT >= 30) {
            if (granted(android.Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (camera && granted(android.Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Companion.inCall && screen) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (t == 0 && Build.VERSION.SDK_INT >= 34) t = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        return t
    }

    companion object {
        const val NOTIF_ID = 41
        const val CHANNEL_CALL = "chamada_em_andamento"
        const val CHANNEL_STANDBY = "conectado"

        @Volatile var instance: CallService? = null
            private set
        @Volatile private var inCall = false
        @Volatile private var camera = false
        @Volatile private var screen = false
        @Volatile private var standby = false
        @Volatile private var title = "Chamada"

        private val main = Handler(Looper.getMainLooper())

        private fun kick(context: Context) {
            val i = instance
            if (i != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) { if (!i.refresh()) i.stopSelf() }
                else main.post { if (!i.refresh()) i.stopSelf() }
            } else if (inCall || standby) {
                try {
                    context.startForegroundService(Intent(context, CallService::class.java))
                } catch (e: Exception) {
                    android.util.Log.w("Falai", "Não deu para ligar o serviço: ${e.message}")
                }
            }
        }

        /** Começou uma chamada (o app está aberto, então o Android deixa). */
        fun start(context: Context, video: Boolean) {
            inCall = true; camera = video; screen = false; title = "Chamada"
            kick(context.applicationContext)
        }

        /** Atualiza o aviso. Chame na linha principal quando for [screen] = true (antes de capturar a tela). */
        fun update(context: Context, title: String, camera: Boolean, screen: Boolean = Companion.screen) {
            if (!inCall) return
            this.title = title; this.camera = camera; this.screen = screen
            kick(context.applicationContext)
        }

        fun stop(context: Context) {
            inCall = false; camera = false; screen = false
            kick(context.applicationContext)
        }

        /** "Sempre conectado": liga ou desliga o aviso discreto. */
        fun setStandby(context: Context, on: Boolean) {
            if (standby == on && (!on || instance != null)) return
            standby = on
            kick(context.applicationContext)
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_CALL, "Chamada em andamento", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STANDBY, "Falaí conectado (pode esconder)", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Mantém o Falaí pronto para tocar as chamadas. Pode desligar este aviso; o Falaí continua conectado."
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CallNotifications.CHANNEL_RING, "Chamadas recebidas", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // o próprio Falaí toca o toque do celular
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            nm.createNotificationChannel(NotificationChannel(CallNotifications.CHANNEL_MISSED, "Chamadas perdidas", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun actionIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, CallActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/** Botões dos avisos: "Recusar" e "Desligar". */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as FalaiApp).calls
        when (intent.action) {
            DECLINE -> calls.decline()
            HANGUP -> calls.hangup()
        }
    }

    companion object {
        const val DECLINE = "app.falai.call.DECLINE"
        const val HANGUP = "app.falai.call.HANGUP"
    }
}

/** Avisos de chamada: tocando (com tela cheia no celular bloqueado) e perdida. */
object CallNotifications {
    const val CHANNEL_RING = "chamadas"
    const val CHANNEL_MISSED = "chamadas_perdidas"
    private const val RING_ID = 42
    private const val MISSED_ID = 43

    const val ACTION_ANSWER = "app.falai.call.ANSWER"
    const val ACTION_SHOW = "app.falai.call.SHOW"
    const val EXTRA_VIDEO = "video"

    fun incoming(context: Context, call: CallDto, caller: String, group: String?, showFullScreen: Boolean) {
        CallService.createChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val show = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).setAction(ACTION_SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_VIDEO, call.video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kind = if (call.video) "Chamada de vídeo" else "Chamada de voz"
        val b = Notification.Builder(context, CHANNEL_RING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("$caller está ligando")
            .setContentText(if (group != null) "$kind no grupo $group" else kind)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(show)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Recusar", CallService.actionIntent(context, CallActionReceiver.DECLINE)).build())
            .addAction(Notification.Action.Builder(null, "Atender", answer).build())
        if (showFullScreen) b.setFullScreenIntent(show, true)
        runCatching { nm.notify(RING_ID, b.build()) }
    }

    fun cancelIncoming(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(RING_ID) }
    }

    fun missed(context: Context, caller: String, video: Boolean) {
        CallService.createChannels(context)
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Chamada perdida")
            .setContentText("$caller ligou (${if (video) "vídeo" else "voz"})")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(MISSED_ID, n) }
    }
}

/** Opção "Tocar chamadas com o app fechado" (ligada de fábrica). */
object KeepConnected {
    private const val KEY = "sempre_conectado"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences("falai", Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("falai", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        CallService.setStandby(context, on)
    }

    /** O Android já deixa o Falaí ligado mesmo economizando bateria? */
    fun batteryOk(context: Context): Boolean =
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Abre a pergunta do Android: "Deixar o Falaí funcionando em segundo plano?". */
    fun askBattery(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
