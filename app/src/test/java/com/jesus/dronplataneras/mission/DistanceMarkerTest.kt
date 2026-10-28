package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.survey.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceMarkerTest {

    // ~1,11 m por cada 0,00001° de latitud
    private fun at(metersNorth: Double) = LatLon(-2.0 + metersNorth / 111_320.0, -79.0)

    @Test fun marcaAlEmpezarYLuegoCadaSeparacion() {
        val m = DistanceMarker(4.5)
        val marks = (0..20).count { m.due(at(it.toDouble())) }   // 0..20 m, de metro en metro
        assertEquals(5, marks)                                     // a los 0, 5, 10, 15 y 20 m
    }

    @Test fun noMarcaSiNoAvanzaLoSuficiente() {
        val m = DistanceMarker(5.0)
        assertTrue(m.due(at(0.0)))
        assertFalse(m.due(at(4.0)))
        assertTrue(m.due(at(5.1)))
    }

    @Test fun resetMarcaDeNuevoAlEmpezarOtroTramo() {
        val m = DistanceMarker(5.0)
        assertTrue(m.due(at(0.0)))
        m.reset()
        assertTrue(m.due(at(0.5)))
    }
}
