package com.jesus.dronplataneras.network

import android.content.Context
import android.net.Network
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
    private const val RECONNECT_EVERY_MS = 10_000L

    private var job: Job? = null

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
        while (isActive) {
            delay(EVERY_MS)
            val now = System.currentTimeMillis()
            if (PcLink.state.value != PcLink.State.CONNECTED) {
                // Con la misión en curso y el Wi-Fi caído se vuelve a pedir; fuera de misión no, para no molestar con el aviso de Android
                if (MissionExecutor.status.value.running && now - lastReconnectAt >= RECONNECT_EVERY_MS) {
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
                if (now - lastPlanAt >= PLAN_EVERY_MS) {
                    lastPlanAt = now
                    MissionRunner.current.value?.let { c ->
                        send(socket, address, TelemetryPacket.plan(now, seq++, planInfo(c)))
                        if (c.perimetro.isNotEmpty()) send(socket, address, TelemetryPacket.lote(now, seq++, c.perimetro.map { it.latitude to it.longitude }))
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "No se pudo enviar: ${e.message}")
                socket?.close()
                socket = null
            }
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
            bat = t.batteryPercent, sat = t.gpsSatelliteCount, enlace = t.signalQuality,
            fase = s.phase.name, volando = DJIConnectionManager.isFlying.value,
            wp = (c?.waypointOffset ?: 0) + s.waypointIndex, wpTotal = c?.totalWaypoints ?: s.waypointCount,
            avance = if (c != null && c.distanciaTotalM > 0) (doneM / c.distanciaTotalM).coerceIn(0.0, 1.0) else s.progress,
            distM = doneM, grabando = s.recording, fotos = s.photos, tVueloS = s.elapsedS,
            dron = DJIConnectionManager.isConnected.value
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
