package com.jesus.dronplataneras.data

import com.jesus.dronplataneras.mission.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VueloNumberingTest {

    private fun vuelo(siguiente: Int = 0) = VueloEntity(
        id = 1, misionId = 1, modoCaptura = CaptureMode.VIDEO,
        perimetroJson = "[]", rutaJson = "{}", totalWaypoints = 10, siguienteWaypoint = siguiente
    )

    private fun salida(indice: Int, estado: EstadoSalida, distanciaM: Double) = SalidaEntity(
        vueloId = 1, indice = indice, desdeWaypoint = 0, hastaWaypoint = 9,
        estado = estado, bateriaInicio = 90, distanciaM = distanciaM
    )

    @Test fun attemptsCancelledBeforeTakeoffDoNotCountAsSorties() {
        // tres intentos que la cámara canceló antes de despegar
        val v = VueloCompleto(vuelo(), (0..2).map { salida(it, EstadoSalida.INTERRUMPIDA, 0.0) })
        assertEquals(0, v.salidasVoladas())
        assertFalse("no se ha volado nada: no es un vuelo en curso", v.yaVoloAlgo())
    }

    @Test fun flownSortiesCountEvenIfInterrupted() {
        val v = VueloCompleto(
            vuelo(siguiente = 4),
            listOf(
                salida(0, EstadoSalida.INTERRUMPIDA, 0.0),      // cancelada en tierra
                salida(1, EstadoSalida.INTERRUMPIDA, 820.0),    // voló y se cortó por batería
                salida(2, EstadoSalida.COMPLETADA, 1500.0)
            )
        )
        assertEquals(2, v.salidasVoladas())
        assertTrue(v.yaVoloAlgo())
    }

    @Test fun progressMakesTheFlightInProgressEvenWithoutCountedSorties() {
        assertTrue(VueloCompleto(vuelo(siguiente = 6), emptyList()).yaVoloAlgo())
    }
}
