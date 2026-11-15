package app.falai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.FalaiClient
import kotlinx.coroutines.launch

/** Escolher uma pessoa (conversa direta) ou várias (grupo). */
@Composable
fun NewConversationScreen(client: FalaiClient, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val changes by client.changes.collectAsState()
    val me = client.account.collectAsState().value?.userId ?: return
    val people = remember(changes) { client.store.users().filter { it.id != me } }
    val selected = remember { mutableStateListOf<String>() }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Atualiza a lista de pessoas (alguém pode ter entrado na família agora).
    LaunchedEffect(Unit) { runCatching { client.refreshUsers() } }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Primary).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("←", color = Color.White, fontSize = 22.sp) }
            Text("Nova conversa", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }

        if (people.isEmpty()) {
            Text(
                "Ainda não tem mais ninguém na família. Use \"Convidar\" na tela inicial.",
                color = FalaiColors.TextSec, modifier = Modifier.padding(24.dp),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(people, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (p.id in selected) selected.remove(p.id) else selected.add(p.id)
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.name, size = 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(p.name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Checkbox(checked = p.id in selected, onCheckedChange = null)
                }
            }
        }

        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            val isGroup = selected.size > 1
            if (isGroup) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Nome do grupo") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            error?.let { Text(it, color = FalaiColors.Error); Spacer(Modifier.height(8.dp)) }
            Button(
                enabled = !busy && selected.isNotEmpty() && (!isGroup || title.isNotBlank()),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val c = client.createConversation(
                                if (isGroup) "group" else "direct",
                                if (isGroup) title.trim() else null,
                                selected.toList(),
                            )
                            onCreated(c.id)
                        } catch (e: Exception) {
                            error = e.message ?: "Não deu para criar a conversa."
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (isGroup) "Criar grupo" else "Conversar") }
        }
    }
}
