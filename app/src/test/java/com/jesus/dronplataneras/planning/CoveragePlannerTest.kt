package com.jesus.dronplataneras.planning

import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.survey.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePlannerTest {

    private val origin = LatLon(-3.2600, -79.9600)
    private val f = LocalFrame(origin)
    private fun p(x: Double, y: Double) = f.toLatLon(XY(x, y))

    // Rectángulo 200 m (este) x 100 m (norte)
    private val rect = listOf(p(0.0, 0.0), p(200.0, 0.0), p(200.0, 100.0), p(0.0, 100.0))

    @Test fun insetShrinksRectangleByMargin() {
        val r = CoveragePlanner.plan(rect, marginM = 10.0, lineSpacingM = 20.0)
        assertNotNull(r)
        // 180 x 80 = 14 400 m²
        assertEquals(14_400.0, PolygonGeometry.areaSquareMeters(r!!.inner), 5.0)
    }

    @Test fun passesFollowLongestEdgeAndCoverHeight() {
        val r = CoveragePlanner.plan(rect, marginM = 0.0, lineSpacingM = 20.0)!!
        assertEquals(5, r.passes)                 // 100 m / 20 m
        assertEquals(10, r.path.size)             // 2 puntos por pasada
        // Pasadas este-oeste => rumbo 90° o 270°
        assertTrue(r.sweepBearingDeg == 90.0 || r.sweepBearingDeg == 270.0)
        // 5 pasadas de 200 m + 4 cambios de 20 m
        assertEquals(1080.0, r.pathLengthM, 1.0)
    }

    @Test fun zigzagAlternatesDirection() {
        val r = CoveragePlanner.plan(rect, 0.0, 20.0)!!
        val xy = r.path.map { f.toXY(it) }
        assertTrue(xy[1].x > xy[0].x)   // primera pasada hacia el este
        assertTrue(xy[3].x < xy[2].x)   // segunda hacia el oeste
    }

    @Test fun marginLargerThanLotFails() {
        assertNull(CoveragePlanner.plan(rect, marginM = 60.0, lineSpacingM = 20.0))
    }

    @Test fun rotatedPolygon() {
        // Rombo inclinado 45°
        val d = listOf(p(0.0, 0.0), p(100.0, 100.0), p(80.0, 120.0), p(-20.0, 20.0))
        val r = CoveragePlanner.plan(d, 2.0, 5.0)
        assertNotNull(r)
        assertEquals(45.0, r!!.sweepBearingDeg % 180, 0.5)
    }

    @Test fun photogrammetryMini3() {
        val (w, h) = Photogrammetry.footprint(100.0)
        assertEquals(139.3, w, 0.5)
        assertEquals(104.5, h, 0.5)
        assertEquals(3.45, Photogrammetry.gsdCmPx(100.0), 0.05)
        val e = Photogrammetry.estimate(pathLengthM = 3000.0, passes = 10, altitudeM = 60.0, overlapPct = 75.0)
        assertTrue(e.speedMs <= 8.0)
        assertTrue(e.photos > 3000 / e.photoSpacingM)
        assertTrue(e.batteryRequiredPct > 25)
    }

    @Test fun routeChecksOutsideDistance() {
        val inside = listOf(p(10.0, 10.0), p(190.0, 90.0))
        assertEquals(0.0, RouteChecks.maxDistanceOutsideM(rect, inside), 1e-6)
        val outside = inside + p(100.0, 130.0)   // 30 m al norte del lado superior
        assertEquals(30.0, RouteChecks.maxDistanceOutsideM(rect, outside), 0.1)
    }

    @Test fun previewPlanIsFlyable() {
        val r = CoveragePlanner.plan(rect, 5.0, 20.0)!!
        val e = Photogrammetry.estimate(r.pathLengthM, r.passes, 60.0, 75.0)
        val plan = RouteChecks.previewToPlan("Prueba", r, 60.0, e)
        // ida y vuelta por JSON
        val back = com.jesus.dronplataneras.mission.WaypointPlan.parse(plan.toJson())
        assertEquals(r.path.size, back.waypoints.size)
        assertEquals(r.pathLengthM, back.lengthM(), 0.5)
    }
}
