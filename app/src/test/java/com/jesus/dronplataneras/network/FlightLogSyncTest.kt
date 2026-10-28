package com.jesus.dronplataneras.network

import com.jesus.dronplataneras.data.EstadoSalida
import com.jesus.dronplataneras.data.SalidaParaEnviar
import com.jesus.dronplataneras.mission.CaptureMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightLogSyncTest {

    private fun salida(uuid: String = "7c9e6679-7425-40de-944b-e07fc1f90ae7", modo: CaptureMode = CaptureMode.VIDEO, videoInicio: Long? = 1_000L) =
        SalidaParaEnviar(
            salidaUuid = uuid, indice = 1, estado = EstadoSalida.COMPLETADA, inicioMs = 900L, finMs = 1_700L,
            videoInicioMs = videoInicio, videoFinMs = videoInicio?.plus(600L), logPath = "/x/salida-2.csv",
            vueloUuid = "0f8fad5b-d9cb-469f-a165-70867728950e", vueloInicioMs = 800L, modoCaptura = modo,
            mision = "Sector 1 / Norte", lote = "Finca Sánchez"
        )

    @Test
    fun elCuerpoTraeLoQueLaPcNecesita() {
        val csv = "t_ms,lat,lon\n1,-0.29,-79.48\n"
        val eventos = """{"t_ms":5,"evento":"marca","n":1,"t_video_s":2.5}""" + "\n"
        val j = JSONObject(FlightLogSync.body(salida(), csv, eventos))
        assertEquals(1, j.getInt("v"))
        val s = j.getJSONObject("salida")
        assertEquals("7c9e6679-7425-40de-944b-e07fc1f90ae7", s.getString("uuid"))
        assertEquals(1, s.getInt("indice"))
        assertEquals("COMPLETADA", s.getString("estado"))
        assertEquals(900L, s.getLong("inicio_ms"))
        assertEquals(1_000L, s.getLong("video_inicio_ms"))
        assertEquals(1_600L, s.getLong("video_fin_ms"))
        val v = j.getJSONObject("vuelo")
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", v.getString("uuid"))
        assertEquals(800L, v.getLong("inicio_ms"))
        assertEquals("video", v.getString("modo"))
        assertEquals("Finca Sánchez", j.getString("lote"))
        assertEquals("Sector 1 / Norte", j.getString("mision"))
        assertEquals(csv, j.getString("registro_csv"))         // los archivos viajan tal cual
        assertEquals(eventos, j.getString("eventos_jsonl"))
    }

    @Test
    fun sinVideoLosTiemposVanComoNull() {
        val s = JSONObject(FlightLogSync.body(salida(modo = CaptureMode.PHOTO, videoInicio = null), "", "")).getJSONObject("salida")
        assertTrue(s.isNull("video_inicio_ms"))
        assertTrue(s.isNull("video_fin_ms"))
    }

    @Test
    fun soloSeEnvianLasQueLaPcNoTiene() {
        val a = salida("7c9e6679-7425-40de-944b-e07fc1f90ae7")
        val b = salida("16FD2706-8BAF-433B-82EB-8C7FADA847DA")
        val c = salida("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        // La PC responde en minúsculas; el UUID del teléfono podría venir en mayúsculas
        val pendientes = FlightLogSync.pendientes(listOf(a, b, c), setOf("7c9e6679-7425-40de-944b-e07fc1f90ae7", "16fd2706-8baf-433b-82eb-8c7fada847da"))
        assertEquals(listOf(c), pendientes)
    }
}
