package app.falai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.FalaiClient
import app.falai.client.LocalMessage

@Composable
fun ChatScreen(client: FalaiClient, conversationId: String, onBack: () -> Unit) {
    val changes by client.changes.collectAsState()
    val state by client.connection.collectAsState()
    val me = client.account.collectAsState().value?.userId ?: return
    val summary = remember(changes) { client.store.conversationSummaries(me).firstOrNull { it.id == conversationId } }
    val messages = remember(changes) { client.store.messages(conversationId) }
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Sempre mostra a mensagem mais nova ao abrir ou quando chega uma.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(FalaiColors.ChatBg).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Primary).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("←", color = Color.White, fontSize = 22.sp) }
            Avatar(summary?.displayName ?: "?", size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    summary?.displayName ?: "Conversa", color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                ConnectionBadge(state)
            }
        }

        val isGroup = summary?.kind == "group"
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                Bubble(m, mine = m.authorId == me, authorName = if (isGroup) client.store.userName(m.authorId) else null)
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Mensagem") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White, focusedContainerColor = Color.White,
                ),
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    client.send(conversationId, text)
                    text = ""
                },
                shape = RoundedCornerShape(24.dp),
            ) { Text("Enviar") }
        }
    }
}

@Composable
private fun Bubble(m: LocalMessage, mine: Boolean, authorName: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (mine) FalaiColors.BubbleOut else FalaiColors.BubbleIn)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (!mine && authorName != null) {
                Text(authorName, color = FalaiColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(m.body, fontSize = 16.sp)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(m.createdAt), color = FalaiColors.TextSec, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    Text(statusIcon(m.status), fontSize = 11.sp, color = FalaiColors.TextSec)
                }
            }
        }
    }
}
