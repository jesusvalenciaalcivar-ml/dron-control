package com.jesus.dronplataneras.mission

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * Registro de vuelo de una salida, para georreferenciar el video en el escritorio.
 *
 * Dos archivos junto a [csv]:
 *  - `<nombre>.csv`: una fila por muestra (~5 Hz) con `t_ms` (epoch UTC en milisegundos, la misma
 *    escala de tiempo que la fecha del archivo de video de la microSD), posición, altura relativa
 *    al despegue, rumbo, gimbal, velocidad, waypoint objetivo, tipo de tramo, fase y si se graba.
 *  - `<nombre>.events.jsonl`: eventos puntuales (inicio/fin de grabación, waypoint alcanzado,
 *    motivo del cierre, etc.), una línea JSON por evento.
 *
 * Es seguro llamarlo desde varios hilos. Los datos se vuelcan a disco cada ~2 s para sobrevivir
 * a un cierre inesperado de la app.
 */
class FlightLogWriter(val csv: File) : Closeable {

    val events = File(csv.parentFile, csv.nameWithoutExtension + ".events.jsonl")

    private val csvOut: BufferedWriter
    private val eventsOut: BufferedWriter
    private var lastFlushMs = 0L
    private var lastSampleMs = 0L
    private var closed = false

    init {
        csv.parentFile?.mkdirs()
        csvOut = csv.bufferedWriter()
        eventsOut = events.bufferedWriter()
        csvOut.write(HEADER)
        csvOut.newLine()
    }

    /** Escribe una muestra si pasaron al menos [minIntervalMs] desde la anterior. */
    @Synchronized
    fun sample(
        tMs: Long,
        lat: Double,
        lon: Double,
        altM: Double,
        yawDeg: Double,
        gimbalPitchDeg: Double,
        speedMs: Double,
        waypoint: Int,
        leg: String,
        phase: String,
        recording: Boolean,
        minIntervalMs: Long = 200
    ) {
        if (closed || tMs - lastSampleMs < minIntervalMs) return
        lastSampleMs = tMs
        csvOut.write(
            String.format(
                Locale.US, "%d,%.7f,%.7f,%.2f,%.1f,%.1f,%.2f,%d,%s,%s,%d",
                tMs, lat, lon, altM, yawDeg, gimbalPitchDeg, speedMs, waypoint, leg, phase, if (recording) 1 else 0
            )
        )
        csvOut.newLine()
        flushIfDue(tMs)
    }

    /**
     * Escribe un evento puntual (inicio de grabación, waypoint alcanzado, motivo del cierre...).
     */
    @Synchronized
    fun event(tMs: Long, name: String, detail: Map<String, Any?> = emptyMap()) {
        if (closed) return
        val o = JSONObject().put("t_ms", tMs).put("evento", name)
        detail.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        eventsOut.write(o.toString())
        eventsOut.newLine()
        eventsOut.flush()
    }

    private fun flushIfDue(tMs: Long) {
        if (tMs - lastFlushMs >= 2000) {
            csvOut.flush()
            lastFlushMs = tMs
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { csvOut.flush(); csvOut.close() }
        runCatching { eventsOut.flush(); eventsOut.close() }
    }

    companion object {
        const val HEADER = "t_ms,lat,lon,alt_m,yaw_deg,gimbal_pitch_deg,speed_ms,wp,tramo,fase,grabando"
    }
}
