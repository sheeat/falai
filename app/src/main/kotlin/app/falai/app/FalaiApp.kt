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
import java.io.File

/** Guarda o "motor" do app (um só para o app inteiro). */
class FalaiApp : Application() {

    lateinit var client: FalaiClient
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ClientStore(AndroidDb(File(filesDir, "falai-app.db").path))
        client = FalaiClient(store, scope, log = { Log.i("Falai", it) })
        if (store.account() != null) client.start()

        // Quando a internet volta (troca de Wi-Fi para dados, etc.), reconecta na hora.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = client.reconnectNow()
            }
        )
    }
}
