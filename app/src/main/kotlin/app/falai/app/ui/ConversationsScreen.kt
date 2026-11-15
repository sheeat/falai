package app.falai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.ConversationSummary
import app.falai.client.FalaiClient
import app.falai.client.MessageStatus

@Composable
fun ConversationsScreen(
    client: FalaiClient,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onInvite: () -> Unit,
) {
    val changes by client.changes.collectAsState()
    val state by client.connection.collectAsState()
    val account by client.account.collectAsState()
    val me = account?.userId ?: return
    val list = remember(changes) { client.store.conversationSummaries(me) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            // Topo
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(FalaiColors.Primary)
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Falaí", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (account?.isAdmin == true) {
                        TextButton(onClick = onInvite) { Text("Convidar", color = Color.White) }
                    }
                }
                ConnectionBadge(state)
            }

            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nenhuma conversa ainda.\nToque em \"Nova conversa\" para começar.",
                        color = FalaiColors.TextSec, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { c ->
                        ConversationRow(c, me, onClick = { onOpen(c.id) })
                        HorizontalDivider(color = FalaiColors.Divider, modifier = Modifier.padding(start = 76.dp))
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNew,
            containerColor = FalaiColors.Primary,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Text("Nova conversa") }
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, me: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.displayName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                c.lastAt?.let { Text(formatTime(it), color = FalaiColors.TextSec, fontSize = 12.sp) }
            }
            val prefix = when {
                c.lastBody == null -> ""
                c.lastAuthorId == me -> statusIcon(c.lastStatus) + " Você: "
                else -> ""
            }
            Text(
                prefix + (c.lastBody ?: if (c.kind == "group") "Grupo criado" else "Conversa nova"),
                color = FalaiColors.TextSec, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun statusIcon(s: MessageStatus?): String = when (s) {
    MessageStatus.PENDING -> "🕓"
    MessageStatus.SENT -> "✓"
    MessageStatus.FAILED -> "⚠️"
    else -> ""
}

fun formatTime(epochMs: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd/MM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale("pt", "BR")).format(java.util.Date(epochMs))
}
