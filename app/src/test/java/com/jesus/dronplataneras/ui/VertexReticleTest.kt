package com.jesus.dronplataneras.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexReticleTest {

    @Test fun conLaCamaraAlSueloLaMiraVaEnElCentro() = assertEquals(0.0, nadirDrop(-90.0)!!, 1e-9)

    @Test fun alInclinarLaCamaraElPuntoBajaEnLaImagen() {
        val a = nadirDrop(-85.0)!!
        val b = nadirDrop(-80.0)!!
        assertTrue(a > 0.0)
        assertTrue(b > a)
    }

    @Test fun enElBordeDeAbajoCuandoLaInclinacionIgualaLaMitadDelCampoDeVision() {
        // Con 40° de campo vertical, el borde de abajo está a 20° del eje
        assertEquals(1.0, nadirDrop(-70.0, verticalFovDeg = 40.0)!!, 1e-9)
    }

    @Test fun muyInclinadaElPuntoQuedaFueraDeLaImagen() {
        assertTrue(nadirDrop(-60.0)!! > 1.0)
        assertTrue(nadirDrop(0.0)!! > 1.0)     // horizontal: tangente limitada, no infinito
        assertNotNull(nadirDrop(0.0))
    }

    @Test fun sinDatoDelGimbalNoHayMira() = assertNull(nadirDrop(Double.NaN))

    @Test fun unAnguloPorDebajoDelSueloNoSeTomaComoInclinacion() = assertEquals(0.0, nadirDrop(-95.0)!!, 1e-9)
}
