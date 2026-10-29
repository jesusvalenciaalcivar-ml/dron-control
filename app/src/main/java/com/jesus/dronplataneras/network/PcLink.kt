package com.jesus.dronplataneras.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import java.net.Inet4Address
import java.nio.ByteBuffer

/**
 * Enlace con la app de escritorio. La PC crea el Wi-Fi "AgroScan" (zona móvil de Windows) y siempre es 192.168.137.1.
 * La app se une sola a esa red con la contraseña que muestra la PC (sin pasar por Ajustes de Wi-Fi). Sobre este enlace
 * [TelemetryStream] manda la telemetría en vivo (UDP, ver docs/PROTOCOLO_PC.md); aún no se envían polígonos ni historial.
 */
object PcLink {

    const val DEFAULT_PC_IP = "192.168.137.1"
    const val WIFI_SSID = "AgroScan"
    const val INVALID_IP = "La IP de la PC no es válida"
    const val INVALID_PASSWORD = "La contraseña del Wi-Fi AgroScan (la muestra la PC) debe tener de 8 a 63 caracteres, sin tildes ni ñ"

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    val state = mutableStateOf(State.DISCONNECTED)

    /**
     * La red Wi-Fi de la PC: la que pidió la app o, si el teléfono ya estaba unido desde Ajustes, esa misma. Ojo: no es la red por defecto del teléfono (no tiene internet), así que
     * el tráfico hacia la PC debe salir por ella (`network.socketFactory`), no con un socket normal.
     */
    @Volatile
    var network: Network? = null
        private set

    /** IP de la PC de la última conexión pedida (null si nunca se pidió). Es a donde manda sus paquetes [TelemetryStream]. */
    @Volatile
    var pcHost: String? = null
        private set

    // Solo en memoria: sirve para volver a pedir la conexión si el Wi-Fi se cae a mitad de una misión
    private var lastPassword: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Conecta con la PC. Si el teléfono ya está en la red de la PC (unido desde Ajustes) basta con eso; si no, la app se une al Wi-Fi
     * [WIFI_SSID] con [password]. Android pide al usuario confirmar la conexión una vez. [onError] recibe el mensaje para el operador.
     */
    fun connect(context: Context, pcIp: String, password: String, onError: (String) -> Unit) {
        val pc = parseIPv4(pcIp.trim()) ?: return onError(INVALID_IP)
        pcHost = pcIp.trim()
        lastPassword = password
        if (state.value == State.CONNECTING || (state.value == State.CONNECTED && network != null)) return
        pcSubnetNetwork(context, pc)?.let { joined ->
            // Unido desde Ajustes: hay que guardar esa red igual, porque si no los paquetes salen por la red por defecto
            // (los datos móviles, si están encendidos) y nunca llegan a la PC
            watchLoss(context.applicationContext, joined)
            network = joined
            state.value = State.CONNECTED
            LinkLog.log("wifi", "Conectado a la PC $pcHost (el teléfono ya estaba en su Wi-Fi, unido desde Ajustes)")
            return
        }
        if (!isValidPassword(password)) return onError(INVALID_PASSWORD).also { LinkLog.log("wifi", "No se conectó: contraseña no válida") }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return onError("En este Android hay que unirse al Wi-Fi AgroScan desde Ajustes")
        LinkLog.log("wifi", "Pidiendo a Android el Wi-Fi $WIFI_SSID de la PC $pcHost")
        requestWifi(context.applicationContext, pc, password, onError)
    }

    /**
     * Si el enlace se cayó, lo pide de nuevo con los datos de la última vez. No hace nada si nunca se conectó. El error no se
     * muestra: ya se mostró la primera vez y esto se repite solo.
     */
    fun reconnect(context: Context) {
        val host = pcHost ?: return
        if (state.value != State.DISCONNECTED) return
        LinkLog.log("wifi", "Reintentando la conexión con la PC")
        connect(context, host, lastPassword.orEmpty()) { LinkLog.log("wifi", "El reintento falló: $it") }
    }

    /**
     * ¿Toca volver a pedir el Wi-Fi de la PC? En misión siempre, cada 10 s. Fuera de misión cada 30 s pero solo los primeros 5 min
     * después de perderlo: más tarde lo normal es que la PC esté apagada y Android molestaría con su aviso para nada.
     * [lostAt] es cuándo se perdió (0 si nunca hubo conexión). Función pura.
     */
    fun shouldReconnect(now: Long, lostAt: Long, lastAttemptAt: Long, inMission: Boolean): Boolean = when {
        inMission -> now - lastAttemptAt >= 10_000
        lostAt == 0L -> false
        else -> now - lostAt <= 5 * 60_000 && now - lastAttemptAt >= 30_000
    }

    /** Suelta la conexión con la PC (Android vuelve a la red anterior del teléfono). */
    fun disconnect(context: Context) {
        callback?.let { runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        network = null
        state.value = State.DISCONNECTED
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestWifi(context: Context, pc: Int, password: String, onError: (String) -> Unit) {
        disconnect(context)
        state.value = State.CONNECTING
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val (ip, prefix) = ipv4Of(linkProperties) ?: return
                if (sameSubnet(ip, pc, prefix)) {
                    this@PcLink.network = network
                    if (state.value != State.CONNECTED) LinkLog.log("wifi", "Conectado al Wi-Fi de la PC (IP del teléfono ${linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress})")
                    state.value = State.CONNECTED
                } else {
                    fail(context, onError, "El Wi-Fi AgroScan no está en la subred de la PC ($pc): revisa la IP de la PC")
                }
            }

            override fun onUnavailable() = fail(context, onError, "No se pudo unir al Wi-Fi AgroScan: revisa la contraseña y que la red de la PC esté encendida")

            override fun onLost(network: Network) {
                LinkLog.log("wifi", "SE PERDIÓ el Wi-Fi de la PC")
                this@PcLink.network = null
                state.value = State.DISCONNECTED
            }
        }
        callback = cb
        // El SDK de Android puede lanzar al armar o enviar la petición (permiso, demasiadas peticiones, contraseña rechazada):
        // sin esto el estado se quedaría en "Conectando…" para siempre
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // la red de la PC no tiene internet
                .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(WIFI_SSID).setWpa2Passphrase(password).build())
                .build()
            cm.requestNetwork(request, cb, CONNECT_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            fail(context, onError, "No se pudo pedir la conexión al Wi-Fi AgroScan: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Para una red a la que el teléfono ya estaba unido: avisa si se cae, para que [reconnect] pueda volver a pedirla. */
    private fun watchLoss(context: Context, joined: Network) {
        disconnect(context)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost != joined) return
                LinkLog.log("wifi", "SE PERDIÓ el Wi-Fi de la PC (al que se unió desde Ajustes)")
                this@PcLink.network = null
                state.value = State.DISCONNECTED
            }
        }
        callback = cb
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // sin esto no avisaría de un Wi-Fi sin internet
                    .build(),
                cb
            )
        }
    }

    private fun fail(context: Context, onError: (String) -> Unit, message: String) {
        LinkLog.log("wifi", "No se pudo conectar: $message")
        disconnect(context)
        onError(message)
    }

    // Android prueba la contraseña y el SSID; si el Wi-Fi no responde, a los 30 s se rinde con onUnavailable
    private const val CONNECT_TIMEOUT_MS = 30_000

    /** Una clave WPA2 son 8 a 63 caracteres ASCII imprimibles; Android lanza excepción con cualquier otra cosa. */
    fun isValidPassword(password: String): Boolean = password.length in 8..63 && password.all { it in ' '..'~' }

    /** "a.b.c.d" → entero de 32 bits; null si no son 4 números de 0 a 255. */
    fun parseIPv4(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var ip = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            ip = (ip shl 8) or n
        }
        return ip
    }

    fun sameSubnet(a: Int, b: Int, prefixLength: Int): Boolean {
        val mask = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength.coerceAtMost(32))
        return (a and mask) == (b and mask)
    }

    private fun ipv4Of(link: LinkProperties): Pair<Int, Int>? =
        link.linkAddresses.firstOrNull { it.address is Inet4Address }
            ?.let { ByteBuffer.wrap(it.address.address).int to it.prefixLength }

    // La red por defecto puede ser el celular (el Wi-Fi de la PC no tiene internet), así que se busca entre todas la que sea Wi-Fi.
    // Solo usa ACCESS_NETWORK_STATE (no lee el nombre de la red, que pediría ubicación).
    @Suppress("DEPRECATION")
    private fun pcSubnetNetwork(context: Context, pc: Int): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(network)?.let(::ipv4Of)?.let { (ip, prefix) -> sameSubnet(ip, pc, prefix) } == true
        }
    }
}
