package com.jesus.dronplataneras.planning

import com.jesus.dronplataneras.mission.CaptureMode
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlanningTest {

    private val f = LocalFrame(LatLon(-3.2600, -79.9600))
    private fun p(x: Double, y: Double) = f.toLatLon(XY(x, y))

    // Lote alargado de 300 m (este) x 120 m (norte)
    private val rect = listOf(p(0.0, 0.0), p(300.0, 0.0), p(300.0, 120.0), p(0.0, 120.0))

    @Test fun videoFrameKeepsWidthAndCropsHeight() {
        val photo = Photogrammetry.footprint(60.0, mode = CaptureMode.PHOTO)
        val video = Photogrammetry.footprint(60.0, mode = CaptureMode.VIDEO)
        assertEquals(photo.first, video.first, 1e-6)
        assertEquals(video.first * 2160 / 3840, video.second, 1e-6)
        assertTrue(video.second < photo.second)
    }

    @Test fun canopyHeightShrinksFootprintAndSpacing() {
        val ground = Photogrammetry.lineSpacingM(60.0, 75.0, mode = CaptureMode.VIDEO)
        val canopy = Photogrammetry.lineSpacingM(60.0, 75.0, mode = CaptureMode.VIDEO, canopyHeightM = 5.0)
        assertTrue(canopy < ground)
        assertEquals(ground * 55.0 / 60.0, canopy, 1e-6)
    }

    @Test fun videoSpeedIsNotLimitedByPhotoInterval() {
        // Fotos: separación frontal chica => velocidad baja por el intervalo de 2,5 s
        val photoSpeed = Photogrammetry.missionSpeedMs(4.0, mode = CaptureMode.PHOTO)
        assertEquals(1.6, photoSpeed, 1e-6)
        // Video: la misma separación no frena el vuelo
        val videoSpeed = Photogrammetry.missionSpeedMs(4.0, mode = CaptureMode.VIDEO, gsdCmPx = 2.2)
        assertTrue(videoSpeed > photoSpeed)
        assertTrue(videoSpeed <= DroneProfile.MINI_3.maxVideoSpeedMs)
    }

    @Test fun lowAltitudeSlowsVideoByMotionBlur() {
        val high = Photogrammetry.missionSpeedMs(10.0, mode = CaptureMode.VIDEO, gsdCmPx = 3.0)
        val low = Photogrammetry.missionSpeedMs(10.0, mode = CaptureMode.VIDEO, gsdCmPx = 1.0)
        assertTrue(low < high)
        assertEquals(1.0 / 100 / DroneProfile.MINI_3.videoShutterS, low, 1e-6)
    }

    @Test fun videoEstimateReportsFramesAndStorage() {
        val e = Photogrammetry.estimate(
            pathLengthM = 3000.0, passes = 10, altitudeM = 60.0, overlapPct = 75.0,
            mode = CaptureMode.VIDEO, canopyHeightM = 4.0, frontOverlapPct = 85.0
        )
        assertEquals(CaptureMode.VIDEO, e.mode)
        assertEquals(e.photoSpacingM / e.speedMs, e.frameIntervalS, 1e-9)
        assertEquals(3000.0 / e.speedMs / 60, e.videoMinutes, 1e-9)
        assertTrue(e.videoGb > 0)
        // más traslape frontal => cuadros más juntos, el vuelo no cambia
        val e2 = e.copy()
        val low = Photogrammetry.estimate(3000.0, 10, 60.0, 75.0, mode = CaptureMode.VIDEO, canopyHeightM = 4.0, frontOverlapPct = 70.0)
        assertEquals(low.durationMin, e2.durationMin, 1e-9)
        assertTrue(e.photoSpacingM < low.photoSpacingM)
    }

    @Test fun lessOverlapMeansWiderPassesAndFewerRepeats() {
        assertEquals(4.0, Photogrammetry.timesCovered(75.0), 1e-9)
        assertEquals(2.5, Photogrammetry.timesCovered(60.0), 1e-9)
        val s70 = Photogrammetry.lineSpacingM(60.0, 70.0, mode = CaptureMode.VIDEO, canopyHeightM = 4.0)
        val s75 = Photogrammetry.lineSpacingM(60.0, 75.0, mode = CaptureMode.VIDEO, canopyHeightM = 4.0)
        assertTrue(s70 > s75)
        // un lote de 120 m de alto necesita menos pasadas con 70 % que con 75 %
        val lot = listOf(p(0.0, 0.0), p(300.0, 0.0), p(300.0, 120.0), p(0.0, 120.0))
        val passes70 = CoveragePlanner.plan(lot, 0.0, s70)!!.passes
        val passes75 = CoveragePlanner.plan(lot, 0.0, s75)!!.passes
        assertTrue(passes70 <= passes75)
    }

    @Test fun overlapPresetsAreOrderedAndMatchable() {
        val sides = OverlapPreset.entries.map { it.sidePct }
        assertEquals(sides.sorted(), sides)
        OverlapPreset.entries.forEach { assertEquals(it, OverlapPreset.match(it.sidePct, it.frontPct)) }
        assertEquals(null, OverlapPreset.match(72.0, 75.0))
    }

    @Test fun sweepFollowsNarrowestDirection() {
        val r = CoveragePlanner.plan(rect, marginM = 0.0, lineSpacingM = 20.0)!!
        // 120 m de alto / 20 m = 6 pasadas (paralelas al lado largo), no 15
        assertEquals(6, r.passes)
        assertTrue(r.sweepBearingDeg == 90.0 || r.sweepBearingDeg == 270.0)
    }

    @Test fun leadInOutExtendsPassesButStaysInsideLot() {
        val r = CoveragePlanner.plan(rect, marginM = 10.0, lineSpacingM = 20.0, leadM = 8.0)!!
        assertEquals(8.0, r.leadM, 1e-9)
        // cada pasada = 2 waypoints: el que llega al inicio es giro, el que llega al final es barrido
        assertEquals(r.passes * 2, r.path.size)
        assertEquals(r.path.size, r.surveyLeg.size)
        assertFalse(r.surveyLeg[0])
        assertTrue(r.surveyLeg[1])
        assertFalse(r.surveyLeg[2])
        // la pasada llega hasta el borde del lote (margen 10 m, entrada 8 m) sin salirse
        val xs = r.path.map { f.toXY(it).x }
        assertEquals(10.0 - 8.0, xs.min(), 0.5)
        assertEquals(290.0 + 8.0, xs.max(), 0.5)
        assertEquals(0.0, RouteChecks.maxDistanceOutsideM(rect, r.path), 1e-6)
    }

    @Test fun leadNeverExceedsMargin() {
        val r = CoveragePlanner.plan(rect, marginM = 3.0, lineSpacingM = 20.0, leadM = 8.0)!!
        assertEquals(3.0, r.leadM, 1e-9)
        val none = CoveragePlanner.plan(rect, marginM = 0.0, lineSpacingM = 20.0, leadM = 8.0)!!
        assertEquals(0.0, none.leadM, 1e-9)
    }

    @Test fun reportsConcaveLots() {
        assertTrue(CoveragePlanner.plan(rect, 0.0, 20.0)!!.convex)
        // L: cóncavo
        val l = listOf(p(0.0, 0.0), p(200.0, 0.0), p(200.0, 60.0), p(60.0, 60.0), p(60.0, 200.0), p(0.0, 200.0))
        val r = CoveragePlanner.plan(l, 0.0, 20.0)
        assertNotNull(r)
        assertFalse(r!!.convex)
    }

    @Test fun routeCheckCatchesSegmentThatCutsOutsideTheLot() {
        // L: los dos waypoints están dentro, pero el tramo recto entre ellos cruza el hueco de la L
        val l = listOf(p(0.0, 0.0), p(200.0, 0.0), p(200.0, 60.0), p(60.0, 60.0), p(60.0, 200.0), p(0.0, 200.0))
        val route = listOf(p(180.0, 40.0), p(20.0, 180.0))
        // ambos puntos dentro
        assertEquals(0.0, RouteChecks.maxDistanceOutsideM(l, listOf(route[0])), 1e-6)
        assertEquals(0.0, RouteChecks.maxDistanceOutsideM(l, listOf(route[1])), 1e-6)
        // el tramo sale del lote más que la tolerancia
        assertTrue(RouteChecks.maxDistanceOutsideM(l, route) > RouteChecks.MAX_OUTSIDE_M)
    }

    @Test fun previewPlanCarriesLegMarksAndCaptureMode() {
        val r = CoveragePlanner.plan(rect, 10.0, 20.0)!!
        val e = Photogrammetry.estimate(r.pathLengthM, r.passes, 60.0, 75.0, mode = CaptureMode.VIDEO)
        val plan = RouteChecks.previewToPlan("Prueba", r, 60.0, e)
        assertEquals(CaptureMode.VIDEO, plan.captureMode)
        assertEquals(r.surveyLeg, plan.waypoints.map { it.surveyLeg })
        val back = com.jesus.dronplataneras.mission.WaypointPlan.parse(plan.toJson())
        assertEquals(plan, back)
    }
}
