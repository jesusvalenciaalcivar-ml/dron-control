package com.jesus.dronplataneras.flight

import android.util.Log
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.sdk.detail
import com.jesus.dronplataneras.sdk.readable
import com.jesus.dronplataneras.sdk.runOnMain
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.set

/**
 * Órdenes de vuelo al dron (despegar, regresar a casa, aterrizar, quitar el mando automático).
 * Cada una avisa su resultado por callback en el hilo principal.
 */
object FlightActions {

    /**
     * Despega a la altura automática del dron (~1,2 m). Si falla, el mensaje explica el motivo con los avisos de salud del dron.
     */
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
                    // Todos los avisos del dron, de cualquier nivel: el motivo real suele estar ahí
                    val reasons = DJIConnectionManager.allHealthMessages()
                    Log.e("MyApp", "Avisos de salud al fallar el despegue: ${reasons.ifEmpty { listOf("ninguno") }}")
                    onStatus(
                        if (reasons.isNotEmpty()) "No se puede despegar: ${reasons.take(3).joinToString(" · ")}"
                        else "El dron rechazó el despegue (${error.detail()}). Prueba despegar desde DJI Fly: ahí se muestra el motivo"
                    )
                    onResult(false)
                }
            }
        )
    }

    /**
     * Altura de regreso a casa que corresponde a una ruta que vuela como máximo a [routeMaxAltitudeM]: la de la ruta más
     * [RTH_MARGIN_M] m, sin bajar de [RTH_MIN_M] m (el mínimo que acepta el dron). Sin esto el dron sube a la altura de fábrica
     * (100 m) en cada regreso.
     */
    fun returnHomeHeightFor(routeMaxAltitudeM: Double): Int =
        Math.ceil(routeMaxAltitudeM + RTH_MARGIN_M).toInt().coerceIn(RTH_MIN_M, RTH_MAX_M)

    /**
     * Fija la altura a la que el dron regresa a casa. La guarda el dron (no la app) y la usan todos los regresos: el botón RTH,
     * el final de la misión, la batería baja y las protecciones propias del dron (enlace perdido). [onResult] recibe null si
     * quedó fijada, o el motivo del fallo (en ese caso queda la altura que tuviera el dron).
     * shortcut: no se restaura al terminar; DJI Fly mostrará la última altura fijada. Restaurar si se vuela fuera de la app.
     */
    fun setReturnHomeHeight(meters: Int, onResult: (error: String?) -> Unit) {
        FlightControllerKey.KeyGoHomeHeight.create().set(
            meters,
            onSuccess = { runOnMain { onResult(null) } },
            onFailure = { error -> runOnMain { onResult(error.readable()) } }
        )
    }

    private const val RTH_MARGIN_M = 5
    private const val RTH_MIN_M = 20
    private const val RTH_MAX_M = 500

    /**
     * Inicia el regreso a casa (RTH) del propio dron.
     */
    fun returnToHome(onStatus: (String) -> Unit) {
        FlightControllerKey.KeyStartGoHome.create().action(
            onSuccess = { runOnMain { onStatus("Regresando a casa") } },
            onFailure = { error -> runOnMain { onStatus("Error al iniciar RTH: ${error.readable()}") } }
        )
    }

    /**
     * Fija el punto de origen (Home) en la posición actual del dron. Solo tiene sentido en tierra y con GPS: es el punto al que
     * volverá con RTH y donde aterrizará.
     */
    fun setHomeHere(onStatus: (String) -> Unit) {
        FlightControllerKey.KeyHomeLocationUsingCurrentAircraftLocation.create().action(
            onSuccess = { runOnMain { onStatus("Punto de origen fijado en la posición actual del dron") } },
            onFailure = { error -> runOnMain { onStatus("No se pudo fijar el punto de origen: ${error.readable()}") } }
        )
    }

    /**
     * Quita al dron el mando automático (regreso a casa y aterrizaje): queda en hover para que el
     * piloto lo guíe con el control remoto. Se piden las dos cancelaciones porque el SDK separa el
     * regreso a casa del aterrizaje; basta con que una surta efecto.
     */
    fun takeManualControl(onStatus: (String) -> Unit) {
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        fun ok() {
            if (reported.compareAndSet(false, true)) runOnMain { onStatus("Control manual: el dron queda en hover") }
        }
        fun fail(error: dji.v5.common.error.IDJIError) {
            if (failures.incrementAndGet() == 2 && !reported.get()) {
                runOnMain { onStatus("No se pudo pasar a control manual: ${error.readable()}. Usa el control remoto") }
            }
        }
        FlightControllerKey.KeyStopGoHome.create().action(onSuccess = { ok() }, onFailure = { fail(it) })
        FlightControllerKey.KeyStopAutoLanding.create().action(onSuccess = { ok() }, onFailure = { fail(it) })
    }

    /**
     * Aterriza en el sitio. Si el dron duda del terreno pedirá confirmación (ver `LandingDecisionBar`).
     */
    fun landNow(onStatus: (String) -> Unit, onResult: (Boolean) -> Unit = {}) {
        FlightControllerKey.KeyStartAutoLanding.create().action(
            onSuccess = { runOnMain { onStatus("Aterrizando..."); onResult(true) } },
            onFailure = { error -> runOnMain { onStatus("Error al aterrizar: ${error.readable()}"); onResult(false) } }
        )
    }

    /**
     * Cancela el aterrizaje en curso: el dron queda en hover.
     */
    fun cancelLanding(onStatus: (String) -> Unit, onResult: (Boolean) -> Unit = {}) {
        FlightControllerKey.KeyStopAutoLanding.create().action(
            onSuccess = { runOnMain { onStatus("Aterrizaje cancelado, en hover"); onResult(true) } },
            onFailure = { error -> runOnMain { onStatus("Error al cancelar aterrizaje: ${error.readable()}"); onResult(false) } }
        )
    }
}