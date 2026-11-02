package com.jesus.dronplataneras.network

import com.jesus.dronplataneras.data.EstadoMision
import com.jesus.dronplataneras.data.EstadoSalida
import com.jesus.dronplataneras.data.EstadoVuelo
import com.jesus.dronplataneras.data.Historial
import com.jesus.dronplataneras.data.LoteConVertices
import com.jesus.dronplataneras.data.LoteEntity
import com.jesus.dronplataneras.data.MisionCompleta
import com.jesus.dronplataneras.data.MisionEntity
import com.jesus.dronplataneras.data.ParametrosVueloEntity
import com.jesus.dronplataneras.data.SalidaEntity
import com.jesus.dronplataneras.data.VerticeEntity
import com.jesus.dronplataneras.data.VueloCompleto
import com.jesus.dronplataneras.data.VueloEntity
import com.jesus.dronplataneras.mission.CaptureMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySyncTest {

    private val lote = LoteConVertices(
        LoteEntity(id = 1, nombre = "Finca Sánchez", areaEstimadaHa = 1.5, fechaCreacion = 100L, uuid = "11111111-1111-4111-8111-111111111111"),
        // Room no garantiza el orden: el envío va por ordenSecuencia
        listOf(
            VerticeEntity(id = 2, loteId = 1, ordenSecuencia = 1, latitud = -0.30, longitud = -79.49),
            VerticeEntity(id = 1, loteId = 1, ordenSecuencia = 0, latitud = -0.29, longitud = -79.48),
            VerticeEntity(id = 3, loteId = 1, ordenSecuencia = 2, latitud = -0.31, longitud = -79.47)
        )
    )
    private val mision = MisionCompleta(
        MisionEntity(
            id = 5, nombre = "Sector 1", loteId = 1, parametrosId = 9, estado = EstadoMision.SINCRONIZADO, duracionEstimadaMin = 12.5,
            fotosEstimadas = 80, rutaWaypointsJson = """{"waypoints":[]}""", fechaCreacion = 200L, fechaActualizacion = 300L,
            uuid = "22222222-2222-4222-8222-222222222222"
        ),
        lote,
        ParametrosVueloEntity(id = 9, alturaMetros = 25.0, traslapePorcentaje = 70.0, margenInternoMetros = 3.0, modoCaptura = CaptureMode.VIDEO)
    )
    private val vuelo = VueloCompleto(
        VueloEntity(
            id = 7, uuid = "33333333-3333-4333-8333-333333333333", misionId = 5, estado = EstadoVuelo.COMPLETADO, fechaInicio = 1_000L,
            fechaFin = 2_000L, modoCaptura = CaptureMode.VIDEO, perimetroJson = "[[-0.29,-79.48]]", rutaJson = "{}", totalWaypoints = 6, siguienteWaypoint = 6
        ),
        listOf(
            SalidaEntity(
                id = 1, uuid = "44444444-4444-4444-8444-444444444444", vueloId = 7, indice = 0, desdeWaypoint = 0, hastaWaypoint = 5,
                estado = EstadoSalida.COMPLETADA, inicioMs = 1_000L, finMs = 1_900L, bateriaInicio = 98, bateriaFin = null, distanciaM = 812.5
            )
        )
    )

    @Test
    fun elHistorialTraeLoQueLaBaseDeLaPcNecesita() {
        val j = JSONObject(HistorySync.body(Historial(listOf(lote), listOf(mision), listOf(vuelo))))
        assertEquals(1, j.getInt("v"))

        val l = j.getJSONArray("lotes").getJSONObject(0)
        assertEquals("11111111-1111-4111-8111-111111111111", l.getString("uuid"))
        assertEquals("Finca Sánchez", l.getString("nombre"))
        val v = l.getJSONArray("vertices")
        assertEquals(3, v.length())
        assertEquals(-0.29, v.getJSONArray(0).getDouble(0), 0.0)   // [lat, lon] en el orden del perímetro
        assertEquals(-79.48, v.getJSONArray(0).getDouble(1), 0.0)
        assertEquals(-0.31, v.getJSONArray(2).getDouble(0), 0.0)

        val m = j.getJSONArray("misiones").getJSONObject(0)
        assertEquals(l.getString("uuid"), m.getString("lote"))        // los hijos apuntan al padre por UUID, no por id de Room
        assertEquals("SINCRONIZADO", m.getString("estado"))
        assertEquals("""{"waypoints":[]}""", m.getString("ruta"))      // la ruta viaja como texto, tal cual
        val p = m.getJSONObject("parametros")
        assertEquals(25.0, p.getDouble("altura_m"), 0.0)
        assertEquals(75.0, p.getDouble("traslape_frontal_pct"), 0.0)
        assertEquals("video", p.getString("modo"))

        val vu = j.getJSONArray("vuelos").getJSONObject(0)
        assertEquals(m.getString("uuid"), vu.getString("mision"))
        assertEquals("COMPLETADO", vu.getString("estado"))
        assertEquals(2_000L, vu.getLong("fin_ms"))
        val s = vu.getJSONArray("salidas").getJSONObject(0)
        assertEquals("44444444-4444-4444-8444-444444444444", s.getString("uuid"))
        assertEquals(98, s.getInt("bateria_inicio"))
        assertTrue(s.isNull("bateria_fin"))                            // lo que falta va como null, no se inventa
        assertTrue(s.isNull("video_inicio_ms"))
    }

    @Test
    fun unVueloSinSuMisionNoSeEnvia() {
        // No pasa con la base del teléfono (la misión se borra en cascada), pero la PC rechazaría el envío entero
        val j = JSONObject(HistorySync.body(Historial(listOf(lote), emptyList(), listOf(vuelo))))
        assertEquals(0, j.getJSONArray("vuelos").length())
        assertEquals(0, j.getJSONArray("misiones").length())
    }
}
