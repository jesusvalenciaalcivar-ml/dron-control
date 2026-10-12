package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.planning.Photogrammetry
import com.jesus.dronplataneras.survey.GeoCoord
import com.jesus.dronplataneras.survey.distanceTo

/**
 * Una salida = un despegue con una batería: vuela los waypoints [from]..[to] (índices de la ruta
 * completa, ambos incluidos) y vuelve a casa.
 */
data class Sortie(
    val index: Int,
    val from: Int,
    val to: Int,
    val lengthM: Double,
    val flightMinutes: Double,
    /** % de batería necesario al despegar, con la reserva incluida. */
    val batteryRequiredPct: Int
)

/**
 * Resultado de partir la ruta en salidas: la lista, o por qué no se puede volar.
 */
sealed interface SplitResult {
    data class Ok(val sorties: List<Sortie>) : SplitResult
    data class Impossible(val reason: String) : SplitResult
}

/**
 * Parte una ruta en salidas que caben cada una en una batería, cortando siempre entre pasadas
 * (en un waypoint que no es de barrido), y calcula desde dónde reanudar tras una interrupción.
 */
object SortiePlanner {

    /** Tránsito supuesto casa→lote cuando todavía no se conoce el punto de origen (m, ida o vuelta). */
    const val DEFAULT_TRANSIT_M = 150.0

    /**
     * @param startIndex primer waypoint por volar (0 = misión nueva; otro valor = reanudación)
     * @param home punto de origen del dron; null = se supone [DEFAULT_TRANSIT_M]
     * @param speedMs velocidad de las pasadas
     */
    fun split(
        plan: WaypointPlan,
        startIndex: Int,
        home: GeoCoord?,
        speedMs: Double,
        profile: DroneProfile = DroneProfile.MINI_3
    ): SplitResult {
        val wps = plan.waypoints
        val n = wps.size
        if (startIndex !in 0 until n - 1) return SplitResult.Ok(emptyList())
        require(speedMs > 0) { "La velocidad debe ser positiva" }

        // Longitud de cada tramo y su tiempo (los giros entre pasadas cuestan más)
        val legM = DoubleArray(n)
        val legS = DoubleArray(n)
        for (i in 1 until n) {
            legM[i] = wps[i - 1].distanceTo(wps[i])
            legS[i] = legM[i] / speedMs + if (!wps[i].surveyLeg) Photogrammetry.TURN_SECONDS else 0.0
        }
        val hasPassMarks = (startIndex + 1 until n).any { !wps[it].surveyLeg }
        // Una salida puede terminar en `to` si la siguiente pasada empieza en to+1
        fun canEndAt(to: Int) = to == n - 1 || !hasPassMarks || !wps[to + 1].surveyLeg

        fun transitS(i: Int): Double {
            val d = home?.distanceTo(wps[i]) ?: DEFAULT_TRANSIT_M
            return d / profile.maxMissionSpeedMs
        }

        val fixedS = 2 * plan.altitudeM / profile.climbSpeedMs
        val budget = profile.missionBudgetS
        val sorties = mutableListOf<Sortie>()
        var from = startIndex
        while (from < n - 1) {
            var cumS = 0.0
            var cumM = 0.0
            var bestTo = -1
            var bestS = 0.0
            var bestM = 0.0
            for (to in from + 1 until n) {
                cumS += legS[to]
                cumM += legM[to]
                val total = fixedS + transitS(from) + cumS + transitS(to)
                if (fixedS + transitS(from) + cumS > budget) break
                if (total <= budget && canEndAt(to)) {
                    bestTo = to
                    bestS = total
                    bestM = cumM
                }
            }
            if (bestTo < 0) {
                return SplitResult.Impossible(
                    "Una sola pasada no cabe en una batería (desde el waypoint ${from + 1}). " +
                        "Reduce la altura de captura, acorta el lote o acerca el punto de despegue"
                )
            }
            sorties += Sortie(
                index = sorties.size,
                from = from,
                to = bestTo,
                lengthM = bestM,
                flightMinutes = bestS / 60,
                batteryRequiredPct = Photogrammetry.batteryRequiredPct(bestS, profile)
            )
            from = bestTo + 1
        }
        return SplitResult.Ok(sorties)
    }

    /**
     * Waypoint por el que reanudar tras una salida interrumpida mientras volaba hacia
     * [interruptedAt] (el waypoint objetivo cuando se cortó, índice de la ruta completa).
     * Se repite la pasada interrumpida desde su inicio para no dejar un hueco sin imagen.
     */
    fun resumeIndex(plan: WaypointPlan, interruptedAt: Int): Int {
        val wps = plan.waypoints
        val i = interruptedAt.coerceIn(0, wps.size - 1)
        if (wps.none { !it.surveyLeg }) return (i - 1).coerceAtLeast(0)
        var j = i
        while (j > 0 && wps[j].surveyLeg) j--
        return j
    }
}
