package com.jesus.dronplataneras.sdk

import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.flight.FlightActions
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.GoHomeNeedConfirmType
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DeviceHealthManager
import dji.v5.manager.diagnostic.WarningLevel

/**
 * Estado de la conexión con el dron (conectado, volando) y vigilantes de avisos de salud, confirmaciones del dron y regreso a casa.
 * Se activa al registrarse el SDK (ver `MainActivity`).
 */
object DJIConnectionManager {

    val isConnected = mutableStateOf(false)
    val isFlying = mutableStateOf(false)

    private val healthSeverityOrder = listOf(
        WarningLevel.SERIOUS_WARNING,
        WarningLevel.WARNING,
        WarningLevel.CAUTION,
        WarningLevel.NOTICE
    )

    /**
     * Empieza a vigilar la conexión y si el dron está volando.
     */
    fun startListening() {
        val key = KeyTools.createKey(FlightControllerKey.KeyConnection)
        KeyManager.getInstance().listen(key, this) { _, newValue ->
            isConnected.value = newValue ?: false
        }

        val flyingKey = KeyTools.createKey(FlightControllerKey.KeyIsFlying)
        KeyManager.getInstance().listen(flyingKey, this) { _, newValue ->
            isFlying.value = newValue ?: false
        }
    }

    private fun topWarnings(infos: List<DJIDeviceHealthInfo>): List<DJIDeviceHealthInfo> =
        infos.filter { it.warningLevel() in healthSeverityOrder }
            .sortedBy { healthSeverityOrder.indexOf(it.warningLevel()) }

    private fun DJIDeviceHealthInfo.readable(): String =
        listOfNotNull(title(), description()).filter { it.isNotBlank() }.joinToString(": ")

    /** Todos los avisos activos del dron (cualquier nivel), del más grave al más leve. */
    fun allHealthMessages(): List<String> =
        DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos
            .sortedBy { healthSeverityOrder.indexOf(it.warningLevel()).let { i -> if (i < 0) 99 else i } }
            .map { it.readable() }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * Avisa con [onWarning] del aviso de salud más grave cada vez que cambia.
     */
    fun startHealthWatcher(onWarning: (String) -> Unit) {
        DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener { infos ->
            topWarnings(infos).firstOrNull()?.let { onWarning(it.readable()) }
        }
    }

    /**
     * Deja de escuchar todas las llaves de este gestor.
     */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
    }

    /**
     * El dron pide confirmar el aterrizaje cuando el terreno puede no ser apto (agua, plantas,
     * superficie irregular). Antes se confirmaba solo; ahora la decisión es del piloto: la UI muestra
     * un diálogo mientras esto sea true y llama a [confirmLanding] o [cancelLanding].
     */
    val landingConfirmationNeeded = mutableStateOf(false)

    /**
     * Vigila si el dron pide confirmar el aterrizaje; el resultado queda en [landingConfirmationNeeded].
     */
    fun startLandingConfirmationWatcher(){
        val key = KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded)
        KeyManager.getInstance().listen(key, this) { _, needsConfirmation ->
            landingConfirmationNeeded.value = needsConfirmation == true
        }
    }

    /**
     * Confirma el aterrizaje que el dron pidió.
     */
    fun confirmLanding(onStatus: (String) -> Unit = {}) {
        FlightControllerKey.KeyConfirmLanding.create().action(
            onSuccess = { landingConfirmationNeeded.value = false },
            onFailure = { error -> onStatus("No se pudo confirmar el aterrizaje: ${error.readable()}") }
        )
    }

    /** No aterriza: el dron queda en hover para que el piloto lo mueva a un lugar despejado. */
    fun cancelLanding(onStatus: (String) -> Unit = {}) {
        FlightActions.cancelLanding(onStatus) { ok -> if (ok) landingConfirmationNeeded.value = false }
    }

    /**
     * Confirma el regreso a casa que el propio dron propone (tipo NORMAL, p. ej. por batería baja).
     */
    fun startGoHomeConfirmationWatcher(onStatus: (String) -> Unit = {}) {
        val key = KeyTools.createKey(FlightControllerKey.KeyGoHomeInfo)
        KeyManager.getInstance().listen(key, this) { _, info ->
            if (info?.type == GoHomeNeedConfirmType.NORMAL) {
                FlightControllerKey.KeyGoHomeConfirm.create().action(
                    true,
                    onSuccess = { onStatus("RTH confirmado") },
                    onFailure = { error -> onStatus("Error al confirmar RTH: $error") }
                )
            }
        }
    }

    /**
     * Informa del estado del regreso a casa del dron (IDLE, RETURNING_TO_HOME, LANDING, COMPLETED...).
     */
    fun startGoHomeStatusWatcher(onStatusChange: (String) -> Unit) {
        val key = KeyTools.createKey(FlightControllerKey.KeyGoHomeStatus)
        KeyManager.getInstance().listen(key, this) { _, state ->
            onStatusChange(state?.toString() ?: "UNKNOWN")
        }
    }

    // Funcion para volver a casa - de manera especifica
}
