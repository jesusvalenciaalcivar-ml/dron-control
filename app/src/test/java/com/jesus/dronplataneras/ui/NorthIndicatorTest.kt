package com.jesus.dronplataneras.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NorthIndicatorTest {

    private val delta = 0.01f

    private fun check(yaw: Double, w: Float, h: Float, x: Float, y: Float) {
        val (dx, dy) = northEdgeOffset(w, h, yaw)
        assertEquals("x con yaw $yaw", x, dx, delta)
        assertEquals("y con yaw $yaw", y, dy, delta)
    }

    @Test fun dronMirandoAlNorteElNorteEstaArriba() = check(0.0, 200f, 100f, 0f, -50f)

    @Test fun dronMirandoAlEsteElNorteEstaALaIzquierda() = check(90.0, 200f, 100f, -100f, 0f)

    @Test fun dronMirandoAlSurElNorteEstaAbajo() = check(180.0, 200f, 100f, 0f, 50f)

    @Test fun dronMirandoAlOesteElNorteEstaALaDerecha() = check(270.0, 200f, 100f, 100f, 0f)

    @Test fun laMarcaSoloVaConElGimbalAlSuelo() {
        assertTrue(isNadir(-90.0))
        assertTrue(isNadir(-87.0))     // dentro de la tolerancia del chequeo pre-vuelo
        assertFalse(isNadir(-60.0))    // inclinada: el norte ya no cae en el borde
        assertFalse(isNadir(0.0))      // horizontal
        assertFalse(isNadir(Double.NaN))
    }

    @Test fun enDiagonalCaeEnElBordeMasCercano() {
        // marco ancho (200x100): a 45° la recta sale por arriba-izquierda tocando antes el borde de arriba
        check(45.0, 200f, 100f, -50f, -50f)
    }
}
