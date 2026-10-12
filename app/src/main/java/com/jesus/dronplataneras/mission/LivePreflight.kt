package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.jesus.dronplataneras.telemetry.TelemetryManager

/**
 * Arma el [PreflightInput] con el estado actual del dron. Lo usan la pantalla de verificación y el
 * ejecutor, así que el criterio es el mismo en los dos sitios. Dentro de un composable, leer esto
 * suscribe la pantalla a los cambios de telemetría.
 *
 * @param plan la ruta de esta salida; null si todavía no hay
 */
fun livePreflightInput(plan: WaypointPlan?, options: Options): PreflightInput {
    val t = TelemetryManager.telemetry.value
    return PreflightInput(
        hasPlan = plan != null,
        connected = DJIConnectionManager.isConnected.value,
        satellites = t.gpsSatelliteCount,
        linkQuality = t.signalQuality,
        homeSet = t.homeLocationSet,
        flying = DJIConnectionManager.isFlying.value,
        batteryPct = t.batteryPercent,
        batteryRequiredPct = options.requiredBatteryPct,
        gimbalPitchDeg = DroneInfo.gimbalPitch.value,
        routeOutsideM = options.routeOutsideM,
        maxRouteAltitudeM = plan?.waypoints?.maxOfOrNull { it.altitudeM } ?: 0.0,
        captureMode = plan?.captureMode ?: CaptureMode.VIDEO,
        videoSecondsAvailable = DroneInfo.videoSecondsAvailable.value,
        videoSecondsRequired = options.videoSecondsRequired,
        returningHome = t.goHomeStatus == "RETURNING_TO_HOME" || t.goHomeStatus == "LANDING",
        sdCardState = DroneInfo.sdCardState.value,
        photosAvailable = DroneInfo.photosAvailable.value,
        photosRequired = options.photosRequired
    )
}
