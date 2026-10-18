package com.jesus.dronplataneras.survey

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Geometría plana para polígonos pequeños (lotes de cultivo, < ~5 km de lado).
 *
 * Proyecta lat/lon a un plano local en metros con [LocalFrame] (radios del elipsoide WGS84 a esa latitud),
 * centrado en el lote para minimizar la distorsión. El error es despreciable a esta escala. El planificador
 * ([com.jesus.dronplataneras.planning.CoveragePlanner]) usa el mismo tipo de marco; si algún día se pasa a JTS para
 * lotes cóncavos, conviene reproyectar a UTM (zona 17S para la costa de Ecuador).
 */
object PolygonGeometry {

    /** Proyecta los vértices a metros (x = este, y = norte) respecto al centro del lote. */
    fun toLocalMeters(points: List<GeoCoord>): List<XY> {
        if (points.isEmpty()) return emptyList()
        // Origen en el centro del lote: minimiza la distorsión de la proyección local
        val frame = LocalFrame(
            LatLon(
                (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2,
                (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2
            )
        )
        return points.map { frame.toXY(it) }
    }

    /** Distancia en metros entre dos coordenadas (válida a corta distancia). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        LatLon(lat1, lon1).distanceTo(LatLon(lat2, lon2))

    /** Área con signo en m² (shoelace): positiva si los vértices van en sentido antihorario. */
    fun signedAreaSquareMeters(points: List<GeoCoord>): Double = signedArea(toLocalMeters(points))

    /** Área con signo (shoelace) de un anillo ya proyectado a metros: positiva si va en sentido antihorario. */
    internal fun signedArea(ring: List<XY>): Double {
        if (ring.size < 3) return 0.0
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    /** true si [pt] queda dentro del anillo (regla par-impar). */
    internal fun contains(ring: List<XY>, pt: XY): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.y > pt.y) != (b.y > pt.y) && pt.x < (b.x - a.x) * (pt.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Área en m². */
    fun areaSquareMeters(points: List<GeoCoord>): Double = abs(signedAreaSquareMeters(points))

    /** GeoJSON (RFC 7946) exige que el anillo exterior vaya en sentido antihorario. */
    fun isCounterClockwise(points: List<GeoCoord>): Boolean = signedAreaSquareMeters(points) > 0

    /** Perímetro en metros. Si [closed] es true incluye el lado último→primero. */
    fun perimeterMeters(points: List<GeoCoord>, closed: Boolean = true): Double {
        val p = toLocalMeters(points)
        if (p.size < 2) return 0.0
        val edges = if (closed && p.size >= 3) p.size else p.size - 1
        return (0 until edges).sumOf { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            hypot(b.x - a.x, b.y - a.y)
        }
    }

    /**
     * true si algún par de lados no adyacentes se cruza (polígono "en ocho").
     * Un polígono así es inválido para JTS (isValid == false) y rompe el buffer y el CPP.
     *
     * @param closed true evalúa el anillo completo (incluye el lado último→primero);
     *               false evalúa solo la polilínea abierta, útil mientras se siguen marcando
     *               vértices (el lado de cierre provisional puede cruzar en lotes cóncavos).
     */
    fun isSelfIntersecting(points: List<GeoCoord>, closed: Boolean = true): Boolean {
        val p = toLocalMeters(points)
        val n = p.size
        val edges = if (closed) n else n - 1
        if (n < (if (closed) 4 else 3)) return false
        for (i in 0 until edges) {
            val a1 = p[i]
            val a2 = p[(i + 1) % n]
            for (j in i + 1 until edges) {
                // saltar lados consecutivos (comparten vértice)
                if (j == i + 1 || (closed && i == 0 && j == n - 1)) continue
                if (segmentsIntersect(a1, a2, p[j], p[(j + 1) % n])) return true
            }
        }
        return false
    }

    internal fun segmentsCross(p1: XY, p2: XY, q1: XY, q2: XY) = segmentsIntersect(p1, p2, q1, q2)

    private fun cross(o: XY, a: XY, b: XY) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun onSegment(a: XY, b: XY, p: XY) =
        minOf(a.x, b.x) <= p.x && p.x <= maxOf(a.x, b.x) &&
            minOf(a.y, b.y) <= p.y && p.y <= maxOf(a.y, b.y)

    private fun segmentsIntersect(p1: XY, p2: XY, q1: XY, q2: XY): Boolean {
        val d1 = cross(q1, q2, p1)
        val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1)
        val d4 = cross(p1, p2, q2)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) return true
        val eps = 1e-9
        return (abs(d1) < eps && onSegment(q1, q2, p1)) ||
            (abs(d2) < eps && onSegment(q1, q2, p2)) ||
            (abs(d3) < eps && onSegment(p1, p2, q1)) ||
            (abs(d4) < eps && onSegment(p1, p2, q2))
    }
}
