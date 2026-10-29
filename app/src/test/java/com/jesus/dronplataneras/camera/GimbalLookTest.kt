package com.jesus.dronplataneras.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GimbalLookTest {

    /** Gimbal de mentira: la orden de girar lo deja en ese ángulo al instante (o no lo mueve, para simular un gimbal atascado). */
    private class FakeGimbal(start: Double, private val obeys: Boolean = true) {
        @Volatile var pitch = start
        val commands = mutableListOf<Double>()
        fun rotate(to: Double) {
            synchronized(commands) { commands += to }
            if (obeys) pitch = to
        }
    }

    private fun look(g: FakeGimbal) = GimbalLook(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        pitch = { g.pitch }, rotate = g::rotate, atNadir = { !it.isNaN() && abs(it + 90) <= 5.0 },
        arriveTimeoutMs = 100, pollMs = 5
    )

    @Test fun bajaYDespuesVuelveAlAnguloAnterior() = runBlocking {
        val g = FakeGimbal(start = -30.0)
        var restored = false
        look(g).lookDownThenRestore { restored = true }!!.join()
        assertEquals(listOf(-90.0, -30.0), g.commands)
        assertEquals(-30.0, g.pitch, 0.0)
        assertTrue(restored)
    }

    @Test fun siYaMirabaAlSueloNoHaceNada() {
        val g = FakeGimbal(start = -88.0)
        assertNull(look(g).lookDownThenRestore())
        assertTrue(g.commands.isEmpty())
    }

    @Test fun vuelveEnCuantoLlegaAbajoSinEsperar() = runBlocking {
        val g = FakeGimbal(start = -30.0)
        val t0 = System.nanoTime()
        look(g).lookDownThenRestore()!!.join()
        assertEquals(listOf(-90.0, -30.0), g.commands)
        assertTrue("tardó ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 50)   // sin espera fija
    }

    @Test fun siNuncaLlegaAlSueloElOperadorLoTieneYNoSeToca() = runBlocking {
        val g = FakeGimbal(start = -30.0, obeys = false)   // el gimbal no responde: como si el operador lo sostuviera
        look(g).lookDownThenRestore()!!.join()
        assertEquals(listOf(-90.0), g.commands)
    }

    @Test fun otraMarcaMientrasTantoNoLoReinicia() = runBlocking {
        val g = FakeGimbal(start = -30.0)
        val l = look(g)
        val first = l.lookDownThenRestore()!!
        assertTrue(l.busy)
        assertNull(l.lookDownThenRestore())       // segunda marca: sigue el primero
        first.join()
        assertEquals(listOf(-90.0, -30.0), g.commands)
        assertFalse(l.busy)
    }

    @Test fun conAnguloAnteriorDesconocidoBajaPeroNoPuedeVolver() = runBlocking {
        val g = FakeGimbal(start = Double.NaN)
        var restored = false
        look(g).lookDownThenRestore { restored = true }!!.join()
        assertEquals(listOf(-90.0), g.commands)
        assertFalse(restored)
    }

    @Test fun despuesDeTerminarSePuedeVolverAUsar() = runBlocking {
        val g = FakeGimbal(start = -30.0)
        val l = look(g)
        l.lookDownThenRestore()!!.join()
        assertNotNull(l.lookDownThenRestore()?.also { it.join() })
        assertEquals(listOf(-90.0, -30.0, -90.0, -30.0), g.commands)
    }
}
