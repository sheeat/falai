package app.falai.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Atualização do Falaí pelo próprio Falaí (sem loja): baixa o app novo do servidor da família e
 * pede ao Android para instalar por cima. Os dados (conversas, conta) continuam.
 *
 * Na primeira vez o Android pergunta se o Falaí pode instalar apps ("Permitir desta fonte").
 * Depois disso, cada atualização é só tocar em "Atualizar" (no Android 12 ou mais novo, muitas
 * vezes nem pergunta de novo).
 */
object AppUpdater {
    private const val ACTION_RESULT = "app.falai.UPDATE_RESULT"

    /** O que está acontecendo com a atualização agora (para a tela mostrar). */
    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Float) : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun setState(s: State) { _state.value = s }

    data class SelfApk(val file: File, val versionCode: Long, val versionName: String)

    /** O arquivo do app instalado neste celular e a versão dele. */
    fun selfApk(context: Context): SelfApk? = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        SelfApk(File(context.applicationInfo.sourceDir), pi.longVersionCode, pi.versionName ?: "")
    }.getOrNull()

    fun currentVersionCode(context: Context): Long = selfApk(context)?.versionCode ?: 0L

    /** O Android já deixa o Falaí instalar apps? (a chave "Permitir desta fonte") */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Abre a tela do Android onde a pessoa liga "Permitir desta fonte" para o Falaí. */
    fun openInstallPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    fun updateFile(context: Context) = File(context.cacheDir, "atualizacao/falai.apk").also { it.parentFile?.mkdirs() }

    /** Entrega o arquivo ao instalador do Android. O resultado chega em [ResultReceiver]. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // O app atualizando a si mesmo: o Android pode instalar sem perguntar de novo.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("falai.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, ResultReceiver::class.java).setAction(ACTION_RESULT)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Resposta do instalador do Android. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            Log.i("Falai", "Atualização: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // O Android quer que a pessoa confirme: mostra a tela "Atualizar este app?".
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                            .onFailure { setState(State.Failed("Não consegui abrir o instalador. Tente de novo.")) }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> setState(State.Idle) // o app reinicia na versão nova
                PackageInstaller.STATUS_FAILURE_ABORTED -> setState(State.Idle) // a pessoa cancelou
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    setState(State.Failed("O Android não aceitou o app novo (assinatura diferente). Peça ajuda ao administrador."))
                PackageInstaller.STATUS_FAILURE_STORAGE -> setState(State.Failed("Falta espaço no celular para atualizar."))
                PackageInstaller.STATUS_FAILURE_BLOCKED -> setState(State.Failed("O Android bloqueou a atualização. Tente de novo e toque em \"Instalar mesmo assim\"."))
                else -> setState(State.Failed("Não deu para atualizar agora. Tente de novo."))
            }
        }
    }
}
