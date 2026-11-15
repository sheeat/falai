package app.falai.server.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Liga o servidor sozinho quando o celular reinicia ou o app é atualizado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ServerService.start(context)
        }
    }
}
