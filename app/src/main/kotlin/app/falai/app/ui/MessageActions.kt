package app.falai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.ConversationSummary
import app.falai.client.FalaiClient
import app.falai.client.LocalMessage

/** As reações rápidas (as mesmas no computador). */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** Menu que aparece ao segurar o dedo numa mensagem. Opções grandes, fáceis de tocar. */
@Composable
fun MessageActionsDialog(
    m: LocalMessage,
    mine: Boolean,
    canWrite: Boolean,
    myReaction: String?,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onForward: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val att = m.attachment
    val uploading = att != null && att.mediaId.isEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = {
            Text(
                when (att?.kind) {
                    "image" -> "Foto"
                    "video" -> "Vídeo"
                    "audio" -> "Áudio"
                    null -> "Mensagem"
                    else -> att.name
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                if (canWrite && m.seq != null) {
                    // Reagir: um toque no emoji (de novo no mesmo = tira).
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (e in QUICK_REACTIONS) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(if (e == myReaction) FalaiColors.Primary.copy(alpha = 0.25f) else Color.Transparent)
                                    .clickable { onReact(e) },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 24.sp) }
                        }
                    }
                    ActionRow("↩", "Responder", onReply)
                }
                if (uploading) {
                    Text("Espere o arquivo terminar de subir.", color = FalaiColors.TextSec)
                } else {
                    if (att != null) ActionRow("⬇", "Salvar no celular", onSave)
                    ActionRow("↪", "Encaminhar no Falaí", onForward)
                    ActionRow("📤", "Compartilhar (WhatsApp, e-mail…)", onShare)
                }
                if (m.body.isNotEmpty()) ActionRow("📋", "Copiar o texto", onCopy)
                if (mine) ActionRow("🗑️", "Apagar para todos", { confirmDelete = true }, FalaiColors.Error)
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Apagar para todos?") },
            text = { Text("A mensagem some para todo mundo da conversa. No lugar aparece \"Mensagem apagada\".") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteForEveryone() }) {
                    Text("Apagar", color = FalaiColors.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ActionRow(icon: String, label: String, onClick: () -> Unit, color: Color = Color.Unspecified) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 20.sp, modifier = Modifier.width(36.dp))
        Text(label, fontSize = 16.sp, color = color)
    }
}

/** Escolher para qual conversa encaminhar. */
@Composable
fun ForwardDialog(
    client: FalaiClient,
    me: String,
    onDismiss: () -> Unit,
    onPick: (ConversationSummary) -> Unit,
) {
    val list = remember { client.store.conversationSummaries(me) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Encaminhar para…") },
        text = {
            if (list.isEmpty()) {
                Text("Nenhuma conversa ainda.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.displayName, size = 40.dp, client = client, mediaId = c.avatarMediaId)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                c.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}
