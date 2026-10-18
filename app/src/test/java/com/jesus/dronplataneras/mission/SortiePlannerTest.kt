package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.planning.CoveragePlanner
import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.planning.Photogrammetry
import com.jesus.dronplataneras.planning.RouteChecks
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.XY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortiePlannerTest {

    private val origin = LatLon(-3.2600, -79.9600)
    private val f = LocalFrame(origin)
    private fun p(x: Double, y: Double) = f.toLatLon(XY(x, y))

    private val profile = DroneProfile.MINI_3
    private val speed = 5.0

    /** Lote grande (1 km x 400 m): no cabe en una batería. */
    private fun bigPlan(): WaypointPlan {
        val lot = listOf(p(0.0, 0.0), p(1000.0, 0.0), p(1000.0, 400.0), p(0.0, 400.0))
        val prev = CoveragePlanner.plan(lot, 5.0, 20.0)!!
        val est = Photogrammetry.estimate(prev.pathLengthM, prev.passes, 60.0, 75.0, mode = CaptureMode.VIDEO)
        return RouteChecks.previewToPlan("Grande", prev, 60.0, est).copy(speedMs = speed)
    }

    private fun ok(r: SplitResult) = (r as SplitResult.Ok).sorties

    @Test fun smallRouteIsOneSortie() {
        val lot = listOf(p(0.0, 0.0), p(100.0, 0.0), p(100.0, 60.0), p(0.0, 60.0))
        val prev = CoveragePlanner.plan(lot, 5.0, 20.0)!!
        val plan = RouteChecks.previewToPlan("Chico", prev, 60.0, Photogrammetry.estimate(prev.pathLengthM, prev.passes, 60.0, 75.0, mode = CaptureMode.VIDEO))
        val s = ok(SortiePlanner.split(plan, 0, home = origin, speedMs = speed))
        assertEquals(1, s.size)
        assertEquals(0, s[0].from)
        assertEquals(plan.waypoints.lastIndex, s[0].to)
        assertTrue(s[0].batteryRequiredPct <= 100)
    }

    @Test fun bigRouteSplitsIntoSeveralSortiesThatEachFit() {
        val plan = bigPlan()
        val s = ok(SortiePlanner.split(plan, 0, home = origin, speedMs = speed))
        assertTrue("debería necesitar más de una batería", s.size > 1)
        s.forEach { assertTrue("salida ${it.index} pide ${it.batteryRequiredPct}%", it.batteryRequiredPct <= 100) }
    }

    @Test fun sortiesCoverTheWholeRouteWithoutGapsOrOverlap() {
        val plan = bigPlan()
        val s = ok(SortiePlanner.split(plan, 0, home = origin, speedMs = speed))
        assertEquals(0, s.first().from)
        assertEquals(plan.waypoints.lastIndex, s.last().to)
        s.zipWithNext().forEach { (a, b) -> assertEquals(a.to + 1, b.from) }
    }

    @Test fun cutsOnlyBetweenPasses() {
        val plan = bigPlan()
        val s = ok(SortiePlanner.split(plan, 0, home = origin, speedMs = speed))
        // cada salida empieza en el inicio de una pasada (tramo que llega = giro) y acaba en el final de una
        s.forEach {
            assertTrue("empieza en un inicio de pasada", !plan.waypoints[it.from].surveyLeg)
            assertTrue("acaba en un fin de pasada", plan.waypoints[it.to].surveyLeg)
        }
    }

    @Test fun farHomeNeedsMoreSorties() {
        val plan = bigPlan()
        val near = ok(SortiePlanner.split(plan, 0, home = origin, speedMs = speed))
        val far = ok(SortiePlanner.split(plan, 0, home = p(-1500.0, -1500.0), speedMs = speed))
        assertTrue(far.size >= near.size)
    }

    @Test fun routeWithoutPassMarksCutsAnywhere() {
        // ruta importada de un LineString: todo "barrido"
        val wps = (0..80).map { i ->
            val q = p(i * 40.0, if (i % 2 == 0) 0.0 else 100.0)
            Waypoint(q.latitude, q.longitude, 60.0)
        }
        val plan = WaypointPlan("Importada", 60.0, speed, 0.0, null, wps)
        val s = ok(SortiePlanner.split(plan, 0, origin, speed))
        assertEquals(0, s.first().from)
        assertEquals(wps.lastIndex, s.last().to)
        s.zipWithNext().forEach { (a, b) -> assertEquals(a.to + 1, b.from) }
    }

    @Test fun startIndexSkipsWhatWasAlreadyFlown() {
        val plan = bigPlan()
        val all = ok(SortiePlanner.split(plan, 0, origin, speed))
        val rest = ok(SortiePlanner.split(plan, all[0].to + 1, origin, speed))
        assertEquals(all.size - 1, rest.size)
        assertEquals(all[1].from, rest[0].from)
    }

    @Test fun nothingLeftWhenStartIsPastTheEnd() {
        val plan = bigPlan()
        assertTrue(ok(SortiePlanner.split(plan, plan.waypoints.size - 1, origin, speed)).isEmpty())
    }

    @Test fun singlePassTooLongIsImpossible() {
        // una sola pasada de 12 km a 5 m/s = 40 min: no cabe en ninguna batería
        val wps = listOf(
            Waypoint(p(0.0, 0.0).latitude, p(0.0, 0.0).longitude, 60.0, surveyLeg = false),
            Waypoint(p(12000.0, 0.0).latitude, p(12000.0, 0.0).longitude, 60.0, surveyLeg = true)
        )
        val plan = WaypointPlan("Larga", 60.0, speed, 0.0, null, wps)
        assertTrue(SortiePlanner.split(plan, 0, origin, speed) is SplitResult.Impossible)
    }

    @Test fun resumeRestartsTheInterruptedPass() {
        val plan = bigPlan()
        // waypoint 5 (pasada 3: índices 4,5): se cortó volando hacia el 5 => repetir desde el 4
        assertEquals(4, SortiePlanner.resumeIndex(plan, 5))
        // se cortó volando el giro hacia el 6 (inicio de la pasada siguiente) => la anterior ya está completa
        assertEquals(6, SortiePlanner.resumeIndex(plan, 6))
        assertEquals(0, SortiePlanner.resumeIndex(plan, 0))
        assertEquals(0, SortiePlanner.resumeIndex(plan, 1))
    }

    @Test fun resumeWithoutMarksRepeatsTheLastLeg() {
        val wps = (0..5).map { Waypoint(p(it * 50.0, 0.0).latitude, p(it * 50.0, 0.0).longitude, 60.0) }
        val plan = WaypointPlan("Sin marcas", 60.0, speed, 0.0, null, wps)
        assertEquals(2, SortiePlanner.resumeIndex(plan, 3))
        assertEquals(0, SortiePlanner.resumeIndex(plan, 0))
    }

    @Test fun budgetMatchesBatteryFormula() {
        // lo que cabe en el presupuesto de la salida equivale exactamente al 100 % de batería
        val pct = Photogrammetry.batteryRequiredPct(profile.missionBudgetS, profile)
        assertEquals(100, pct)
    }
}
