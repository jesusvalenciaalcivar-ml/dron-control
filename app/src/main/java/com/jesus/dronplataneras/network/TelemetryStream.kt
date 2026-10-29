package com.jesus.dronplataneras.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.jesus.dronplataneras.mission.MissionExecutor
import com.jesus.dronplataneras.mission.MissionRunner
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Manda a la PC la telemetría del dron y de la misión en tiempo real, para la pantalla de Telemetría de la app de escritorio.
 * UDP: no hay conexión que se pueda colgar. Corre en su propio hilo y solo lee estado ya existente, así que un Wi-Fi caído o
 * lento nunca afecta al vuelo; un paquete que no llega se reemplaza por el siguiente. La verdad del vuelo sigue siendo el
 * registro de vuelo en el teléfono. Formato en docs/PROTOCOLO_PC.md.
 */
object TelemetryStream {

    const val PORT = 8765
    private const val TAG = "TelemetryStream"
    private const val EVERY_MS = 500L
    private const val PLAN_EVERY_MS = 5_000L

    private var job: Job? = null

    /**
     * En qué paso de preparar la misión está el operador (`perimetro`, `planificacion`, `verificacion`), o null fuera de esos
     * pasos. Lo fija la navegación (MainActivity). La PC lo usa para avisar "Generando la ruta…" antes de que llegue el `plan`,
     * que solo se envía al iniciar la salida.
     */
    @Volatile
    var etapa: String? = null

    /** Empieza a enviar mientras [PcLink] esté conectado. Se llama una vez al abrir la app; llamarla de nuevo no hace nada. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loop(app) }
    }

    private suspend fun CoroutineScope.loop(context: Context) {
        var socket: DatagramSocket? = null
        var boundTo: Network? = null
        var seq = 0
        var lastPlanAt = 0L
        var lastReconnectAt = 0L
        var lostAt = 0L
        var wasConnected = false
        val stats = MinuteStats()
        while (isActive) {
            delay(EVERY_MS)
            val now = System.currentTimeMillis()
            val connected = PcLink.state.value == PcLink.State.CONNECTED
            if (wasConnected && !connected) lostAt = now
            wasConnected = connected
            stats.maybeLog(now, connected, context)
            if (!connected) {
                // Se vuelve a pedir el Wi-Fi caído (ver PcLink.shouldReconnect: en misión siempre, fuera de ella un rato)
                if (PcLink.shouldReconnect(now, lostAt, lastReconnectAt, MissionExecutor.status.value.running)) {
                    lastReconnectAt = now
                    PcLink.reconnect(context)
                }
                continue
            }
            val host = PcLink.pcHost ?: continue
            try {
                val network = PcLink.network
                if (socket == null || network !== boundTo) {
                    socket?.close()
                    // El Wi-Fi de la PC no es la red por defecto del teléfono: el socket se ata a ella para que salga por ahí
                    socket = DatagramSocket().also { network?.bindSocket(it) }
                    boundTo = network
                }
                val address = InetAddress.getByName(host)   // IP numérica ya validada: no hay consulta DNS
                send(socket, address, TelemetryPacket.telemetry(sample(seq++, now)))
                stats.sent++
                if (now - lastPlanAt >= PLAN_EVERY_MS) {
                    lastPlanAt = now
                    MissionRunner.current.value?.let { c ->
                        send(socket, address, TelemetryPacket.plan(now, seq++, planInfo(c)))
                        if (c.perimetro.isNotEmpty()) send(socket, address, TelemetryPacket.lote(now, seq++, c.perimetro.map { it.latitude to it.longitude }))
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "No se pudo enviar: ${e.message}")
                if (stats.errors++ == 0) LinkLog.log("telemetria", "No se pudo enviar a la PC: ${e.message}")
                socket?.close()
                socket = null
            }
        }
    }

    /** Resumen por minuto para el registro: cuánto se envió, cuántos errores, la señal del Wi-Fi y en qué estaba el dron. */
    private class MinuteStats {
        var sent = 0
        var errors = 0
        private var start = 0L

        fun maybeLog(now: Long, connected: Boolean, context: Context) {
            if (start == 0L) start = now
            if (now - start < 60_000) return
            if (sent > 0 || errors > 0 || connected) {
                val t = TelemetryManager.telemetry.value
                val s = MissionExecutor.status.value
                LinkLog.log(
                    "telemetria",
                    "Último minuto: $sent paquetes enviados, $errors errores · Wi-Fi ${if (connected) "conectado, ${wifiSignal(context)}" else "DESCONECTADO"} · " +
                        "fase ${s.phase.name}, ${if (DJIConnectionManager.isConnected.value) "dron conectado" else "dron SIN conectar"}, " +
                        "batería ${t.batteryPercent} %, altura ${"%.0f".format(t.altitude)} m"
                )
            }
            start = now
            sent = 0
            errors = 0
        }

        /** Intensidad de la señal del Wi-Fi de la PC (dBm; cuanto más cerca de 0, mejor: -50 muy buena, -80 mala). */
        private fun wifiSignal(context: Context): String {
            val network = PcLink.network ?: return "señal desconocida"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "señal desconocida"
            val dbm = context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)?.signalStrength
            return if (dbm == null || dbm == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) "señal desconocida" else "señal $dbm dBm"
        }
    }

    private fun send(socket: DatagramSocket, address: InetAddress, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, address, PORT))
    }

    private fun sample(seq: Int, tMs: Long): TelemetrySample {
        val t = TelemetryManager.telemetry.value
        val s = MissionExecutor.status.value
        val c = MissionRunner.current.value
        val hasFix = !t.latitude.isNaN() && !t.longitude.isNaN() && (t.latitude != 0.0 || t.longitude != 0.0)
        val doneM = (c?.distanciaPreviaM ?: 0.0) + s.distanceDoneM
        return TelemetrySample(
            tMs = tMs, seq = seq,
            lat = t.latitude.takeIf { hasFix }, lon = t.longitude.takeIf { hasFix },
            altM = t.altitude, velMs = t.speed, rumbo = DroneInfo.heading.value.takeUnless { it.isNaN() },
            curso = t.course.takeUnless { it.isNaN() },
            bat = t.batteryPercent, sat = t.gpsSatelliteCount, enlace = t.signalQuality,
            fase = s.phase.name, volando = DJIConnectionManager.isFlying.value,
            wp = (c?.waypointOffset ?: 0) + s.waypointIndex, wpTotal = c?.totalWaypoints ?: s.waypointCount,
            avance = if (c != null && c.distanciaTotalM > 0) (doneM / c.distanciaTotalM).coerceIn(0.0, 1.0) else s.progress,
            distM = doneM, grabando = s.recording, fotos = s.photos, tVueloS = s.elapsedS,
            // Lo mismo que muestra la pantalla de misión del teléfono en modo video
            tRecS = s.recordingS,
            cuadros = if (c != null && c.plan.photoEveryM > 0) (doneM / c.plan.photoEveryM).toInt() else 0,
            dron = DJIConnectionManager.isConnected.value,
            etapa = etapa
        )
    }

    /** La misión de la salida preparada o en curso: quién es, qué salida es, con qué altura y modo, el origen y la ruta completa. */
    private fun planInfo(c: MissionRunner.RunContext): PlanInfo {
        val t = TelemetryManager.telemetry.value
        val homeSet = t.homeLocationSet && !t.homeLatitude.isNaN() && !t.homeLongitude.isNaN() && (t.homeLatitude != 0.0 || t.homeLongitude != 0.0)
        return PlanInfo(
            mision = c.misionNombre, salida = c.salidaNumero, salidas = c.salidasTotal,
            alturaM = c.plan.altitudeM, modo = c.plan.captureMode.json,
            home = if (homeSet) t.homeLatitude to t.homeLongitude else null,
            route = c.plan.waypoints.map { it.latitude to it.longitude }
        )
    }
}
