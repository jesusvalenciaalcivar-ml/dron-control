package com.jesus.dronplataneras.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPacketTest {

    private fun sample(lat: Double? = -0.29947234, lon: Double? = -79.48817118, rumbo: Double? = 212.4) = TelemetrySample(
        tMs = 1793022406491, seq = 7, lat = lat, lon = lon, altM = 25.04, velMs = 1.7734, rumbo = rumbo,
        bat = 78, sat = 29, enlace = 92, fase = "FLYING", volando = true, wp = 3, wpTotal = 6, avance = 0.41234,
        distM = 97.25, grabando = true, fotos = 0, tVueloS = 131
    )

    @Test fun telemetriaLlevaTodosLosCamposRedondeados() {
        val o = JSONObject(TelemetryPacket.telemetry(sample()))
        assertEquals("tel", o.getString("tipo"))
        assertEquals(1, o.getInt("v"))
        assertEquals(7, o.getInt("seq"))
        assertEquals(-0.2994723, o.getDouble("lat"), 1e-9)
        assertEquals(-79.4881712, o.getDouble("lon"), 1e-9)
        assertEquals(25.0, o.getDouble("alt_m"), 1e-9)
        assertEquals(1.77, o.getDouble("vel_ms"), 1e-9)
        assertEquals(212.0, o.getDouble("rumbo"), 1e-9)
        assertEquals(0.412, o.getDouble("avance"), 1e-9)
        assertEquals("FLYING", o.getString("fase"))
        assertTrue(o.getBoolean("grabando"))
    }

    @Test fun sinDatoNoSeEnviaElCampo() {
        val o = JSONObject(TelemetryPacket.telemetry(sample(lat = null, lon = null, rumbo = null)))
        assertFalse(o.has("lat"))
        assertFalse(o.has("lon"))
        assertFalse(o.has("rumbo"))
        assertEquals(78, o.getInt("bat"))   // lo demás sigue yendo
    }

    @Test fun avisaSiElDronNoEstaConectado() {
        assertTrue(JSONObject(TelemetryPacket.telemetry(sample())).getBoolean("dron"))
        assertFalse(JSONObject(TelemetryPacket.telemetry(sample().copy(dron = false))).getBoolean("dron"))
    }

    @Test fun elPaqueteCabeEnUnDatagramaNormal() {
        assertTrue(TelemetryPacket.telemetry(sample()).toByteArray().size < 400)
    }

    private fun plan(route: List<Pair<Double, Double>> = listOf(-0.1 to -79.1, -0.2 to -79.2), home: Pair<Double, Double>? = -0.3 to -79.3) =
        PlanInfo("Lote \"Norte\" / 1", salida = 2, salidas = 3, alturaM = 25.0, modo = "video", home = home, route = route)

    @Test fun planLlevaMisionSalidaParametrosOrigenYRuta() {
        val o = JSONObject(TelemetryPacket.plan(1L, 2, plan()))
        assertEquals("plan", o.getString("tipo"))
        assertEquals("Lote \"Norte\" / 1", o.getString("mision"))   // comillas y barra se escapan y se recuperan
        assertEquals(2, o.getInt("salida"))
        assertEquals(3, o.getInt("salidas"))
        assertEquals(25.0, o.getDouble("altura_m"), 1e-9)
        assertEquals("video", o.getString("modo"))
        assertEquals(-79.3, o.getJSONArray("home").getDouble(1), 1e-9)
        val ruta = o.getJSONArray("ruta")
        assertEquals(4, ruta.length())
        assertEquals(-79.2, ruta.getDouble(3), 1e-9)
    }

    @Test fun sinOrigenFijadoNoSeEnviaHome() {
        assertFalse(JSONObject(TelemetryPacket.plan(1L, 2, plan(home = null))).has("home"))
    }

    @Test fun rutaMuyLargaSeAdelgazaSinPerderElFinal() {
        val route = (0 until 1000).map { it * 1e-5 to -79.0 }
        val ruta = JSONObject(TelemetryPacket.plan(1L, 2, plan(route = route))).getJSONArray("ruta")
        assertTrue(ruta.length() / 2 <= TelemetryPacket.MAX_ROUTE_POINTS + 1)
        assertEquals(999 * 1e-5, ruta.getDouble(ruta.length() - 2), 1e-9)   // el último punto sigue estando
    }

    @Test fun loteLlevaElPerimetroEnOrden() {
        val o = JSONObject(TelemetryPacket.lote(1L, 3, listOf(-0.1 to -79.1, -0.2 to -79.2, -0.3 to -79.0)))
        assertEquals("lote", o.getString("tipo"))
        val p = o.getJSONArray("perimetro")
        assertEquals(6, p.length())
        assertEquals(-0.1, p.getDouble(0), 1e-9)
        assertEquals(-79.0, p.getDouble(5), 1e-9)
    }

    @Test fun perimetroMuyLargoSeAdelgaza() {
        val p = (0 until 500).map { it * 1e-5 to -79.0 }
        val out = JSONObject(TelemetryPacket.lote(1L, 3, p)).getJSONArray("perimetro")
        assertTrue(out.length() / 2 <= TelemetryPacket.MAX_PERIMETER_POINTS + 1)
    }

    @Test fun losPaquetesDeMisionCabenEnUnDatagramaRazonable() {
        val route = (0 until 150).map { it * 1e-5 to -79.1234567 }
        assertTrue(TelemetryPacket.plan(1L, 2, plan(route = route)).toByteArray().size < 4_000)
        assertTrue(TelemetryPacket.lote(1L, 3, (0 until 60).map { it * 1e-5 to -79.1234567 }).toByteArray().size < 2_000)
    }
}
