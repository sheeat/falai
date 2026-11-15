package app.falai.app.ui

import android.os.Build
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
import kotlinx.coroutines.launch

/** Primeira tela: liga este aparelho ao servidor da família com um código de convite. */
@Composable
fun PairScreen(client: FalaiClient) {
    var server by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var device by rememberSaveable { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
        Text("O mensageiro da família. Peça um código de convite para quem cuida do servidor.", color = FalaiColors.TextSec)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = server, onValueChange = { server = it },
            label = { Text("Endereço do servidor") },
            placeholder = { Text("falai.seudominio.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it.uppercase() },
            label = { Text("Código de convite") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Seu nome") },
            supportingText = { Text("Não precisa se este convite for para mais um aparelho seu.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = device, onValueChange = { device = it },
            label = { Text("Nome deste aparelho") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(it, color = FalaiColors.Error) }

        Button(
            enabled = !busy && server.isNotBlank() && code.isNotBlank() && device.isNotBlank(),
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

        Column(horizontalAlignment = Alignment.Start) {
            Text(
                "Testando em casa? No emulador use http://10.0.2.2:8443 e no próprio celular-servidor use http://127.0.0.1:8443.",
                color = FalaiColors.TextSec, fontSize = 13.sp,
            )
        }
    }
}
