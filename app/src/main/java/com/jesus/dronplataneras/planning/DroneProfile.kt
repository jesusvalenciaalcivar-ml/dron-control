package com.jesus.dronplataneras.planning

import com.jesus.dronplataneras.mission.CaptureMode

/**
 * Perfil del dron. Valores por defecto: DJI Mini 3 (cámara 1/1.3", 12 MP 4:3, FOV 82.1°,
 * batería estándar 38 min). Si usan la batería Plus, subir [maxFlightTimeMin] a 51.
 */
data class DroneProfile(
    val name: String = "DJI Mini 3",
    val imageWidthPx: Int = 4032,
    val imageHeightPx: Int = 3024,
    val diagonalFovDeg: Double = 82.1,
    /**
     * Video 4K 16:9. Se supone que conserva el ancho de campo de la foto 4:3 (recorta arriba y
     * abajo), así que el cuadro de video cubre el mismo ancho y menos alto. Confirmar en campo
     * midiendo el ancho real de un cuadro sobre un objeto de medida conocida.
     */
    val videoWidthPx: Int = 3840,
    val videoHeightPx: Int = 2160,
    /** Tasa de bits aproximada del video 4K (para estimar el espacio en la microSD). */
    val videoBitrateMbps: Double = 100.0,
    val maxFlightTimeMin: Double = 38.0,
    /** Fracción realista del tiempo nominal (viento, giros, ascenso). */
    val usableFlightFraction: Double = 0.75,
    val maxMissionSpeedMs: Double = 8.0,
    /** Tope de velocidad grabando video: más rápido aparece desenfoque de movimiento y rolling shutter. */
    val maxVideoSpeedMs: Double = 5.0,
    /** Obturador supuesto en video (1/240 s) para limitar el desenfoque a ~1 píxel. */
    val videoShutterS: Double = 1.0 / 240,
    /** Tiempo mínimo entre fotos (escritura en microSD del Mini 3). */
    val minPhotoIntervalS: Double = 2.5,
    val maxAltitudeM: Double = 120.0,
    val climbSpeedMs: Double = 3.0,
    /** Reserva de batería que debe quedar al terminar (para volver y aterrizar). */
    val batteryReservePct: Int = 25
) {
    /** Segundos de vuelo de misión (sin la reserva) que caben en una batería completa. */
    val missionBudgetS: Double
        get() = maxFlightTimeMin * 60 * usableFlightFraction * (100 - batteryReservePct) / 100.0

    companion object {
        val MINI_3 = DroneProfile()
    }
}

/**
 * Resultado de estimar un vuelo: huella, GSD, separaciones, velocidad, tiempo, batería y cantidad de capturas.
 */
data class FlightEstimate(
    val gsdCmPx: Double,
    val footprintWidthM: Double,
    val footprintHeightM: Double,
    val lineSpacingM: Double,
    /** Separación entre capturas (fotos, o cuadros a extraer del video) a lo largo de la pasada. */
    val photoSpacingM: Double,
    val speedMs: Double,
    /** Fotos a disparar, o cuadros a extraer del video. */
    val photos: Int,
    val durationMin: Double,
    /** % de batería necesario incluyendo la reserva. */
    val batteryRequiredPct: Int,
    val mode: CaptureMode = CaptureMode.PHOTO,
    /** Cada cuántos segundos extraer un cuadro del video para lograr [photoSpacingM] (solo VIDEO). */
    val frameIntervalS: Double = 0.0,
    /** Minutos de video grabado (solo VIDEO). */
    val videoMinutes: Double = 0.0,
    /** Espacio aproximado que ocupa el video (solo VIDEO). */
    val videoGb: Double = 0.0
)
