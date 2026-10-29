package com.jesus.dronplataneras.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcLinkTest {

    @Test
    fun reconexionEnMisionSiempreYFueraSoloUnRato() {
        // En misión: cada 10 s, aunque haga mucho que se perdió
        assertTrue(PcLink.shouldReconnect(now = 100_000, lostAt = 1_000, lastAttemptAt = 89_000, inMission = true))
        assertFalse(PcLink.shouldReconnect(now = 100_000, lostAt = 1_000, lastAttemptAt = 95_000, inMission = true))
        // Fuera de misión: cada 30 s y solo los primeros 5 min
        assertTrue(PcLink.shouldReconnect(now = 60_000, lostAt = 10_000, lastAttemptAt = 20_000, inMission = false))
        assertFalse(PcLink.shouldReconnect(now = 60_000, lostAt = 10_000, lastAttemptAt = 40_000, inMission = false))
        assertFalse(PcLink.shouldReconnect(now = 400_000, lostAt = 10_000, lastAttemptAt = 0, inMission = false))
        // Nunca hubo conexión: no se insiste fuera de misión
        assertFalse(PcLink.shouldReconnect(now = 60_000, lostAt = 0, lastAttemptAt = 0, inMission = false))
    }

    private fun ip(s: String) = PcLink.parseIPv4(s)!!

    @Test fun parseaIpValida() {
        assertEquals(0xC0A88901.toInt(), ip("192.168.137.1"))
        assertNotNull(PcLink.parseIPv4("0.0.0.0"))
        assertNotNull(PcLink.parseIPv4("255.255.255.255"))
    }

    @Test fun rechazaIpInvalida() {
        listOf("", "192.168.137", "192.168.137.1.5", "192.168.137.256", "192.168.137.-1", "a.b.c.d", "192.168..1", "1234.1.1.1", " 192.168.137.1")
            .forEach { assertNull(it, PcLink.parseIPv4(it)) }
    }

    @Test fun claveWpa2Valida() {
        assertTrue(PcLink.isValidPassword("12345678"))
        assertTrue(PcLink.isValidPassword("con espacio y 'simbolos'!"))
        assertTrue(PcLink.isValidPassword("a".repeat(63)))
    }

    @Test fun claveWpa2Invalida() {
        assertFalse(PcLink.isValidPassword(""))
        assertFalse(PcLink.isValidPassword("1234567"))            // 7: Android lanzaría excepción
        assertFalse(PcLink.isValidPassword("a".repeat(64)))
        assertFalse(PcLink.isValidPassword("contraseñaLarga"))    // ñ no es ASCII
        assertFalse(PcLink.isValidPassword("clave\ncorta1"))      // salto de línea (pegado por error)
    }

    @Test fun mismaSubredDeLaZonaMovilDeWindows() {
        assertTrue(PcLink.sameSubnet(ip("192.168.137.23"), ip("192.168.137.1"), 24))
        assertFalse(PcLink.sameSubnet(ip("192.168.1.23"), ip("192.168.137.1"), 24))
    }

    @Test fun respetaElLargoDelPrefijo() {
        assertTrue(PcLink.sameSubnet(ip("192.168.1.23"), ip("192.168.137.1"), 16))
        assertFalse(PcLink.sameSubnet(ip("192.169.0.1"), ip("192.168.0.1"), 16))
        assertTrue(PcLink.sameSubnet(ip("10.0.0.1"), ip("200.1.1.1"), 0))
        assertFalse(PcLink.sameSubnet(ip("192.168.137.2"), ip("192.168.137.1"), 32))
        assertTrue(PcLink.sameSubnet(ip("192.168.137.1"), ip("192.168.137.1"), 32))
    }
}
