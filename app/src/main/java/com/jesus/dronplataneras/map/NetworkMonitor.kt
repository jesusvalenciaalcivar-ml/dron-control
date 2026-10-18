package com.jesus.dronplataneras.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateOf

/**
 * Estado de internet observable desde Compose. En campo casi nunca hay datos: el mapa pasa a usar
 * solo los mosaicos descargados y la cuadrícula.
 *
 * Nota: el control remoto RC-N1 conecta por USB, no consume la red del teléfono, así que esto
 * solo refleja Wi-Fi / datos móviles.
 */
object NetworkMonitor {

    val isOnline = mutableStateOf(false)
    private var registered = false

    /**
     * Empieza a vigilar la conectividad y mantiene [isOnline] al día. Es idempotente.
     */
    fun start(context: Context) {
        if (registered) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        isOnline.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasValidatedInternet() == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                isOnline.value = caps.hasValidatedInternet()
            }

            override fun onLost(network: Network) {
                isOnline.value = false
            }
        })
        registered = true
    }

    private fun NetworkCapabilities.hasValidatedInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
