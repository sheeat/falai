package app.falai.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.falai.protocol.InviteLinks
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.falai.app.ui.ChatScreen
import app.falai.app.ui.ConversationsScreen
import app.falai.app.ui.FalaiTheme
import app.falai.app.ui.InviteScreen
import app.falai.app.ui.NewConversationScreen
import app.falai.app.ui.PairScreen
import app.falai.app.ui.SettingsScreen
import app.falai.app.ui.WebLinkScreen

class MainActivity : ComponentActivity() {
    /** Convite que chegou por link (falai://convite?...), para preencher a tela de entrada. */
    private val pendingInvite = mutableStateOf<InviteLinks.Parsed?>(null)

    /** "Atender" tocado no aviso: true = com vídeo, false = só voz. */
    private val answerFromNotification = mutableStateOf<Boolean?>(null)

    private fun readCallAction(intent: Intent?) {
        if (intent?.action == app.falai.app.call.CallNotifications.ACTION_ANSWER) {
            answerFromNotification.value = intent.getBooleanExtra(app.falai.app.call.CallNotifications.EXTRA_VIDEO, false)
            app.falai.app.call.CallNotifications.cancelIncoming(this)
        }
    }

    private val askNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** Celular bloqueado: a chamada aparece por cima; durante a chamada, a tela não apaga sozinha. */
    private fun followCall(ui: app.falai.app.call.CallUi) {
        val ringingOrInCall = ui.incoming != null || ui.active != null
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(ringingOrInCall)
            setTurnScreenOn(ui.incoming != null)
        }
        if (ui.active != null) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun readInvite(intent: Intent?) {
        val data = intent?.dataString ?: return
        val parsed = InviteLinks.parse(data) ?: return
        val client = (application as FalaiApp).client
        if (client.account.value != null) {
            Toast.makeText(this, "Este aparelho já está conectado ao Falaí.", Toast.LENGTH_LONG).show()
        } else {
            pendingInvite.value = parsed
        }
    }

    /**
     * Primeira vez que o app abre depois de instalado pela página do convite: a página guardou o
     * link na área de transferência. Lê só se ainda não está pareado e não chegou convite por link.
     * (O Android só deixa ler com o app em primeiro plano, por isso aqui.)
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || pendingInvite.value != null) return
        if ((application as FalaiApp).client.account.value != null) return
        val text = runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: return
        InviteLinks.parse(text)?.let { pendingInvite.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readInvite(intent)
        readCallAction(intent)
    }

    override fun onResume() {
        super.onResume()
        // Abriu o app: não espera o próximo intervalo de tentativa, conecta (e envia a fila) na hora.
        val falai = application as FalaiApp
        falai.client.reconnectNow()
        falai.calls.appVisible = true
        // "Sempre conectado": as chamadas tocam mesmo com o app fechado.
        if (falai.client.account.value != null) {
            app.falai.app.call.CallService.setStandby(this, app.falai.app.call.KeepConnected.isOn(this))
        }
    }

    override fun onPause() {
        super.onPause()
        (application as FalaiApp).calls.appVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val client = (application as FalaiApp).client
        readInvite(intent)
        readCallAction(intent)
        val calls = (application as FalaiApp).calls
        // Android 13+: pede uma vez para mostrar avisos (chamadas tocando, chamadas perdidas).
        if (android.os.Build.VERSION.SDK_INT >= 33 && client.account.value != null &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = getSharedPreferences("falai", MODE_PRIVATE)
            if (!prefs.getBoolean("pediu_avisos", false)) {
                prefs.edit().putBoolean("pediu_avisos", true).apply()
                askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            FalaiTheme {
                val account by client.account.collectAsState()
                val callUi by calls.ui.collectAsState()
                androidx.compose.runtime.LaunchedEffect(callUi.incoming != null, callUi.active != null) { followCall(callUi) }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier) {
                    if (account == null) {
                        PairScreen(client, pendingInvite.value)
                    } else {
                        Navigation(client)
                        app.falai.app.call.CallOverlay(
                            client, calls,
                            answerNow = answerFromNotification.value,
                            onAnswerHandled = { answerFromNotification.value = null },
                        )
                    }
                }
            }
        }
    }
}

/** Navegação simples entre as telas, sem biblioteca extra. */
@Composable
private fun Navigation(client: app.falai.client.FalaiClient) {
    // "lista" | "nova" | "convite" | "ajustes" | "computador" | "chat:<id>"
    var route by rememberSaveable { mutableStateOf("lista") }
    BackHandler(enabled = route != "lista") { route = if (route == "computador") "ajustes" else "lista" }
    when {
        route == "nova" -> NewConversationScreen(
            client,
            onBack = { route = "lista" },
            onCreated = { route = "chat:$it" },
        )
        route == "convite" -> InviteScreen(client, onBack = { route = "lista" })
        route == "ajustes" -> SettingsScreen(
            client, onBack = { route = "lista" }, onInvite = { route = "convite" }, onWebLink = { route = "computador" },
        )
        route == "computador" -> WebLinkScreen(client, onBack = { route = "ajustes" })
        route.startsWith("chat:") -> ChatScreen(
            client,
            conversationId = route.removePrefix("chat:"),
            onBack = { route = "lista" },
        )
        else -> ConversationsScreen(
            client,
            onOpen = { route = "chat:$it" },
            onNew = { route = "nova" },
            onInvite = { route = "convite" },
            onSettings = { route = "ajustes" },
        )
    }
}
