package com.jesus.dronplataneras.mission

/** Por qué terminó una salida. */
enum class EndReason {
    COMPLETED,
    PILOT_STOP,
    RTH_REQUESTED,
    LOW_BATTERY,
    LINK_LOST,
    AUTHORITY_LOST,
    NO_GPS,
    STALE_POSITION,
    TAKEOFF_FAILED,
    VIRTUAL_STICK_FAILED,
    RECORDING_FAILED,
    GIMBAL_FAILED,
    CAMERA_FAILED,
    WRONG_DIRECTION,
    ERROR
}

/**
 * Resultado de una salida.
 *
 * @property waypointIndex waypoint (de la subruta que se voló) hacia el que iba al terminar
 */
data class Outcome(
    val reason: EndReason,
    val waypointIndex: Int,
    val message: String,
    val distanceM: Double,
    val videoStartMs: Long?,
    val videoEndMs: Long?
) {
    val completed get() = reason == EndReason.COMPLETED
}

/** Lo que el ejecutor necesita saber de la misión además de la ruta. */
data class Options(
    /** % de batería que pide esta salida (se vuelve a validar al iniciar). */
    val requiredBatteryPct: Int = 0,
    /** Distancia a la que la ruta sale del lote, calculada por quien conoce el perímetro. */
    val routeOutsideM: Double = 0.0,
    /** Segundos de video que se grabarán, para comprobar el espacio de la microSD. */
    val videoSecondsRequired: Double = 0.0,
    /** Fotos que se tomarán (modo FOTO), para comprobar el espacio de la microSD. */
    val photosRequired: Double = 0.0,
    val log: FlightLogWriter? = null
)
