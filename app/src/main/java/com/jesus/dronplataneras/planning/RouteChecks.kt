package com.jesus.dronplataneras.planning

import com.jesus.dronplataneras.mission.Waypoint
import com.jesus.dronplataneras.mission.WaypointPlan
import com.jesus.dronplataneras.survey.GeoCoord
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.survey.XY
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Comprobaciones de una ruta contra el lote, y conversión del resultado del planificador en una ruta volable.
 */
object RouteChecks {

    /**
     * Tolerancia para aceptar una ruta: nada de la ruta (waypoints ni tramos entre ellos) puede
     * quedar a más de esta distancia fuera del lote. El Mini 3 no tiene sensores de obstáculos,
     * así que no conviene volar sobre predios vecinos.
     */
    const val MAX_OUTSIDE_M = 10.0

    /** Cada cuántos metros se revisa un tramo recto entre dos waypoints. */
    private const val SEGMENT_STEP_M = 2.0

    /**
     * Distancia máxima (m) a la que algún punto de [route] queda fuera de [polygon]; 0 si todo está
     * dentro. Revisa también el interior de cada tramo: en un lote cóncavo el vuelo recto entre
     * dos waypoints puede cortar por fuera aunque ambos waypoints estén dentro.
     */
    fun maxDistanceOutsideM(polygon: List<GeoCoord>, route: List<GeoCoord>): Double {
        if (polygon.size < 3 || route.isEmpty()) return 0.0
        val f = LocalFrame(polygon.first())
        val poly = polygon.map { f.toXY(it) }
        val pts = route.map { f.toXY(it) }

        fun outside(q: XY) = if (PolygonGeometry.contains(poly, q)) 0.0 else distanceToRing(poly, q)

        var worst = outside(pts.first())
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val steps = ceil((b - a).length() / SEGMENT_STEP_M).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                worst = maxOf(worst, outside(a + (b - a) * (s.toDouble() / steps)))
            }
        }
        return worst
    }

    /** Convierte el resultado del planificador en una ruta volable. */
    fun previewToPlan(name: String, preview: CoveragePlanner.Result, altitudeM: Double, estimate: FlightEstimate) =
        WaypointPlan(
            missionName = name,
            altitudeM = altitudeM,
            speedMs = estimate.speedMs,
            photoEveryM = estimate.photoSpacingM,
            headingDeg = preview.sweepBearingDeg,
            waypoints = preview.path.mapIndexed { i, p ->
                Waypoint(p.latitude, p.longitude, altitudeM, surveyLeg = preview.surveyLeg[i])
            },
            captureMode = estimate.mode
        )

    private fun distanceToRing(poly: List<XY>, p: XY): Double = poly.indices.minOf { i ->
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0.0, 1.0)
        hypot(p.x - (a.x + ab.x * t), p.y - (a.y + ab.y * t))
    }
}
