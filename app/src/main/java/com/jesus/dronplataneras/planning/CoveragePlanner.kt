package com.jesus.dronplataneras.planning

import com.jesus.dronplataneras.survey.GeoCoord
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.survey.XY
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Planificador de barrido en zigzag (boustrophedon) que corre en el móvil, sin internet.
 *
 * Es el generador de rutas de la app: produce los waypoints que vuela [com.jesus.dronplataneras.mission.MissionExecutor].
 * Cada pasada se extiende unos metros más allá del borde de barrido (entrada y salida de línea)
 * para que el dron ya vaya a velocidad de crucero cuando empieza a capturar imagen útil.
 *
 * Limitación conocida: en lotes cóncavos el margen interno es aproximado y una pasada puede
 * cortar el polígono en varios tramos que se unen con un vuelo recto por fuera del lote
 * ([Result.convex] = false avisa; [RouteChecks] bloquea el vuelo si la ruta se sale).
 */
object CoveragePlanner {

    /** Entrada/salida de línea por defecto, limitada además por el margen interno. */
    const val DEFAULT_LEAD_M = 8.0

    data class Result(
        /** Polígono reducido por el margen interno (Inward Buffer). */
        val inner: List<LatLon>,
        /** Waypoints del zigzag en orden de vuelo (inicio y fin de cada pasada, con entrada/salida de línea). */
        val path: List<LatLon>,
        /** Para cada waypoint de [path]: true si el tramo que llega a él es de barrido; false si es giro/tránsito. */
        val surveyLeg: List<Boolean>,
        val pathLengthM: Double,
        val passes: Int,
        /** Rumbo de las pasadas en grados de brújula (0 = norte, 90 = este). */
        val sweepBearingDeg: Double,
        /** false = el lote (con margen) es cóncavo: la ruta puede salirse y hay que revisarla. */
        val convex: Boolean = true,
        /** Entrada/salida de línea realmente aplicada (m). */
        val leadM: Double = 0.0
    )

    /**
     * @param marginM margen interno (buffer negativo) en metros
     * @param lineSpacingM separación entre pasadas en metros (de traslape lateral y altura)
     * @param leadM entrada/salida de línea deseada; nunca supera [marginM] para no salir del lote
     * @return null si el polígono no es válido o el margen se come todo el lote
     */
    fun plan(vertices: List<GeoCoord>, marginM: Double, lineSpacingM: Double, leadM: Double = DEFAULT_LEAD_M): Result? {
        if (vertices.size < 3 || lineSpacingM <= 0.5) return null
        if (PolygonGeometry.isSelfIntersecting(vertices)) return null

        val frame = LocalFrame(vertices.first())
        var ring = vertices.map { frame.toXY(it) }
        if (PolygonGeometry.signedArea(ring) < 0) ring = ring.reversed()

        val inner = if (marginM > 0.01) insetPolygon(ring, marginM) ?: return null else ring

        // Pasadas en la dirección que deja el polígono más angosto: menos pasadas = menos giros = menos batería
        val angle = narrowestSweepAngle(inner)
        val rotated = inner.map { rotate(it, -angle) }

        val minY = rotated.minOf { it.y }
        val maxY = rotated.maxOf { it.y }
        val height = maxY - minY

        val lead = if (marginM > 0.01) minOf(leadM, marginM).coerceAtLeast(0.0) else 0.0

        // Centrar las pasadas dentro del alto disponible
        // 1 cm de tolerancia: el ruido de la proyección no debe sumar una pasada de más
        val count = maxOf(1, kotlin.math.ceil((height - 0.01) / lineSpacingM).toInt())
        val step = if (count > 1) height / count else 0.0
        val path = mutableListOf<XY>()
        val survey = mutableListOf<Boolean>()
        var passes = 0
        for (k in 0 until count) {
            val y = if (count == 1) minY + height / 2 else minY + step * (k + 0.5)
            val segments = scanline(rotated, y)
            if (segments.isEmpty()) continue
            val ordered = if (passes % 2 == 0) segments else segments.reversed().map { it.second to it.first }
            ordered.forEach { (x1, x2) ->
                val dir = if (x2 >= x1) 1.0 else -1.0
                // el tramo que llega al inicio de la pasada es giro/tránsito; el que llega al final es barrido
                path += XY(x1 - dir * lead, y); survey += false
                path += XY(x2 + dir * lead, y); survey += true
            }
            passes++
        }
        if (path.isEmpty()) return null

        val back = path.map { rotate(it, angle) }
        val length = back.zipWithNext().sumOf { (a, b) -> (b - a).length() }
        return Result(
            inner = inner.map { frame.toLatLon(it) },
            path = back.map { frame.toLatLon(it) },
            surveyLeg = survey,
            pathLengthM = length,
            passes = passes,
            sweepBearingDeg = ((90.0 - Math.toDegrees(angle)) % 360 + 360) % 360,
            convex = isConvex(inner),
            leadM = lead
        )
    }

    /** true si el polígono es convexo (todos los giros van al mismo lado). */
    fun isConvex(p: List<XY>): Boolean {
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-9) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Buffer interno por desplazamiento de lados. Exacto para polígonos convexos, aproximado en cóncavos. */
    fun insetPolygon(ccwRing: List<XY>, d: Double): List<XY>? {
        val n = ccwRing.size
        // Recta desplazada de cada lado: punto + dirección
        val lines = (0 until n).map { i ->
            val a = ccwRing[i]
            val b = ccwRing[(i + 1) % n]
            val dir = b - a
            val len = dir.length()
            if (len < 1e-6) return null
            val normalIn = XY(-dir.y / len, dir.x / len) // izquierda = interior en anillo antihorario
            (a + normalIn * d) to dir
        }
        val out = (0 until n).map { i ->
            val (p1, d1) = lines[(i - 1 + n) % n]
            val (p2, d2) = lines[i]
            intersect(p1, d1, p2, d2) ?: p2
        }
        // El margen no puede invertir el polígono ni hacerlo cruzarse
        val area = PolygonGeometry.signedArea(out)
        if (area <= 1.0) return null
        for (i in 0 until n) {
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue
                if (PolygonGeometry.segmentsCross(out[i], out[(i + 1) % n], out[j], out[(j + 1) % n])) return null
            }
        }
        // Vértices que se salieron del original (margen > ancho local) => no válido
        if (out.any { !PolygonGeometry.contains(ccwRing, it) }) return null
        return out
    }

    // --- utilidades planas ---

    private fun narrowestSweepAngle(p: List<XY>): Double {
        var bestAngle = 0.0
        var bestHeight = Double.MAX_VALUE
        var bestLen = 0.0
        for (i in p.indices) {
            val d = p[(i + 1) % p.size] - p[i]
            val len = d.length()
            if (len < 1e-6) continue
            val a = atan2(d.y, d.x)
            val ys = p.map { rotate(it, -a).y }
            val h = ys.max() - ys.min()
            if (h < bestHeight - 1e-6 || (abs(h - bestHeight) <= 1e-6 && len > bestLen + 1e-6)) {
                bestHeight = h
                bestAngle = a
                bestLen = len
            }
        }
        return bestAngle
    }

    private fun rotate(p: XY, a: Double) = XY(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a))

    private fun intersect(p1: XY, d1: XY, p2: XY, d2: XY): XY? {
        val den = d1.x * d2.y - d1.y * d2.x
        if (abs(den) < 1e-9) return null
        val t = ((p2.x - p1.x) * d2.y - (p2.y - p1.y) * d2.x) / den
        return p1 + d1 * t
    }

    /** Tramos [x1, x2] donde la recta horizontal y corta el interior del polígono. */
    private fun scanline(p: List<XY>, y: Double): List<Pair<Double, Double>> {
        val xs = mutableListOf<Double>()
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) {
                xs += a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
            }
        }
        xs.sort()
        return xs.chunked(2).filter { it.size == 2 && it[1] - it[0] > 0.1 }.map { it[0] to it[1] }
    }
}
