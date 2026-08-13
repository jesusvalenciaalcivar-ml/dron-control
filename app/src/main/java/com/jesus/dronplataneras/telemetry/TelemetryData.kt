package com.jesus.dronplataneras.telemetry

data class TelemetryData(
    val batteryPercent: Int = 0,
    val altitude: Double=0.0,
    val speed: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gpsSatelliteCount: Int = 0,
    val gpsSignalLevel: String = "",
    val homeLocationSet: Boolean = false,
    val goHomeStatus: String = "IDLE",
    val flightMode: String = ""
)