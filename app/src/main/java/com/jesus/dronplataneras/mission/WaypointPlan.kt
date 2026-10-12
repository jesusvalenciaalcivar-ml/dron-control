package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.survey.GeoCoord
import com.jesus.dronplataneras.survey.pathLengthM
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un punto de la ruta: posición, altura sobre el despegue y qué hacer en el tramo que llega a él.
 */
data class Waypoint(
    override val latitude: Double,
    override val longitude: Double,
    /** Altura sobre el punto de despegue (m). */
    val altitudeM: Double,
    /** Tomar foto al llegar a este waypoint. */
    val takePhoto: Boolean = false,
    /**
     * true = el tramo que llega a este waypoint es de barrido (se captura imagen).
     * false = es tránsito o giro entre pasadas. Marca dónde empieza cada pasada, que es donde se
     * puede cortar la misión en varias baterías o reanudarla. Las rutas importadas sin la marca
     * se tratan como barrido completo.
     */
    val surveyLeg: Boolean = true
) : GeoCoord

/**
 * Ruta lista para volar. Es lo que genera el planificador del móvil ([com.jesus.dronplataneras.planning.CoveragePlanner]),
 * lo que se guarda en MisionEntity.rutaWaypointsJson y lo que también se puede importar de otra herramienta.
 *
 * Versión 2 añade a cada waypoint `"barrido"` (el tramo que llega a él es de barrido; false = giro entre
 * pasadas) y a la ruta `"modo_captura"` (`"video"` | `"foto"`) y `"gimbal_pitch_deg"`. Sin esos campos se
 * asume video, gimbal a nadir y todo el recorrido como barrido.
 *
 * Formato JSON aceptado (ver [parse]):
 * ```json
 * {
 *   "formato": "agroscan-waypoints",
 *   "version": 1,
 *   "mision": "Finca Sanchez - Sector 1",
 *   "altura_m": 60,
 *   "velocidad_ms": 5.0,
 *   "foto_cada_m": 18.5,
 *   "rumbo_deg": 35.0,
 *   "waypoints": [
 *     { "lat": -3.2601, "lon": -79.9602, "alt_m": 60, "foto": false },
 *     { "lat": -3.2611, "lon": -79.9590 }
 *   ]
 * }
 * ```
 * También se acepta un GeoJSON con un LineString (coordenadas [lon, lat, alt?]),
 * que es lo más fácil de exportar desde JTS/QGIS.
 */
data class WaypointPlan(
    val missionName: String?,
    val altitudeM: Double,
    val speedMs: Double?,
    /**
     * Separación entre capturas a lo largo de la pasada. FOTO: el dron dispara cada tanto; 0 = solo
     * en waypoints con foto=true. VIDEO: el dron no dispara; es la separación con la que el
     * escritorio debe extraer los cuadros.
     */
    val photoEveryM: Double,
    /** Rumbo fijo del dron durante la misión (grados brújula); null = mantener el actual. */
    val headingDeg: Double?,
    val waypoints: List<Waypoint>,
    val captureMode: CaptureMode = CaptureMode.VIDEO,
    /** Inclinación del gimbal durante la misión (-90 = nadir). */
    val gimbalPitchDeg: Double = -90.0
) {
    /**
     * Longitud total de la ruta en metros.
     */
    fun lengthM(): Double = waypoints.pathLengthM()

    /** Metros de ruta desde el primer waypoint hasta el de índice [index]. */
    fun lengthUpToM(index: Int): Double {
        if (index <= 0) return 0.0
        val end = index.coerceAtMost(waypoints.lastIndex)
        return waypoints.subList(0, end + 1).pathLengthM()
    }

    /** Subruta con los waypoints [from]..[to] (ambos incluidos): lo que vuela una sola batería. */
    fun slice(from: Int, to: Int): WaypointPlan {
        require(from in waypoints.indices && to in from until waypoints.size) { "Tramo $from..$to fuera de la ruta" }
        return copy(waypoints = waypoints.subList(from, to + 1).toList())
    }

    /**
     * Serializa la ruta al JSON versión 2 (el que se guarda en la base y se puede exportar).
     */
    fun toJson(): String = JSONObject().apply {
        put("formato", FORMAT)
        put("version", 2)
        missionName?.let { put("mision", it) }
        put("altura_m", altitudeM)
        speedMs?.let { put("velocidad_ms", it) }
        put("foto_cada_m", photoEveryM)
        headingDeg?.let { put("rumbo_deg", it) }
        put("modo_captura", captureMode.json)
        put("gimbal_pitch_deg", gimbalPitchDeg)
        put("waypoints", JSONArray().apply {
            waypoints.forEach { w ->
                put(JSONObject().apply {
                    put("lat", w.latitude)
                    put("lon", w.longitude)
                    put("alt_m", w.altitudeM)
                    put("foto", w.takePhoto)
                    put("barrido", w.surveyLeg)
                })
            }
        })
    }.toString()

    companion object {
        const val FORMAT = "agroscan-waypoints"
        const val MAX_WAYPOINTS = 2000

        /** @throws IllegalArgumentException con un mensaje en español si el archivo no es válido. */
        fun parse(text: String, defaultAltitudeM: Double = 60.0): WaypointPlan {
            val root = try {
                JSONObject(text.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("El archivo no es JSON válido")
            }
            val plan = when (root.optString("type")) {
                "FeatureCollection", "Feature", "LineString" -> parseGeoJson(root, defaultAltitudeM)
                else -> parseNative(root, defaultAltitudeM)
            }
            validate(plan)
            return plan
        }

        private fun parseNative(root: JSONObject, defaultAltitudeM: Double): WaypointPlan {
            val arr = root.optJSONArray("waypoints")
                ?: throw IllegalArgumentException("Falta la lista \"waypoints\"")
            val altitude = root.optDouble("altura_m", defaultAltitudeM)
            val wps = (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Waypoint ${i + 1} no es un objeto")
                if (!o.has("lat") || !o.has("lon")) throw IllegalArgumentException("Waypoint ${i + 1} sin lat/lon")
                Waypoint(
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lon"),
                    altitudeM = o.optDouble("alt_m", altitude),
                    takePhoto = o.optBoolean("foto", false),
                    surveyLeg = o.optBoolean("barrido", true)
                )
            }
            return WaypointPlan(
                missionName = root.optString("mision").ifBlank { null },
                altitudeM = altitude,
                speedMs = root.optDouble("velocidad_ms").takeUnless { it.isNaN() },
                photoEveryM = root.optDouble("foto_cada_m", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                headingDeg = root.optDouble("rumbo_deg").takeUnless { it.isNaN() },
                waypoints = wps,
                captureMode = CaptureMode.fromJson(root.optString("modo_captura")),
                gimbalPitchDeg = root.optDouble("gimbal_pitch_deg", -90.0).takeUnless { it.isNaN() } ?: -90.0
            )
        }

        private fun parseGeoJson(root: JSONObject, defaultAltitudeM: Double): WaypointPlan {
            val geometry: JSONObject = when (root.optString("type")) {
                "LineString" -> root
                "Feature" -> root.optJSONObject("geometry")
                else -> {
                    val features = root.optJSONArray("features") ?: JSONArray()
                    (0 until features.length())
                        .mapNotNull { features.optJSONObject(it)?.optJSONObject("geometry") }
                        .firstOrNull { it.optString("type") == "LineString" }
                }
            } ?: throw IllegalArgumentException("El GeoJSON no contiene un LineString")
            if (geometry.optString("type") != "LineString") throw IllegalArgumentException("La geometría debe ser LineString")

            val props = if (root.optString("type") == "Feature") root.optJSONObject("properties") else null
            val altitude = props?.optDouble("altura_m", defaultAltitudeM) ?: defaultAltitudeM
            val coords = geometry.getJSONArray("coordinates")
            val wps = (0 until coords.length()).map { i ->
                val c = coords.getJSONArray(i)
                Waypoint(
                    latitude = c.getDouble(1),
                    longitude = c.getDouble(0),
                    altitudeM = if (c.length() > 2) c.getDouble(2) else altitude
                )
            }
            return WaypointPlan(
                missionName = props?.optString("name")?.ifBlank { null },
                altitudeM = altitude,
                speedMs = props?.optDouble("velocidad_ms")?.takeUnless { it.isNaN() },
                photoEveryM = props?.optDouble("foto_cada_m", 0.0)?.takeUnless { it.isNaN() } ?: 0.0,
                headingDeg = props?.optDouble("rumbo_deg")?.takeUnless { it.isNaN() },
                waypoints = wps,
                captureMode = CaptureMode.fromJson(props?.optString("modo_captura")),
                gimbalPitchDeg = props?.optDouble("gimbal_pitch_deg", -90.0)?.takeUnless { it.isNaN() } ?: -90.0
            )
        }

        private fun validate(p: WaypointPlan) {
            require(p.waypoints.size >= 2) { "La ruta necesita al menos 2 waypoints" }
            require(p.waypoints.size <= MAX_WAYPOINTS) { "Demasiados waypoints (${p.waypoints.size} > $MAX_WAYPOINTS)" }
            p.waypoints.forEachIndexed { i, w ->
                require(w.latitude in -90.0..90.0 && w.longitude in -180.0..180.0) {
                    "Waypoint ${i + 1}: coordenadas fuera de rango (¿lat/lon invertidas?)"
                }
                require(!(w.latitude == 0.0 && w.longitude == 0.0)) { "Waypoint ${i + 1} en 0,0" }
                require(w.altitudeM in 5.0..120.0) { "Waypoint ${i + 1}: altura ${w.altitudeM} m fuera de 5–120 m" }
            }
            p.speedMs?.let { require(it in 0.5..15.0) { "Velocidad $it m/s fuera de rango (0.5–15)" } }
            require(p.photoEveryM >= 0) { "foto_cada_m no puede ser negativo" }
            require(p.gimbalPitchDeg in -90.0..0.0) { "gimbal_pitch_deg ${p.gimbalPitchDeg} fuera de -90–0" }
        }
    }
}
