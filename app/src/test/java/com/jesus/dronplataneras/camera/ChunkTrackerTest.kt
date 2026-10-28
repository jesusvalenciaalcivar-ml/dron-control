package com.jesus.dronplataneras.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkTrackerTest {

    @Test fun completoAunqueLlegueDesordenado() {
        val t = ChunkTracker()
        t.add(200, 100)
        t.add(0, 100)
        t.add(100, 100)
        assertTrue(t.isComplete(300))
    }

    @Test fun unTrozoRepetidoNoSumaDosVeces() {
        val t = ChunkTracker()
        t.add(0, 100)
        t.add(0, 100)
        assertEquals(100, t.received)
        assertFalse(t.isComplete(200))   // antes de este cuidado habría parecido completo
    }

    @Test fun faltaUnTrozoEsIncompleto() {
        val t = ChunkTracker()
        t.add(0, 100)
        t.add(200, 100)
        assertFalse(t.isComplete(300))
    }

    @Test fun unTrozoRepetidoConOtroTamanoSeReemplaza() {
        val t = ChunkTracker()
        t.add(0, 100)
        t.add(0, 60)
        assertEquals(60, t.received)
    }

    @Test fun porcentajeSobreElTamanoDelArchivo() {
        assertEquals(0, downloadPercent(0, 1000))
        assertEquals(9, downloadPercent(90, 1000))
        assertEquals(100, downloadPercent(1000, 1000))
        assertEquals(100, downloadPercent(1500, 1000))   // nunca pasa de 100
        assertEquals(0, downloadPercent(500, 0))         // tamaño desconocido
    }

    @Test fun sinTamanoConocidoBastaConHaberRecibidoAlgo() {
        val t = ChunkTracker()
        assertFalse(t.isComplete(0))
        t.add(0, 10)
        assertTrue(t.isComplete(0))
    }
}
