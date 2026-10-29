package com.jesus.dronplataneras.telemetry

import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Telemetría del dron en un estado observable desde Compose, más el regreso automático al bajar de la batería mínima.
 */
object TelemetryManager {

    val telemetry = mutableStateOf(TelemetryData())

    private const val LOW_BATTERY_THRESHOLD = 20
    private const val LOW_BATTERY_RESET_THRESHOLD = 30
    private var autoRTHTriggered = false
    private val lock = Any()

    /**
     * Cambia un campo sin pisar los de otros listeners: el SDK avisa desde varios hilos y un
     * `value = value.copy(...)` simultáneo perdería uno de los dos cambios.
     */
    fun mutate(block: TelemetryData.() -> TelemetryData) {
        synchronized(lock) { telemetry.value = telemetry.value.block() }
    }

    /**
     * Empieza a escuchar las llaves del SDK (llamar tras registrar el SDK).
     */
    fun startListening() {
        KeyManager.getInstance().listen(
            KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent), this
        ) { _, newValue ->
            val percent = newValue ?: 0
            mutate { copy(batteryPercent = percent) }
            checkLowBattery(percent)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyAltitude), this
        ) { _, newValue ->
            mutate { copy(altitude = newValue ?: 0.0) }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this
        ) { _, newValue ->
            val speed = newValue?.let { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) } ?: 0.0
            // x = norte, y = este (como en MissionExecutor). Por debajo de 0,3 m/s la dirección es ruido del GPS
            val course = newValue?.takeIf { sqrt(it.x * it.x + it.y * it.y) >= 0.3 }?.let { Math.toDegrees(atan2(it.y, it.x)) } ?: Double.NaN
            mutate { copy(speed = speed, course = course) }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this
        ) { _, newValue ->
            newValue?.let {
                mutate { copy(latitude = it.latitude, longitude = it.longitude) }
            }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount), this
        ) { _, newValue ->
            mutate { copy(gpsSatelliteCount = newValue ?: 0) }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), this
        ) { _, newValue ->
            mutate { copy(gpsSignalLevel = newValue?.toString() ?: "") }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyIsHomeLocationSet), this
        ) { _, newValue ->
            mutate { copy(homeLocationSet = newValue ?: false) }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyFlightMode), this
        ) { _, newValue ->
            mutate { copy(flightMode = newValue?.toString() ?: "") }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(FlightControllerKey.KeyHomeLocation), this
        ) { _, newValue ->
            newValue?.let {
                mutate { copy(homeLatitude = it.latitude, homeLongitude = it.longitude) }
            }
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(AirLinkKey.KeySignalQuality), this
        ) { _, newValue ->
            mutate { copy(signalQuality = newValue ?: 0) }
        }
    }

    private fun checkLowBattery(percent: Int){
        // En tierra un RTH no tiene sentido (y se dispararía al conectar un dron con la batería baja)
        if (percent in 1..LOW_BATTERY_THRESHOLD && !autoRTHTriggered && DJIConnectionManager.isFlying.value){
            autoRTHTriggered = true
            AppStatus.message.value = "Bateria al $percent% -- regreso automatico activado"
            FlightActions.returnToHome { status ->
                AppStatus.message.value = status
            }
        } else if(percent > LOW_BATTERY_RESET_THRESHOLD){
            autoRTHTriggered = false
        }
    }

    /**
     * Deja de escuchar las llaves de este objeto.
     */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
    }
}
