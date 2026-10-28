package com.jesus.dronplataneras.network

import org.json.JSONArray
import org.json.JSONObject

/** Lo que va en cada paquete de telemetría hacia la PC. Un campo nulo (sin dato) no se envía. Formato en docs/PROTOCOLO_PC.md. */
data class TelemetrySample(
    val tMs: Long,
    val seq: Int,
    val lat: Double?,
    val lon: Double?,
    val altM: Double,
    val velMs: Double,
    val rumbo: Double?,
    val bat: Int,
    val sat: Int,
    val enlace: Int,
    val fase: String,
    val volando: Boolean,
    val wp: Int,
    val wpTotal: Int,
    val avance: Double,
    val distM: Double,
    val grabando: Boolean,
    val fotos: Int,
    val tVueloS: Long,
    /** El teléfono está conectado al dron. Si no, batería, altura, GPS... son valores por defecto y la PC debe mostrarlos como "sin dato". */
    val dron: Boolean = true
)

/** Datos de la misión que se mandan al preparar la salida y mientras dura: quién es, qué número de salida y con qué parámetros. */
data class PlanInfo(
    val mision: String,
    /** Número de esta salida para el operador y total estimado ("salida 2 de 3"). */
    val salida: Int,
    val salidas: Int,
    val alturaM: Double,
    /** "video" o "foto". */
    val modo: String,
    /** Punto de origen (donde regresa el dron); null si aún no está fijado. */
    val home: Pair<Double, Double>?,
    /** Waypoints de la ruta completa. */
    val route: List<Pair<Double, Double>>
)

/** Arma los paquetes JSON (un datagrama UDP cada uno). Función pura: no toca el SDK ni la red. */
object TelemetryPacket {

    private const val VERSION = 1

    /** Más puntos que esto y el paquete se acerca a lo que cabe cómodo en un datagrama: se toma un punto de cada k (siempre con el último). */
    const val MAX_ROUTE_POINTS = 150
    const val MAX_PERIMETER_POINTS = 60

    fun telemetry(s: TelemetrySample): String = JSONObject().apply {
        put("v", VERSION)
        put("tipo", "tel")
        put("seq", s.seq)
        put("t_ms", s.tMs)
        put("dron", s.dron)
        s.lat?.let { put("lat", round(it, 7)) }
        s.lon?.let { put("lon", round(it, 7)) }
        put("alt_m", round(s.altM, 1))
        put("vel_ms", round(s.velMs, 2))
        s.rumbo?.let { put("rumbo", round(it, 0)) }
        put("bat", s.bat)
        put("sat", s.sat)
        put("enlace", s.enlace)
        put("fase", s.fase)
        put("volando", s.volando)
        put("wp", s.wp)
        put("wp_total", s.wpTotal)
        put("avance", round(s.avance, 3))
        put("dist_m", round(s.distM, 1))
        put("grabando", s.grabando)
        put("fotos", s.fotos)
        put("t_vuelo_s", s.tVueloS)
    }.toString()

    /** La misión y su ruta, como lista plana `[lat1, lon1, lat2, lon2, …]`. Se repite cada pocos segundos porque UDP puede perder paquetes. */
    fun plan(tMs: Long, seq: Int, p: PlanInfo): String = JSONObject().apply {
        put("v", VERSION)
        put("tipo", "plan")
        put("seq", seq)
        put("t_ms", tMs)
        put("mision", p.mision)
        put("salida", p.salida)
        put("salidas", p.salidas)
        put("altura_m", round(p.alturaM, 1))
        put("modo", p.modo)
        p.home?.let { (lat, lon) -> put("home", flat(listOf(lat to lon), 1)) }
        put("ruta", flat(p.route, MAX_ROUTE_POINTS))
    }.toString()

    /** El contorno del lote como lista plana `[lat1, lon1, …]`, en el orden de captura (el último punto no repite el primero). */
    fun lote(tMs: Long, seq: Int, perimeter: List<Pair<Double, Double>>): String = JSONObject().apply {
        put("v", VERSION)
        put("tipo", "lote")
        put("seq", seq)
        put("t_ms", tMs)
        put("perimetro", flat(perimeter, MAX_PERIMETER_POINTS))
    }.toString()

    private fun flat(points: List<Pair<Double, Double>>, max: Int): JSONArray {
        val step = (points.size + max - 1) / max
        val kept = if (step > 1) points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex } else points
        return JSONArray().also { a -> kept.forEach { (lat, lon) -> a.put(round(lat, 6)); a.put(round(lon, 6)) } }
    }

    private fun round(x: Double, decimals: Int): Double {
        val k = Math.pow(10.0, decimals.toDouble())
        return Math.round(x * k) / k
    }
}
