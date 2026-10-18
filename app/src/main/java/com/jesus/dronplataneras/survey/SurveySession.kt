package com.jesus.dronplataneras.survey

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.telemetry.GpsQuality
import com.jesus.dronplataneras.telemetry.TelemetryData
import java.util.Locale

/**
 * Levantamiento del contorno de un lote: el operador vuela a cada esquina y fija el vértice.
 *
 * Vive como `object` (igual que TelemetryManager) para que los vértices no se pierdan al pasar
 * entre la vista de cámara (MainScreen) y el editor de perímetro, ni al recomponer.
 * La persistencia definitiva la hace AgroRepository al confirmar el perímetro.
 */
object SurveySession {

    // Umbrales de calidad para aceptar un vértice. Ajustar tras pruebas de campo.
    const val MAX_CAPTURE_SPEED_MS = 0.5    // el dron debe estar en hover al marcar
    const val MIN_VERTEX_SPACING_M = 2.0    // evita duplicados por doble toque
    const val MIN_AREA_M2 = 100.0

    /** Vértices en el orden en que se capturaron. */
    val vertices = mutableStateListOf<SurveyVertex>()

    /** Si se está editando un lote ya guardado, su id; null = lote nuevo. */
    val editingLoteId = mutableStateOf<Long?>(null)
    val editingLoteName = mutableStateOf("")

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Intenta fijar un vértice con la posición actual del dron. Valida conexión, GPS, que esté en hover, la distancia al vértice anterior
     * y que el lado nuevo no cruce los ya trazados.
     */
    fun capture(t: TelemetryData, isConnected: Boolean): Result {
        if (!isConnected) return Result.Rejected("Dron desconectado: no se puede fijar el vértice")

        val hasPosition = !t.latitude.isNaN() && !t.longitude.isNaN() &&
            (t.latitude != 0.0 || t.longitude != 0.0)
        if (!hasPosition) return Result.Rejected("El dron aún no reporta coordenadas. Espera la fijación GPS")

        val level = parseGpsLevel(t.gpsSignalLevel)
        if (t.gpsSatelliteCount < GpsQuality.MIN_SATELLITES || level in 0 until GpsQuality.MIN_SIGNAL_LEVEL) {
            return Result.Rejected(
                "GPS insuficiente (${t.gpsSatelliteCount} satélites, nivel ${if (level < 0) "?" else level}). " +
                    "Se requieren ≥${GpsQuality.MIN_SATELLITES} satélites y nivel ≥${GpsQuality.MIN_SIGNAL_LEVEL}"
            )
        }
        if (t.speed > MAX_CAPTURE_SPEED_MS) {
            return Result.Rejected("Detén el dron sobre la esquina antes de fijar (%.1f m/s)".format(t.speed))
        }

        vertices.lastOrNull()?.let { last ->
            val d = PolygonGeometry.distanceMeters(last.latitude, last.longitude, t.latitude, t.longitude)
            if (d < MIN_VERTEX_SPACING_M) {
                return Result.Rejected("Vértice a %.1f m del anterior; muévete a la siguiente esquina".format(d))
            }
        }

        val candidate = SurveyVertex(
            latitude = t.latitude,
            longitude = t.longitude,
            altitude = t.altitude,
            satellites = t.gpsSatelliteCount,
            gpsLevel = level,
            timestampMs = System.currentTimeMillis()
        )

        // Si el nuevo lado cruza uno ya trazado, avisar en el momento y no al confirmar
        if (vertices.size >= 2 && PolygonGeometry.isSelfIntersecting(vertices + candidate, closed = false)) {
            return Result.Rejected("Ese punto cruza un lado ya trazado. Recorre las esquinas en orden (horario o antihorario)")
        }

        vertices.add(candidate)
        return Result.Ok(
            "Vértice ${vertices.size}: %.7f, %.7f".format(Locale.US, candidate.latitude, candidate.longitude)
        )
    }

    /**
     * Quita el último vértice capturado.
     */
    fun undo() {
        if (vertices.isEmpty()) return
        vertices.removeAt(vertices.lastIndex)
    }

    /** Descarta el levantamiento en curso (y sale del modo edición). */
    fun clear() {
        vertices.clear()
        editingLoteId.value = null
        editingLoteName.value = ""
    }

    /** Carga un lote guardado para editarlo. */
    fun loadForEdit(loteId: Long, name: String, points: List<GeoCoord>) {
        vertices.clear()
        points.forEach {
            vertices.add(SurveyVertex(it.latitude, it.longitude, 0.0, 0, -1, 0L))
        }
        editingLoteId.value = loteId
        editingLoteName.value = name
    }

    /** Validación completa antes de guardar; null si el polígono es válido. */
    fun validationError(): String? = when {
        vertices.size < 3 -> "Se necesitan al menos 3 vértices (hay ${vertices.size})"
        PolygonGeometry.isSelfIntersecting(vertices) -> "El polígono se cruza a sí mismo. Usa deshacer y vuelve a fijar en orden"
        PolygonGeometry.areaSquareMeters(vertices) < MIN_AREA_M2 ->
            "Área demasiado pequeña (%.0f m²)".format(PolygonGeometry.areaSquareMeters(vertices))
        else -> null
    }

    /** "LEVEL_5" -> 5, "NONE"/desconocido -> -1. */
    fun parseGpsLevel(raw: String): Int =
        raw.substringAfterLast('_', "").toIntOrNull()?.takeIf { it in 0..10 } ?: -1
}
