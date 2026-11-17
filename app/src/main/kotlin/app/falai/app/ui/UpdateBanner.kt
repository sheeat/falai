package app.falai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.app.AppUpdater
import app.falai.client.FalaiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Faixa "Tem versão nova do Falaí" com o botão "Atualizar". Só aparece quando o servidor tem um
 * app mais novo que o deste celular.
 */
@Composable
fun UpdateBanner(client: FalaiClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by client.latestApp.collectAsState()
    val state by AppUpdater.state.collectAsState()
    val current = remember { AppUpdater.currentVersionCode(context) }
    var askPermission by remember { mutableStateOf(false) }
    var waitingPermission by remember { mutableStateOf(false) }
    val app = latest ?: return
    if (app.versionCode <= current) return

    fun start() {
        if (state is AppUpdater.State.Downloading || state is AppUpdater.State.Installing) return
        if (!AppUpdater.canInstall(context)) { askPermission = true; return }
        scope.launch {
            try {
                AppUpdater.setState(AppUpdater.State.Downloading(0f))
                val file = AppUpdater.updateFile(context)
                client.downloadApp(file) { p -> AppUpdater.setState(AppUpdater.State.Downloading(p)) }
                AppUpdater.setState(AppUpdater.State.Installing)
                AppUpdater.install(context, file)
                // Se o Android não respondeu (pessoa fechou a tela de confirmação), libera o botão de novo.
                delay(20_000)
                if (AppUpdater.state.value == AppUpdater.State.Installing) AppUpdater.setState(AppUpdater.State.Idle)
            } catch (e: Exception) {
                AppUpdater.setState(AppUpdater.State.Failed(e.message ?: "Não deu para atualizar agora. Tente de novo."))
            }
        }
    }

    // Voltou da tela "Permitir desta fonte" com a chave ligada: continua sozinho.
    LaunchedEffect(waitingPermission) {
        while (waitingPermission) {
            delay(700)
            if (AppUpdater.canInstall(context)) {
                start()
                waitingPermission = false
            }
        }
    }

    Column(
        modifier.fillMaxWidth().background(FalaiColors.Highlight).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("✨ Versão nova do Falaí: ${app.versionName}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val sub = when (val s = state) {
                    is AppUpdater.State.Downloading -> "Baixando… ${(s.progress * 100).toInt()}%"
                    AppUpdater.State.Installing -> "Instalando… se o Android perguntar, toque em \"Atualizar\"."
                    is AppUpdater.State.Failed -> s.message
                    AppUpdater.State.Idle -> "Toque em Atualizar. Suas conversas continuam."
                }
                Text(sub, fontSize = 13.sp, color = if (state is AppUpdater.State.Failed) FalaiColors.Error else FalaiColors.TextSec)
            }
            Spacer(Modifier.width(8.dp))
            val busy = state is AppUpdater.State.Downloading || state is AppUpdater.State.Installing
            Button(
                onClick = { start() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = FalaiColors.Primary),
            ) { Text(if (state is AppUpdater.State.Failed) "Tentar de novo" else "Atualizar") }
        }
        (state as? AppUpdater.State.Downloading)?.let {
            LinearProgressIndicator(
                progress = { it.progress },
                color = FalaiColors.Primary,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Só na primeira vez") },
            text = {
                Text(
                    "Para o Falaí se atualizar sozinho, o Android precisa da sua licença.\n\n" +
                        "Na próxima tela, ligue a chave \"Permitir desta fonte\" e depois volte para o Falaí. " +
                        "A atualização continua sozinha.",
                    fontSize = 15.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    waitingPermission = true
                    AppUpdater.openInstallPermission(context)
                }) { Text("Abrir", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Agora não") } },
        )
    }
}
