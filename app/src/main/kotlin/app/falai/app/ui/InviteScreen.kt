package app.falai.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.falai.protocol.InviteLinks
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.FalaiClient
import app.falai.protocol.InviteFrame
import kotlinx.coroutines.launch

/** Só para o administrador: gera convites (link para mandar pelo WhatsApp, ou código). */
@Composable
fun InviteScreen(client: FalaiClient, onBack: () -> Unit) {
    val me = client.account.collectAsState().value ?: return
    val context = LocalContext.current
    var invite by remember { mutableStateOf<InviteFrame?>(null) }
    var forMe by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var apkStatus by remember { mutableStateOf<String?>(null) }
    val publicBase by client.publicBase.collectAsState()
    var address by remember { mutableStateOf("") }
    var savingAddress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Deixa o app disponível no servidor, para quem for convidado poder baixar pelo link.
    LaunchedEffect(Unit) {
        val info = runCatching {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, 0)
            Triple(java.io.File(context.applicationInfo.sourceDir), pi.longVersionCode, pi.versionName ?: "")
        }.getOrNull() ?: return@LaunchedEffect
        apkStatus = "Conferindo se o app está disponível para download…"
        val result = client.publishAppIfNewer(info.first, info.second, info.third)
        apkStatus = if (result != null) "✓ Quem receber o link consegue baixar o app (versão ${result.versionName})."
        else "O app não ficou disponível para download. Se precisar, mande o arquivo do app junto com o link."
    }

    fun generate(forOwnDevice: Boolean) {
        error = null
        scope.launch {
            try {
                invite = client.createInvite(if (forOwnDevice) me.userId else null)
                forMe = forOwnDevice
            } catch (e: Exception) {
                error = e.message ?: "Não deu para gerar o convite."
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Primary).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text("Convidar", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(onClick = { generate(false) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Convidar uma pessoa nova")
            }
            OutlinedButton(onClick = { generate(true) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Ligar mais um aparelho meu")
            }

            error?.let { Text(it, color = FalaiColors.Error) }

            invite?.let { inv ->
                // O link vem do servidor; se ele ainda não souber o próprio endereço, usa o deste aparelho.
                val link = inv.link
                    ?: publicBase?.let { InviteLinks.page(it, inv.code) }
                    ?: me.serverUrl.takeIf { it.startsWith("https://") }?.let { InviteLinks.page(it, inv.code) }
                val message = if (forMe) {
                    "Link para ligar mais um aparelho meu ao Falaí: $link"
                } else {
                    "Oi! Te convidei para o Falaí, o mensageiro da nossa família 💬\nToque no link para entrar: $link"
                }
                Column(
                    Modifier.fillMaxWidth().background(FalaiColors.ChatBg).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (link != null) {
                        Text(
                            if (forMe) "Abra este link no seu outro aparelho" else "Mande este link para a pessoa",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Button(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, message)
                                }
                                context.startActivity(Intent.createChooser(send, "Enviar convite"))
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Enviar convite (WhatsApp, SMS…)", fontSize = 16.sp) }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("Convite do Falaí", link))
                                Toast.makeText(context, "Link copiado", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Copiar link") }
                        Text(link, color = FalaiColors.TextSec, fontSize = 12.sp)
                    } else {
                        // Só na primeira vez: o servidor ainda não sabe o próprio endereço na internet.
                        Text("Qual é o endereço do Falaí na internet?", fontWeight = FontWeight.SemiBold)
                        Text("Só precisa informar uma vez. É o mesmo que você usou no celular pessoal.", color = FalaiColors.TextSec, fontSize = 13.sp)
                        OutlinedTextField(
                            value = address, onValueChange = { address = it.trim() },
                            placeholder = { Text("falai.seudominio.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = address.isNotBlank() && !savingAddress,
                            onClick = {
                                savingAddress = true
                                error = null
                                scope.launch {
                                    try {
                                        client.setPublicAddress(address)
                                    } catch (e: Exception) {
                                        error = e.message ?: "Não deu para salvar o endereço."
                                    } finally {
                                        savingAddress = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Salvar e criar o link") }
                    }
                    Text("Código", color = FalaiColors.TextSec, fontSize = 13.sp)
                    SelectionContainer {
                        Text(
                            inv.code.chunked(5).joinToString(" "),
                            fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text("Vale por 7 dias. Cada pessoa usa o seu próprio convite.", color = FalaiColors.TextSec, fontSize = 13.sp)
                }
            }

            apkStatus?.let { Text(it, color = FalaiColors.TextSec, fontSize = 13.sp) }
        }
    }
}
