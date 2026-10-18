package com.jesus.dronplataneras.flight

import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * El aterrizaje automático del regreso a casa es impreciso (cae donde cree que está el punto de
 * origen, sin saber si hay plantas, agua o gente debajo). Esta guardia lo interrumpe cuando el dron
 * baja de [HOLD_BELOW_M] metros: lo deja en hover y le pide al operador que decida si es seguro
 * aterrizar ([holdingForDecision] es lo que muestra la barra de decisión).
 *
 * Solo actúa sobre el aterrizaje del regreso a casa (estado LANDING del RTH), no sobre un aterrizaje
 * que el operador pide a propósito, y se aparta si la batería está tan baja que el dron debe aterrizar ya.
 */
object LandingGuard {

    /**
     * Altura (m sobre el punto de despegue) a la que se corta el descenso. La orden tarda unos
     * decímetros de descenso en surtir efecto, así que el dron queda a ~1,5–2 m.
     */
    const val HOLD_BELOW_M = 2.5

    /** Por debajo de este % de batería no se interrumpe: el dron necesita aterrizar. */
    const val MIN_BATTERY_PCT = 10

    /** true mientras el dron espera, en hover cerca del suelo, a que el operador decida. */
    val holdingForDecision = mutableStateOf(false)

    private var engaged = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * ¿Hay que cortar ahora el aterrizaje del regreso a casa? (pura, para probarla sin dron)
     *
     * @param batteryPct 0 = desconocida
     * @param alreadyHeld ya se cortó en este aterrizaje: no repetir la orden
     */
    fun shouldHold(goHomeStatus: String, flying: Boolean, altitudeM: Double, batteryPct: Int, alreadyHeld: Boolean): Boolean =
        flying && !alreadyHeld && goHomeStatus == "LANDING" &&
            altitudeM <= HOLD_BELOW_M && (batteryPct <= 0 || batteryPct > MIN_BATTERY_PCT)

    /** Empieza a vigilar (idempotente). Se llama al registrarse el SDK. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                tick()
                delay(100)
            }
        }
    }

    private fun tick() {
        val t = TelemetryManager.telemetry.value
        val flying = DJIConnectionManager.isFlying.value
        if (!flying) {
            engaged = false
            holdingForDecision.value = false
            return
        }
        if (t.goHomeStatus != "LANDING") {
            engaged = false       // el regreso terminó o se canceló: la próxima vez se vuelve a vigilar
            return
        }
        if (shouldHold(t.goHomeStatus, flying, t.altitude, t.batteryPercent, engaged)) {
            engaged = true
            FlightActions.takeManualControl { AppStatus.message.value = it }
            holdingForDecision.value = true
            AppStatus.message.value = "Dron detenido a %.0f m: decide si es seguro aterrizar".format(t.altitude)
        }
    }

    /** El operador ya decidió (aterrizó o prefiere seguir en manual): se oculta la barra. */
    fun dismiss() {
        holdingForDecision.value = false
    }
}
