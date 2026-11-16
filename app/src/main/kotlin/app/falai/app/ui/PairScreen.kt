package app.falai.app.ui

import android.content.ClipboardManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import app.falai.client.FalaiClient
import app.falai.client.FalaiClientException
import app.falai.protocol.InviteLinks
import kotlinx.coroutines.launch

/**
 * Primeira tela: liga este aparelho ao servidor da família.
 * Com [invite] (veio de um link de convite), só pede o nome; o resto já vem preenchido.
 */
@Composable
fun PairScreen(client: FalaiClient, invite: InviteLinks.Parsed? = null) {
    var server by rememberSaveable(invite) { mutableStateOf(invite?.server ?: "") }
    var code by rememberSaveable(invite) { mutableStateOf(invite?.code ?: "") }
    var name by rememberSaveable { mutableStateOf("") }
    var device by rememberSaveable { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    var showDetails by rememberSaveable(invite) { mutableStateOf(invite == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val forExistingUser = invite?.forExistingUser == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Falaí", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = FalaiColors.Primary)
        if (invite != null) {
            Text(
                if (forExistingUser) "Convite para ligar mais um aparelho seu. É só tocar em Entrar."
                else "Bem-vindo! 👋\nComo você quer ser chamado na família?",
                fontSize = 18.sp,
            )
        } else {
            Text(
                "O mensageiro da família. Toque no link de convite que você recebeu, ou cole o link aqui embaixo.",
                color = FalaiColors.TextSec,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (!forExistingUser) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Seu nome") },
                supportingText = if (invite == null) {
                    { Text("Não precisa se este convite for para mais um aparelho seu.") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showDetails) {
            OutlinedTextField(
                value = code,
                onValueChange = { typed ->
                    // Colou o link inteiro? Separa o endereço e o código sozinho.
                    val parsed = InviteLinks.parse(typed)
                    if (parsed != null) {
                        server = parsed.server
                        code = parsed.code
                    } else {
                        code = typed.uppercase()
                    }
                },
                label = { Text("Link ou código de convite") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Endereço do servidor") },
                placeholder = { Text("falai.seudominio.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = device, onValueChange = { device = it },
                label = { Text("Nome deste aparelho") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let { Text(it, color = FalaiColors.Error) }

        Button(
            enabled = !busy && server.isNotBlank() && code.isNotBlank() && device.isNotBlank() &&
                (forExistingUser || invite == null || name.isNotBlank()),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        client.pair(server, code, device, name.ifBlank { null })
                        client.start()
                    } catch (e: FalaiClientException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "Algo deu errado: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(24.dp), color = FalaiColors.BubbleIn)
            else Text("Entrar", fontSize = 17.sp)
        }

        if (invite != null && !showDetails) {
            TextButton(onClick = { showDetails = true }) { Text("Ver detalhes do convite", color = FalaiColors.TextSec) }
        }

        if (invite == null) {
            // Copiou o link do convite? Um toque e preenche tudo.
            val context = LocalContext.current
            TextButton(onClick = {
                val text = runCatching {
                    context.getSystemService(ClipboardManager::class.java)
                        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                }.getOrNull()
                val parsed = text?.let { InviteLinks.parse(it) }
                if (parsed != null) {
                    server = parsed.server
                    code = parsed.code
                    error = null
                } else {
                    error = "Não achei um link de convite copiado. Abra o link que você recebeu."
                }
            }) { Text("📋 Colar o link do convite") }
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    "Testando em casa? No emulador use http://10.0.2.2:8443 e no próprio celular-servidor use http://127.0.0.1:8443.",
                    color = FalaiColors.TextSec, fontSize = 13.sp,
                )
            }
        }
    }
}
