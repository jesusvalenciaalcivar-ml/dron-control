package com.jesus.dronplataneras.network

import android.util.Log
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.data.SalidaParaEnviar
import com.jesus.dronplataneras.sdk.AppStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Envía a la PC el registro de vuelo de cada salida (`salida-N.csv`) y sus eventos con las marcas del video
 * (`salida-N.events.jsonl`), para que la app de escritorio los junte con el video de la microSD. Formato en docs/PROTOCOLO_PC.md.
 *
 * - Por HTTP y no por UDP como la telemetría: cada salida se confirma, porque una marca perdida deja mal ubicados los cuadros del video.
 * - Sin marcar nada en el teléfono: primero se pregunta a la PC qué salidas ya tiene y se envían solo las que faltan. Así un corte a
 *   mitad, otra PC o una PC con la carpeta borrada se resuelven solos al volver a enviar.
 * - Automático al cerrar cada salida si el teléfono está conectado a la PC ([sendInBackground]), y con el botón "Enviar vuelos
 *   pendientes" para lo que se voló sin la PC cerca ([sendPending]).
 */
object FlightLogSync {

    const val PORT = 8766
    private const val TAG = "FlightLogSync"

    private val mutex = Mutex()   // el envío automático y el botón nunca van a la vez
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Resultado(val enviadas: Int, val sinArchivo: Int)

    /** Al cerrar una salida: si hay PC, envía lo pendiente en segundo plano. Nunca bloquea ni afecta al vuelo; si falla, queda pendiente. */
    fun sendInBackground(repo: AgroRepository) {
        if (PcLink.state.value != PcLink.State.CONNECTED) return LinkLog.log("registros", "Salida cerrada sin conexión con la PC: su registro queda pendiente")
        scope.launch {
            sendPending(repo)
                .onSuccess { if (it.enviadas > 0) AppStatus.message.value = "Registro de vuelo enviado a la PC" }
                .onFailure { Log.w(TAG, "No se pudo enviar el registro: ${it.message}") }
        }
    }

    /** Envía a la PC las salidas que todavía no tiene. Hace red y lee archivos: llamar desde un hilo de fondo. */
    suspend fun sendPending(repo: AgroRepository): Result<Resultado> = runCatching {
        mutex.withLock {
            check(PcLink.state.value == PcLink.State.CONNECTED) { "Conéctate primero a la PC" }
            var enviadas = 0
            var sinArchivo = 0
            for (s in pendientes(repo.salidasParaEnviar(), enPc())) {
                val csv = s.logPath?.let(::File)?.takeIf { it.exists() }
                if (csv == null) {   // registro borrado del teléfono: no hay nada que enviar
                    sinArchivo++
                    continue
                }
                val eventos = File(csv.parentFile, csv.nameWithoutExtension + ".events.jsonl")
                post(s, body(s, csv.readText(), if (eventos.exists()) eventos.readText() else ""))
                enviadas++
                LinkLog.log("registros", "Salida ${s.indice + 1} de \"${s.mision}\" enviada a la PC")
            }
            sendLinkLogs()
            Resultado(enviadas, sinArchivo)
        }
    }.onFailure { LinkLog.log("registros", "No se pudieron enviar los registros de vuelo: ${it.message}") }

    /** Manda a la PC los registros del enlace de los últimos días (ver [LinkLog]). Si falla no importa: van en el próximo envío. */
    private fun sendLinkLogs() {
        for (f in LinkLog.recentFiles()) runCatching {
            val bytes = f.readBytes()
            val c = connection("/logs/${f.name}")
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
                check(c.responseCode == 200) { "la PC respondió ${c.responseCode}" }
            } finally {
                c.disconnect()
            }
        }.onFailure { Log.w(TAG, "No se pudo mandar ${f.name}: ${it.message}") }
    }

    /** Las salidas que la PC todavía no tiene. */
    fun pendientes(todas: List<SalidaParaEnviar>, enPc: Set<String>): List<SalidaParaEnviar> =
        todas.filter { it.salidaUuid.lowercase() !in enPc }

    /** JSON de una salida con sus dos archivos, tal como lo espera la PC. Función pura. */
    fun body(s: SalidaParaEnviar, registroCsv: String, eventosJsonl: String): String = JSONObject().apply {
        put("v", 1)
        put("salida", JSONObject().apply {
            put("uuid", s.salidaUuid)
            put("indice", s.indice)
            put("estado", s.estado.name)
            put("inicio_ms", s.inicioMs)
            put("fin_ms", s.finMs ?: JSONObject.NULL)
            put("video_inicio_ms", s.videoInicioMs ?: JSONObject.NULL)
            put("video_fin_ms", s.videoFinMs ?: JSONObject.NULL)
        })
        put("vuelo", JSONObject().apply {
            put("uuid", s.vueloUuid)
            put("inicio_ms", s.vueloInicioMs)
            put("modo", s.modoCaptura.json)
        })
        put("mision", s.mision)
        put("lote", s.lote)
        put("registro_csv", registroCsv)
        put("eventos_jsonl", eventosJsonl)
    }.toString()

    /** Conexión HTTP a la PC (también la usa [CameraFeed], que va al mismo puerto). */
    internal fun connection(path: String, timeoutMs: Int = 30_000): HttpURLConnection {
        val host = PcLink.pcHost ?: error("Conéctate primero a la PC")
        val url = URL("http", host, PORT, path)
        // Por el Wi-Fi de la PC, que no es la red por defecto del teléfono (no tiene internet)
        val c = (PcLink.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = minOf(5_000, timeoutMs)
        c.readTimeout = timeoutMs
        return c
    }

    private fun enPc(): Set<String> {
        val c = connection("/salidas")
        try {
            check(c.responseCode == 200) { "La PC respondió ${c.responseCode}" }
            val a = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("salidas")
            return (0 until a.length()).map { a.getString(it).lowercase() }.toSet()
        } finally {
            c.disconnect()
        }
    }

    private fun post(s: SalidaParaEnviar, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val c = connection("/salidas/${s.salidaUuid}")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode != 200) {
                val motivo = c.errorStream?.bufferedReader()?.readText()?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                error("La PC no aceptó la salida ${s.indice + 1} de \"${s.mision}\": ${motivo ?: c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }
}
