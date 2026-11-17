package app.falai.app

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.falai.protocol.ApkStamp
import app.falai.protocol.InviteLinks

/**
 * Quem é este aparelho, mesmo depois de desinstalar e instalar o Falaí de novo.
 *
 * O Android não deixa mais os apps lerem o número de série nem o MAC. O que ele oferece é o
 * ANDROID_ID: um número próprio deste aparelho para os apps assinados pela mesma chave, que só
 * muda se o celular for formatado. O servidor guarda só um resumo dele, e quando o app volta
 * (reinstalado) o servidor reconhece o aparelho e entra na conta certa, sem convite.
 */
object DeviceIdentity {
    @SuppressLint("HardwareIds")
    fun installKey(context: Context): String? {
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest("falai-install:$id".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** O que veio carimbado no arquivo do app que a pessoa baixou: um convite ou o endereço do servidor. */
    data class Stamp(val invite: InviteLinks.Parsed?, val server: String?)

    fun readStamp(context: Context): Stamp? {
        val text = runCatching { ApkStamp.read(java.io.File(context.applicationInfo.sourceDir)) }.getOrNull() ?: return null
        val invite = InviteLinks.parse(text)
        val server = invite?.server ?: InviteLinks.parseServer(text)
        if (invite == null && server == null) return null
        return Stamp(invite, server)
    }
}
