package com.jesus.dronplataneras.telemetry

import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.sdk.AppStatus
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import kotlin.math.sqrt

object TelemetryManager {

    val telemetry = mutableStateOf(TelemetryData())

    private const val LOW_BATTERY_THRESHOLD = 20
    private const val LOW_BATTERY_RESET_THRESHOLD = 30
    private var autoRTHTriggered = false

    fun startListening() {
        KeyManager.getInstance().listen(
            KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent), this
        ) { _, newValue ->
            val percent = newValue ?: 0
            telemetry.value = telemetry.value.copy(batteryPercent = percent)
            checkLowBattery(percent)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyAltitude), this
        ) { _, newValue ->
            telemetry.value = telemetry.value.copy(altitude = newValue ?: 0.0)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this
        ) { _, newValue ->
            val speed = newValue?.let { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) } ?: 0.0
            telemetry.value = telemetry.value.copy(speed = speed)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this
        ) { _, newValue ->
            newValue?.let {
                telemetry.value = telemetry.value.copy(
                    latitude = it.latitude,
                    longitude = it.longitude
                )
            }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount), this
        ) { _, newValue ->
            telemetry.value = telemetry.value.copy(gpsSatelliteCount = newValue ?: 0)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), this
        ) { _, newValue ->
            telemetry.value = telemetry.value.copy(gpsSignalLevel = newValue?.toString() ?: "")
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyIsHomeLocationSet), this
        ) { _, newValue ->
            telemetry.value = telemetry.value.copy(homeLocationSet = newValue ?: false)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyFlightMode), this
        ) { _, newValue ->
            telemetry.value = telemetry.value.copy(flightMode = newValue?.toString() ?: "")
        }
    }

    private fun checkLowBattery(percent: Int){
        if (percent in 1..LOW_BATTERY_THRESHOLD && !autoRTHTriggered){
            autoRTHTriggered = true
            AppStatus.message.value = "Bateria al $percent% -- regreso automatico activado"
            FlightActions.returnToHome { status ->
                AppStatus.message.value = status
            }
        } else if(percent > LOW_BATTERY_RESET_THRESHOLD){
            autoRTHTriggered = false
        }
    }

    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
    }
}