package app.falai.app.ui

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

/** Só para o administrador: gera códigos de convite. */
@Composable
fun InviteScreen(client: FalaiClient, onBack: () -> Unit) {
    val me = client.account.collectAsState().value ?: return
    var invite by remember { mutableStateOf<InviteFrame?>(null) }
    var forMe by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
            TextButton(onClick = onBack) { Text("←", color = Color.White, fontSize = 22.sp) }
            Text("Convidar", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = { generate(false) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Convidar uma pessoa nova")
            }
            OutlinedButton(onClick = { generate(true) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Ligar mais um aparelho meu")
            }

            error?.let { Text(it, color = FalaiColors.Error) }

            invite?.let { inv ->
                Column(
                    Modifier.fillMaxWidth().background(FalaiColors.ChatBg).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (forMe) "Código para o seu outro aparelho" else "Código para a pessoa nova",
                        color = FalaiColors.TextSec,
                    )
                    SelectionContainer {
                        Text(
                            inv.code.chunked(5).joinToString(" "),
                            fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text("Vale por 24 horas e só pode ser usado uma vez.", color = FalaiColors.TextSec, fontSize = 13.sp)
                }
            }
        }
    }
}
