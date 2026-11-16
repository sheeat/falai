package app.falai.app

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

class MainActivity : ComponentActivity() {
    /** Convite que chegou por link (falai://convite?...), para preencher a tela de entrada. */
    private val pendingInvite = mutableStateOf<InviteLinks.Parsed?>(null)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readInvite(intent)
    }

    override fun onResume() {
        super.onResume()
        // Abriu o app: não espera o próximo intervalo de tentativa, conecta (e envia a fila) na hora.
        (application as FalaiApp).client.reconnectNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val client = (application as FalaiApp).client
        readInvite(intent)
        setContent {
            FalaiTheme {
                val account by client.account.collectAsState()
                if (account == null) {
                    PairScreen(client, pendingInvite.value)
                } else {
                    Navigation(client)
                }
            }
        }
    }
}

/** Navegação simples entre as telas, sem biblioteca extra. */
@Composable
private fun Navigation(client: app.falai.client.FalaiClient) {
    // "lista" | "nova" | "convite" | "chat:<id>"
    var route by rememberSaveable { mutableStateOf("lista") }
    BackHandler(enabled = route != "lista") { route = "lista" }
    when {
        route == "nova" -> NewConversationScreen(
            client,
            onBack = { route = "lista" },
            onCreated = { route = "chat:$it" },
        )
        route == "convite" -> InviteScreen(client, onBack = { route = "lista" })
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
        )
    }
}
