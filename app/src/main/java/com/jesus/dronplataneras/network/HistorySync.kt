package com.jesus.dronplataneras.network

import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.data.Historial
import com.jesus.dronplataneras.data.perimetro
import org.json.JSONArray
import org.json.JSONObject

/**
 * Envía a la PC todo el historial del teléfono (lotes con su perímetro, misiones con sus parámetros, vuelos y salidas) para que lo
 * guarde en su base de datos y se vea en su pantalla de Inicio. Formato en docs/PROTOCOLO_PC.md.
 *
 * - Todo cada vez y no solo lo nuevo: son pocos KB, no hay que marcar nada aquí y la PC nunca queda a medias. La PC lo guarda por
 *   UUID, así que repetir no duplica nada.
 * - Va junto con los registros de vuelo ([FlightLogSync.sendPending]): al cerrar cada salida y con "Enviar vuelos pendientes".
 */
object HistorySync {

    /** Envía el historial; si la PC no lo guarda (sin sesión, base apagada) devuelve el motivo y va completo en el próximo envío. */
    suspend fun send(repo: AgroRepository): Result<Unit> = runCatching {
        val bytes = body(repo.historial()).toByteArray(Charsets.UTF_8)
        val c = FlightLogSync.connection("/sync")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode != 200) {
                val motivo = c.errorStream?.bufferedReader()?.readText()?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                error(motivo ?: "la PC respondió ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }.onSuccess { LinkLog.log("historial", "Historial guardado en la base de la PC") }
        .onFailure { LinkLog.log("historial", "La PC no guardó el historial: ${it.message}") }

    /** JSON del historial tal como lo espera la PC. Función pura. */
    fun body(h: Historial): String {
        val misionUuid = h.misiones.associate { it.mision.id to it.mision.uuid }
        return JSONObject().apply {
            put("v", 1)
            put("lotes", JSONArray(h.lotes.map { l ->
                JSONObject().apply {
                    put("uuid", l.lote.uuid)
                    put("nombre", l.lote.nombre)
                    put("area_ha", l.lote.areaEstimadaHa)
                    put("creado_ms", l.lote.fechaCreacion)
                    put("vertices", JSONArray(l.perimetro().map { JSONArray().put(it.latitude).put(it.longitude) }))
                }
            }))
            put("misiones", JSONArray(h.misiones.map { mc ->
                val m = mc.mision
                val p = mc.parametros
                JSONObject().apply {
                    put("uuid", m.uuid)
                    put("lote", mc.lote.lote.uuid)
                    put("nombre", m.nombre)
                    put("estado", m.estado.name)
                    put("duracion_min", m.duracionEstimadaMin)
                    put("fotos", m.fotosEstimadas)
                    put("ruta", m.rutaWaypointsJson ?: JSONObject.NULL)   // el JSON de la ruta va como texto, tal cual
                    put("creado_ms", m.fechaCreacion)
                    put("actualizado_ms", m.fechaActualizacion)
                    put("parametros", JSONObject().apply {
                        put("altura_m", p.alturaMetros)
                        put("traslape_lateral_pct", p.traslapePorcentaje)
                        put("traslape_frontal_pct", p.traslapeFrontalPorcentaje)
                        put("margen_interno_m", p.margenInternoMetros)
                        put("altura_copa_m", p.alturaCopaMetros)
                        put("modo", p.modoCaptura.json)
                    })
                }
            }))
            put("vuelos", JSONArray(h.vuelos.mapNotNull { vc ->
                val v = vc.vuelo
                JSONObject().apply {
                    put("uuid", v.uuid)
                    put("mision", misionUuid[v.misionId] ?: return@mapNotNull null)
                    put("estado", v.estado.name)
                    put("inicio_ms", v.fechaInicio)
                    put("fin_ms", v.fechaFin ?: JSONObject.NULL)
                    put("modo", v.modoCaptura.json)
                    put("perimetro", v.perimetroJson)
                    put("ruta", v.rutaJson)
                    put("total_wp", v.totalWaypoints)
                    put("siguiente_wp", v.siguienteWaypoint)
                    put("salidas", JSONArray(vc.salidas.sortedBy { it.indice }.map { s ->
                        JSONObject().apply {
                            put("uuid", s.uuid)
                            put("indice", s.indice)
                            put("desde_wp", s.desdeWaypoint)
                            put("hasta_wp", s.hastaWaypoint)
                            put("estado", s.estado.name)
                            put("motivo", s.motivo ?: JSONObject.NULL)
                            put("inicio_ms", s.inicioMs)
                            put("fin_ms", s.finMs ?: JSONObject.NULL)
                            put("bateria_inicio", s.bateriaInicio)
                            put("bateria_fin", s.bateriaFin ?: JSONObject.NULL)
                            put("video_inicio_ms", s.videoInicioMs ?: JSONObject.NULL)
                            put("video_fin_ms", s.videoFinMs ?: JSONObject.NULL)
                            put("distancia_m", s.distanciaM)
                        }
                    }))
                }
            }))
        }.toString()
    }
}
