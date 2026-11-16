package app.falai.app.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.FalaiClient
import app.falai.client.FalaiClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configurações: a minha foto e o meu nome, e informações do aparelho.
 * Tudo que muda aqui a família vê na hora.
 */
@Composable
fun SettingsScreen(client: FalaiClient, onBack: () -> Unit, onInvite: () -> Unit, onWebLink: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by client.changes.collectAsState()
    val account by client.account.collectAsState()
    val state by client.connection.collectAsState()
    val publicBase by client.publicBase.collectAsState()
    val acc = account ?: return
    val me = remember(changes) { client.store.user(acc.userId) }
    val myName = me?.name ?: ""

    var name by remember(myName) { mutableStateOf(myName) }
    var busy by remember { mutableStateOf<String?>(null) } // o que está fazendo agora
    var message by remember { mutableStateOf<String?>(null) }

    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

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
        if (uri != null) run("Enviando a foto…", "Foto trocada! ✔") {
            val file = withContext(Dispatchers.IO) { makeProfilePhoto(context, uri) }
                ?: throw FalaiClientException("photo", "Não consegui abrir essa foto. Escolha outra.")
            try {
                client.updateProfile(photo = file)
            } finally {
                file.delete()
            }
        }
    }

    // Some com o aviso de "pronto" depois de alguns segundos.
    LaunchedEffect(message) {
        if (message?.endsWith("✔") == true) {
            kotlinx.coroutines.delay(3000)
            message = null
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Primary).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text("Configurações", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Foto ----
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(132.dp).clip(CircleShape).clickable(enabled = busy == null) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(myName.ifEmpty { "?" }, size = 132.dp, client = client, mediaId = me?.avatarMediaId)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(
                    enabled = busy == null,
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) { Text(if (me?.avatarMediaId.isNullOrEmpty()) "Escolher foto" else "Trocar foto") }
                if (!me?.avatarMediaId.isNullOrEmpty()) {
                    TextButton(
                        enabled = busy == null,
                        onClick = { run("Tirando a foto…", "Foto removida ✔") { client.updateProfile(removePhoto = true) } },
                    ) { Text("Tirar foto", color = FalaiColors.TextSec) }
                }
            }

            // ---- Nome ----
            Text("Seu nome", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val nameChanged = name.trim().isNotEmpty() && name.trim() != myName
            Button(
                onClick = { run("Salvando o nome…", "Nome salvo ✔") { client.updateProfile(name = name.trim()) } },
                enabled = nameChanged && busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = FalaiColors.Primary),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Salvar nome") }

            // Aviso do que está acontecendo / resultado
            if (busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(busy!!, color = FalaiColors.TextSec)
                }
            }
            message?.let {
                Text(
                    it, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    color = if (it.endsWith("✔")) FalaiColors.Online else FalaiColors.Error,
                )
            }

            // ---- Chamadas ----
            HorizontalDivider(color = FalaiColors.Divider)
            Text("Chamadas", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            CallsSettings(client, acc.isAdmin)

            // ---- Computador ----
            HorizontalDivider(color = FalaiColors.Divider)
            Text("Computador", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            OutlinedButton(onClick = onWebLink, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("💻  Falaí no computador")
            }

            if (acc.isAdmin) {
                HorizontalDivider(color = FalaiColors.Divider)
                Text("Família", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                OutlinedButton(onClick = onInvite, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Convidar alguém da família")
                }
            }

            // ---- Sobre ----
            HorizontalDivider(color = FalaiColors.Divider)
            Text("Sobre este aparelho", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            InfoLine("Conexão", null) { ConnectionBadge(state, onDark = false) }
            InfoLine("Versão do Falaí", appVersion)
            InfoLine("Servidor", acc.serverUrl)
            publicBase?.let { InfoLine("Endereço na internet", it) }
            if (acc.isAdmin) InfoLine("Função", "Administrador da família")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?, content: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = FalaiColors.TextSec, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        if (content != null) content() else Text(value ?: "", fontSize = 14.sp)
    }
}

/** Tocar com o app fechado (e, para o administrador, o "ajudante de chamadas" da Cloudflare). */
@Composable
private fun CallsSettings(client: FalaiClient, isAdmin: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keep by remember { mutableStateOf(app.falai.app.call.KeepConnected.isOn(context)) }
    var batteryOk by remember { mutableStateOf(app.falai.app.call.KeepConnected.batteryOk(context)) }
    // Volta da pergunta do Android: confere de novo.
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1500); batteryOk = app.falai.app.call.KeepConnected.batteryOk(context) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tocar com o app fechado", fontSize = 15.sp)
            Text(
                "Deixa um aviso discreto \"Falaí conectado\" para as chamadas tocarem mesmo com o app fechado.",
                fontSize = 13.sp, color = FalaiColors.TextSec,
            )
        }
        androidx.compose.material3.Switch(
            checked = keep,
            onCheckedChange = { keep = it; app.falai.app.call.KeepConnected.set(context, it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = FalaiColors.Primary),
        )
    }
    if (keep && !batteryOk) {
        OutlinedButton(
            onClick = { app.falai.app.call.KeepConnected.askBattery(context) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("🔋  Não deixar o Android desligar o Falaí") }
        Text(
            "Toque e responda \"Permitir\". Sem isso, com o celular parado por muito tempo, a chamada pode não tocar.",
            fontSize = 13.sp, color = FalaiColors.TextSec,
        )
    }

    if (!isAdmin) return
    val turnOn by client.turnConfigured.collectAsState()
    var open by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ajudante de chamadas (4G)", fontSize = 15.sp)
            Text(
                if (turnOn == true) "✔ Ligado: chamadas funcionam mesmo entre dois celulares no 4G."
                else "Desligado. Se uma chamada ficar em \"conectando…\" no 4G, ligue aqui (grátis, Cloudflare).",
                fontSize = 13.sp, color = if (turnOn == true) FalaiColors.Online else FalaiColors.TextSec,
            )
        }
        Text(if (open) "▲" else "▼", color = FalaiColors.TextSec)
    }
    if (open) {
        Text(
            "Na Cloudflare: Realtime → TURN Server → Create. Copie o \"Turn Token ID\" e o \"API Token\" e cole aqui.",
            fontSize = 13.sp, color = FalaiColors.TextSec,
        )
        OutlinedTextField(keyId, { keyId = it.trim() }, label = { Text("Turn Token ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it.trim() }, label = { Text("API Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && keyId.isNotEmpty() && token.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = FalaiColors.Primary),
                onClick = {
                    busy = true; msg = null
                    scope.launch {
                        msg = try { client.setTurn(keyId, token).also { keyId = ""; token = "" } } catch (e: Exception) { e.message ?: "Não deu certo." }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Testando…" else "Salvar e testar") }
            if (turnOn == true) TextButton(enabled = !busy, onClick = {
                busy = true; msg = null
                scope.launch {
                    msg = try { client.setTurn("", "") } catch (e: Exception) { e.message ?: "Não deu certo." }
                    busy = false
                }
            }) { Text("Desligar", color = FalaiColors.Error) }
        }
        msg?.let { Text(it, fontSize = 14.sp, color = if (it.startsWith("Pronto")) FalaiColors.Online else FalaiColors.TextSec) }
    }
}
