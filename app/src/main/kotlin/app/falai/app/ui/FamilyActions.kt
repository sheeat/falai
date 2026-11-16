package app.falai.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.ConversationSummary
import app.falai.client.FalaiClient
import app.falai.protocol.UserDto
import kotlinx.coroutines.launch

/** Pergunta simples de "tem certeza?", com o botão de confirmar em vermelho. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = FalaiColors.Error, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Apagar conversa?": some da minha lista (celular e computador). Para os outros nada muda. */
@Composable
fun DeleteConversationDialog(client: FalaiClient, c: ConversationSummary, onDone: (deleted: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = "Apagar conversa?",
        text = (if (c.kind == "group") "A conversa do grupo \"${c.displayName}\"" else "A conversa com ${c.displayName}") +
            " some da sua lista, aqui e no computador. Para os outros nada muda. Se chegar mensagem nova, ela volta.",
        confirm = "Apagar",
        onConfirm = {
            scope.launch {
                try {
                    client.hideConversation(c.id)
                    Toast.makeText(context, "Conversa apagada", Toast.LENGTH_SHORT).show()
                    onDone(true)
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Não deu para apagar agora.", Toast.LENGTH_LONG).show()
                    onDone(false)
                }
            }
        },
        onDismiss = { onDone(false) },
    )
}

/** Só o administrador: "Tirar X da família?". */
@Composable
fun RemoveFromFamilyDialog(client: FalaiClient, user: UserDto, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = "Tirar ${user.name} da família?",
        text = "Os aparelhos de ${user.name} são desligados do Falaí na hora e ${user.name} não aparece mais para começar " +
            "conversa. As mensagens antigas continuam. Para voltar, só com um convite novo.",
        confirm = "Tirar da família",
        onConfirm = {
            scope.launch {
                try {
                    client.removeUser(user.id)
                    Toast.makeText(context, "${user.name} saiu da família", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Não deu agora. Tente de novo.", Toast.LENGTH_LONG).show()
                }
                onDone()
            }
        },
        onDismiss = onDone,
    )
}

/** Só o administrador: lista da família, com "Tirar" em cada pessoa (útil para contas repetidas). */
@Composable
fun FamilyDialog(client: FalaiClient, onDismiss: () -> Unit) {
    val changes by client.changes.collectAsState()
    val me = client.account.collectAsState().value?.userId
    val people = remember(changes) { client.store.activeUsers() }
    var removing by remember { mutableStateOf<UserDto?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pessoas da família") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(people, key = { it.id }) { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.name, size = 40.dp, client = client, mediaId = u.avatarMediaId)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(u.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                u.id == me -> Text("Você · administrador", fontSize = 12.sp, color = FalaiColors.TextSec)
                                u.isAdmin -> Text("Administrador", fontSize = 12.sp, color = FalaiColors.TextSec)
                            }
                        }
                        if (u.id != me && !u.isAdmin) {
                            TextButton(onClick = { removing = u }) { Text("Tirar", color = FalaiColors.Error) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
    removing?.let { RemoveFromFamilyDialog(client, it) { removing = null } }
}
