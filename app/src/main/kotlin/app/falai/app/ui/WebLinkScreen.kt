package app.falai.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.FalaiClient
import app.falai.client.FalaiClientException
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * Falaí no computador: o computador mostra um QR Code em falai.SEUDOMINIO/web,
 * o celular lê e pronto, as conversas aparecem no navegador.
 */
@Composable
fun WebLinkScreen(client: FalaiClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val publicBase by client.publicBase.collectAsState()
    val account by client.account.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }

    fun approve(text: String) {
        if (busy) return
        busy = true
        error = null
        result = null
        scope.launch {
            try {
                val name = client.approveWeb(text)
                result = "Pronto! O Falaí abriu no $name. ✔"
                typed = ""
            } catch (e: FalaiClientException) {
                error = e.message
            } catch (e: Exception) {
                error = "Não deu certo. Tente de novo."
            }
            busy = false
        }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) approve(text)
    }

    fun openScanner() {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Aponte para o QR Code na tela do computador")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    // Endereço que a pessoa digita no computador.
    val address = (publicBase ?: account?.serverUrl ?: "").removePrefix("https://").removePrefix("http://").trimEnd('/') + "/web"

    Column(Modifier.fillMaxSize().background(FalaiColors.Surface).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Header).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text("Falaí no computador", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Use o Falaí no navegador do computador, ao mesmo tempo que no celular.", fontSize = 16.sp)
            Step(1, "No computador, abra o navegador e entre em:")
            Text(
                address,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = FalaiColors.PrimaryDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FalaiColors.ChatBg).padding(12.dp),
            )
            Step(2, "Vai aparecer um QR Code. Toque no botão abaixo e aponte a câmera para ele.")

            Button(
                onClick = { openScanner() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = FalaiColors.Primary),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("📷  Ler o QR Code", fontSize = 17.sp) }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Ligando o computador…", color = FalaiColors.TextSec)
                }
            }
            result?.let {
                Text(it, color = FalaiColors.Online, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            error?.let { Text(it, color = FalaiColors.Error, fontSize = 15.sp) }

            Spacer(Modifier.height(8.dp))
            Text("A câmera não leu? Digite o código que aparece embaixo do QR Code:", color = FalaiColors.TextSec, fontSize = 14.sp)
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.uppercase().take(12) },
                singleLine = true,
                placeholder = { Text("ABCD 2345") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { approve(typed) },
                enabled = !busy && typed.count { it.isLetterOrDigit() } == 8,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Ligar com o código") }

            Spacer(Modifier.height(8.dp))
            Text(
                "Para desligar um computador, toque em \"Sair\" no topo da lista de conversas, no próprio navegador. " +
                    "Use só em computadores da família.",
                color = FalaiColors.TextSec, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$n", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(FalaiColors.Primary).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 16.sp)
    }
}
