package com.jesus.dronplataneras.flight

import android.util.Log
import com.jesus.dronplataneras.sdk.readable
import com.jesus.dronplataneras.sdk.runOnMain
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.action
import dji.v5.et.create

object FlightActions {

    fun takeOffToTargetHeight(onStatus: (String) -> Unit, onResult: (Boolean) -> Unit = {}) {
        Log.d("MyApp", "takeOffToTargetHeight() llamada")
        FlightControllerKey.KeyStartTakeoff.create().action(
            onSuccess = {
                Log.d("MyApp", "KeyStartTakeoff onSuccess")
                runOnMain {
                    onStatus("Despegando...")
                    onResult(true)
                }
            },
            onFailure = { error ->
                Log.e("MyApp", "KeyStartTakeoff onFailure: $error")
                runOnMain {
                    onStatus("Error al despegar: ${error.readable()}")
                    onResult(false)
                }
            }
        )
    }

    fun returnToHome(onStatus: (String) -> Unit) {
        FlightControllerKey.KeyStartGoHome.create().action(
            onSuccess = { runOnMain { onStatus("Regresando a casa") } },
            onFailure = { error -> runOnMain { onStatus("Error al iniciar RTH: ${error.readable()}") } }
        )
    }

    fun landNow(onStatus: (String) -> Unit, onResult: (Boolean) -> Unit = {}) {
        FlightControllerKey.KeyStartAutoLanding.create().action(
            onSuccess = { runOnMain { onStatus("Aterrizando..."); onResult(true) } },
            onFailure = { error -> runOnMain { onStatus("Error al aterrizar: ${error.readable()}"); onResult(false) } }
        )
    }

    fun cancelLanding(onStatus: (String) -> Unit, onResult: (Boolean) -> Unit = {}) {
        FlightControllerKey.KeyStopAutoLanding.create().action(
            onSuccess = { runOnMain { onStatus("Aterrizaje cancelado, en hover"); onResult(true) } },
            onFailure = { error -> runOnMain { onStatus("Error al cancelar aterrizaje: ${error.readable()}"); onResult(false) } }
        )
    }
}