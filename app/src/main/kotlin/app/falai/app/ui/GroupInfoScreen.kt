package app.falai.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.ConversationSummary
import app.falai.client.FalaiClient
import app.falai.client.FalaiClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Dados do grupo": foto, nome, quem está no grupo, pôr e tirar pessoas, sair.
 * Qualquer um do grupo muda nome e foto e põe gente; tirar alguém só quem criou o grupo
 * (ou o administrador da família).
 */
@Composable
fun GroupInfoScreen(client: FalaiClient, summary: ConversationSummary, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by client.changes.collectAsState()
    val acc = client.account.collectAsState().value ?: return
    val me = acc.userId
    val members = remember(changes, summary) {
        summary.memberIds.mapNotNull { client.store.user(it) }
            .sortedWith(compareBy({ it.id != me }, { it.id != summary.createdBy }, { it.name.lowercase() }))
    }
    val canRemove = summary.iAmMember && (me == summary.createdBy || acc.isAdmin)
    var title by remember(summary.displayName) { mutableStateOf(summary.displayName) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<app.falai.protocol.UserDto?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    BackHandler(onBack = onBack)

    fun run(what: String, done: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        message = null
        scope.launch {
            message = try {
                block()
                done
            } catch (e: FalaiClientException) {
                e.message ?: "Não deu certo. Tente de novo."
            } catch (e: Exception) {
                "Não deu certo. Tente de novo."
            }
            busy = null
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && busy == null) {
            busy = "Abrindo a foto…"
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val copied = copyFromUri(context, uri)
                    try { copied?.let { loadThumbnail(it.file, 2048) } } finally { copied?.file?.delete() }
                }
                busy = null
                if (bmp == null) message = "Não consegui abrir essa foto. Escolha outra." else cropping = bmp
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(FalaiColors.Surface).imePadding()) {
            Row(
                Modifier.fillMaxWidth().background(FalaiColors.Header).statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onClick = onBack)
                Text("Dados do grupo", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- Foto ----
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clickable(enabled = summary.iAmMember) { photoMenu = true }) {
                        Avatar(summary.displayName, size = 120.dp, client = client, mediaId = summary.avatarMediaId)
                    }
                    if (summary.iAmMember) {
                        TextButton(onClick = { photoMenu = true }) {
                            Text(if (summary.avatarMediaId.isNullOrEmpty()) "📷 Pôr foto do grupo" else "📷 Trocar foto")
                        }
                    }
                }

                // ---- Nome ----
                if (summary.iAmMember) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it.take(60) },
                        label = { Text("Nome do grupo") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (title.trim() != summary.displayName && title.isNotBlank()) {
                        Button(
                            onClick = { run("Salvando o nome…", "Nome trocado! ✔") { client.updateGroup(summary.id, title = title) } },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("Salvar o nome") }
                    }
                } else {
                    Text(summary.displayName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Você não está mais neste grupo. Dá para ler as mensagens antigas.",
                        color = FalaiColors.TextSec, fontSize = 14.sp,
                    )
                }

                busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = FalaiColors.TextSec)
                    }
                }
                message?.let {
                    Text(it, color = if (it.endsWith("✔")) FalaiColors.Online else FalaiColors.Error, fontSize = 14.sp)
                }

                // ---- Pessoas ----
                HorizontalDivider(color = FalaiColors.Divider)
                Text("${members.size} pessoas no grupo", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (summary.iAmMember) {
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("➕  Pôr pessoas no grupo")
                    }
                }
                for (u in members) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.name, size = 42.dp, client = client, mediaId = u.avatarMediaId)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (u.id == me) "Você" else u.name, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val tags = listOfNotNull(
                                "criou o grupo".takeIf { u.id == summary.createdBy },
                                "administrador da família".takeIf { u.isAdmin },
                            )
                            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), fontSize = 12.sp, color = FalaiColors.TextSec)
                        }
                        if (canRemove && u.id != me) {
                            TextButton(onClick = { removing = u }) { Text("Tirar", color = FalaiColors.Error) }
                        }
                    }
                }

                // ---- Sair ----
                if (summary.iAmMember) {
                    HorizontalDivider(color = FalaiColors.Divider)
                    TextButton(onClick = { leaving = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("🚪  Sair do grupo", color = FalaiColors.Error, fontSize = 16.sp)
                    }
                }
            }
        }
        cropping?.let { source ->
            AvatarCropScreen(
                source,
                onCancel = { cropping = null },
                onDone = { left, top, side, bmp ->
                    cropping = null
                    run("Enviando a foto…", "Foto do grupo trocada! ✔") {
                        val file = withContext(Dispatchers.IO) { saveCroppedAvatar(context, bmp, left, top, side) }
                            ?: throw FalaiClientException("photo", "Não consegui preparar a foto. Tente de novo.")
                        try {
                            client.updateGroup(summary.id, photo = file)
                        } finally {
                            file.delete()
                        }
                    }
                },
            )
        }
    }

    if (photoMenu) {
        AlertDialog(
            onDismissRequest = { photoMenu = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { photoMenu = false }) { Text("Fechar") } },
            title = { Text("Foto do grupo") },
            text = {
                Column {
                    Text(
                        "🖼️  Escolher da galeria", fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            photoMenu = false
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.padding(vertical = 14.dp),
                    )
                    if (!summary.avatarMediaId.isNullOrEmpty()) {
                        Text(
                            "🗑️  Tirar a foto", fontSize = 16.sp, color = FalaiColors.Error,
                            modifier = Modifier.fillMaxWidth().clickable {
                                photoMenu = false
                                run("Tirando a foto…", "Foto tirada ✔") { client.updateGroup(summary.id, removePhoto = true) }
                            }.padding(vertical = 14.dp),
                        )
                    }
                }
            },
        )
    }

    if (adding) {
        AddPeopleDialog(client, exclude = summary.memberIds.toSet(), onDismiss = { adding = false }) { ids ->
            adding = false
            run("Pondo no grupo…", if (ids.size == 1) "Pessoa adicionada ✔" else "Pessoas adicionadas ✔") {
                client.updateGroup(summary.id, add = ids)
            }
        }
    }

    removing?.let { u ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Tirar ${u.name} do grupo?") },
            text = { Text("${u.name} para de receber as mensagens deste grupo. Dá para pôr de volta depois.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    run("Tirando do grupo…", "${u.name} saiu do grupo ✔") { client.updateGroup(summary.id, remove = listOf(u.id)) }
                }) { Text("Tirar", color = FalaiColors.Error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Sair do grupo?") },
            text = { Text("Você para de receber as mensagens. As antigas continuam aqui para ler. Alguém do grupo pode te pôr de volta.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    run("Saindo…", "Você saiu do grupo ✔") { client.leaveGroup(summary.id) }
                }) { Text("Sair", color = FalaiColors.Error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancelar") } },
        )
    }
}

/** Escolher (marcando) quem pôr no grupo. */
@Composable
private fun AddPeopleDialog(client: FalaiClient, exclude: Set<String>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val people = remember { client.store.activeUsers().filter { it.id !in exclude } }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pôr no grupo") },
        text = {
            if (people.isEmpty()) {
                Text("Todo mundo da família já está no grupo.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(people, key = { it.id }) { u ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (u.id in picked) picked.remove(u.id) else picked.add(u.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = u.id in picked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Avatar(u.name, size = 36.dp, client = client, mediaId = u.avatarMediaId)
                            Spacer(Modifier.width(10.dp))
                            Text(u.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onAdd(picked.toList()) }) {
                Text(if (picked.size > 1) "Pôr ${picked.size} pessoas" else "Pôr no grupo", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
