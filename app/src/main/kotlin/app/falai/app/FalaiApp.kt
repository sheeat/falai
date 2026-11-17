package app.falai.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import app.falai.client.ClientStore
import app.falai.client.FalaiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Guarda o "motor" do app (um só para o app inteiro). */
class FalaiApp : Application() {

    lateinit var client: FalaiClient
        private set

    /** Chamadas de voz e vídeo. */
    lateinit var calls: app.falai.app.call.CallManager
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ClientStore(AndroidDb(File(filesDir, "falai-app.db").path))
        client = FalaiClient(store, scope, File(filesDir, "media"), log = { Log.i("Falai", it) })
        calls = app.falai.app.call.CallManager(this, client, scope)
        if (store.account() != null) client.start()

        // Administrador: quando conecta, deixa no servidor o app deste celular se for mais novo.
        // Assim os outros celulares ficam sabendo da versão nova e mostram "Atualizar".
        scope.launch {
            var published = 0L
            client.connection.collect { st ->
                val acc = client.account.value
                if (st == app.falai.client.ConnectionState.ONLINE && acc?.isAdmin == true) {
                    val self = AppUpdater.selfApk(this@FalaiApp) ?: return@collect
                    if (published >= self.versionCode) return@collect
                    val info = client.publishAppIfNewer(self.file, self.versionCode, self.versionName)
                    if (info != null && info.versionCode >= self.versionCode) published = self.versionCode
                }
            }
        }

        // Quando a internet volta (troca de Wi-Fi para dados, etc.), reconecta na hora.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = client.reconnectNow()
            }
        )
    }
}
