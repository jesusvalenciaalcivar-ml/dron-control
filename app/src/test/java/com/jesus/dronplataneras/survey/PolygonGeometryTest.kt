package com.jesus.dronplataneras.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonGeometryTest {

    // ~1e-4° de latitud ≈ 11.1 m. Lotes cerca de Machala (lat ≈ -3.26)
    private fun v(lat: Double, lon: Double) = SurveyVertex(lat, lon, 30.0, 18, 5, 0L)

    private val lat0 = -3.2600
    private val lon0 = -79.9600
    // Desplazamientos exactos de 100 m usando el elipsoide (misma proyección que la app)
    private val ref = LocalFrame(LatLon(lat0, lon0)).toLatLon(XY(100.0, 100.0))
    private val dLat = ref.latitude - lat0                                    // 100 m al norte
    private val dLon = ref.longitude - lon0                                   // 100 m al este

    private val square = listOf(
        v(lat0, lon0), v(lat0, lon0 + dLon), v(lat0 + dLat, lon0 + dLon), v(lat0 + dLat, lon0)
    )

    @Test fun areaOfHectareSquare() {
        assertEquals(10_000.0, PolygonGeometry.areaSquareMeters(square), 10.0)
        assertEquals(400.0, PolygonGeometry.perimeterMeters(square), 0.5)
    }

    @Test fun orientation() {
        assertTrue(PolygonGeometry.isCounterClockwise(square))
        assertFalse(PolygonGeometry.isCounterClockwise(square.reversed()))
    }

    @Test fun simpleSquareIsValid() {
        assertFalse(PolygonGeometry.isSelfIntersecting(square))
        assertFalse(PolygonGeometry.isSelfIntersecting(square.reversed()))
    }

    @Test fun bowTieIsDetected() {
        val bowTie = listOf(square[0], square[2], square[1], square[3])
        assertTrue(PolygonGeometry.isSelfIntersecting(bowTie))
    }

    @Test fun concaveLotIsValid() {
        // Lote en forma de L
        val l = listOf(
            v(lat0, lon0), v(lat0, lon0 + 2 * dLon), v(lat0 + dLat, lon0 + 2 * dLon),
            v(lat0 + dLat, lon0 + dLon), v(lat0 + 2 * dLat, lon0 + dLon), v(lat0 + 2 * dLat, lon0)
        )
        assertFalse(PolygonGeometry.isSelfIntersecting(l))
        assertEquals(30_000.0, PolygonGeometry.areaSquareMeters(l), 30.0)
    }

    @Test fun openPolylineIgnoresProvisionalClosingEdge() {
        // Espiral: abierta es válida aunque el cierre provisional cruzaría
        fun p(x: Int, y: Int) = v(lat0 + y * dLat, lon0 + x * dLon)
        val spiral = listOf(p(0, 0), p(4, 0), p(4, 4), p(2, 4), p(2, 1), p(3, 1), p(3, 2))
        assertFalse(PolygonGeometry.isSelfIntersecting(spiral, closed = false))
        assertTrue(PolygonGeometry.isSelfIntersecting(spiral, closed = true))
    }

    @Test fun distance() {
        assertEquals(100.0, PolygonGeometry.distanceMeters(lat0, lon0, lat0 + dLat, lon0), 0.2)
    }

    @Test fun areaMatchesWgs84Geodesic() {
        // Referencia calculada con GeographicLib (área geodésica exacta en el elipsoide WGS84)
        val lote = listOf(
            LatLon(-3.26, -79.96), LatLon(-3.262, -79.955), LatLon(-3.266, -79.961), LatLon(-3.263, -79.965)
        )
        assertEquals(362_544.89, PolygonGeometry.areaSquareMeters(lote), 362_544.89 * 0.001)
        val chico = listOf(LatLon(-0.2531, -79.1712), LatLon(-0.2531, -79.1700), LatLon(-0.2542, -79.1695))
        assertEquals(8_123.91, PolygonGeometry.areaSquareMeters(chico), 8_123.91 * 0.001)
    }
}
