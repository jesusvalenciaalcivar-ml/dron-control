package com.jesus.dronplataneras.telemetry

/**
 * Instantánea de la telemetría del dron (batería, posición, GPS, home, enlace). Es inmutable: [TelemetryManager] la reemplaza entera.
 */
data class TelemetryData(
    val batteryPercent: Int = 0,
    val altitude: Double=0.0,
    val speed: Double = 0.0,
    /** Hacia dónde se mueve el dron (grados brújula, 0 = norte); NaN si está casi quieto. No es la nariz: con rumbo fijo vuela de costado o hacia atrás. */
    val course: Double = Double.NaN,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gpsSatelliteCount: Int = 0,
    val gpsSignalLevel: String = "",
    val homeLocationSet: Boolean = false,
    val goHomeStatus: String = "IDLE",
    val flightMode: String = "",
    val signalQuality: Int = 0,
    val homeLatitude: Double = 0.0,
    val homeLongitude: Double = 0.0
)