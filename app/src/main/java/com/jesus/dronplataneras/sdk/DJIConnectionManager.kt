package com.jesus.dronplataneras.sdk

import androidx.compose.runtime.mutableStateOf
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.GoHomeNeedConfirmType
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.isKeySupported
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DeviceHealthManager
import dji.v5.manager.diagnostic.WarningLevel

object DJIConnectionManager {

    val isConnected = mutableStateOf(false)
    val isFlying = mutableStateOf(false)

    private val healthSeverityOrder = listOf(
        WarningLevel.SERIOUS_WARNING,
        WarningLevel.WARNING,
        WarningLevel.CAUTION,
        WarningLevel.NOTICE
    )

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

    fun startHealthWatcher(onWarning: (String) -> Unit) {
        DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener { infos ->
            val topWarning = infos.minByOrNull { info ->
                healthSeverityOrder.indexOf(info.warningLevel()).let { if (it == -1) Int.MAX_VALUE else it }
            }
            if (topWarning != null && healthSeverityOrder.contains(topWarning.warningLevel())) {
                onWarning("${topWarning.title()}: ${topWarning.description()}")
            }
        }
    }

    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
    }

    fun startLandingConfirmationWatcher(){
        val key = KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded)
        KeyManager.getInstance().listen(key, this) { _, needsConfirmation ->
            if (needsConfirmation == true){
                FlightControllerKey.KeyConfirmLanding.create().action(
                    onSuccess = {},
                    onFailure = {}
                )
            }
        }
    }

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

    fun startGoHomeStatusWatcher(onStatusChange: (String) -> Unit) {
        val key = KeyTools.createKey(FlightControllerKey.KeyGoHomeStatus)
        KeyManager.getInstance().listen(key, this) { _, state ->
            onStatusChange(state?.toString() ?: "UNKNOWN")
        }
    }

    fun checkGoHomeKeySupport(onResult: (String) -> Unit) {
        val support = listOf(
            "StartGoHome" to KeyTools.createKey(FlightControllerKey.KeyStartGoHome).isKeySupported(),
            "GoHomeStatus" to KeyTools.createKey(FlightControllerKey.KeyGoHomeStatus).isKeySupported(),
            "GoHomeInfo" to KeyTools.createKey(FlightControllerKey.KeyGoHomeInfo).isKeySupported(),
            "GoHomeConfirm" to KeyTools.createKey(FlightControllerKey.KeyGoHomeConfirm).isKeySupported()
        )
        onResult(support.joinToString(", ") { (name, supported) -> "$name=$supported" })
    }
}