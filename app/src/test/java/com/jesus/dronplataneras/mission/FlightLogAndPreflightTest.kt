package com.jesus.dronplataneras.mission

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FlightLogAndPreflightTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun logWritesHeaderSamplesAndEvents() {
        val file = tmp.newFolder("vuelo").resolve("salida-1.csv")
        FlightLogWriter(file).use { log ->
            log.sample(1_000, -3.26, -79.96, 60.0, 90.0, -90.0, 4.9, 3, "barrido", "FLYING", true)
            log.event(1_050, "video_inicio", mapOf("wp" to 0))
        }
        val lines = file.readLines()
        assertEquals(FlightLogWriter.HEADER, lines[0])
        assertEquals("1000,-3.2600000,-79.9600000,60.00,90.0,-90.0,4.90,3,barrido,FLYING,1", lines[1])
        val ev = JSONObject(log(file).readLines().single())
        assertEquals("video_inicio", ev.getString("evento"))
        assertEquals(1_050L, ev.getLong("t_ms"))
        assertEquals(0, ev.getInt("wp"))
    }

    private fun log(csv: java.io.File) = java.io.File(csv.parentFile, "salida-1.events.jsonl")

    @Test fun logThrottlesSamples() {
        val file = tmp.newFolder("v2").resolve("s.csv")
        FlightLogWriter(file).use { log ->
            repeat(10) { i ->
                log.sample(1_000L + i * 50, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, "barrido", "FLYING", false)
            }
        }
        // a 50 ms entre llamadas y 200 ms de intervalo mínimo: 1 de cada 4-5
        val rows = file.readLines().size - 1
        assertTrue("filas=$rows", rows in 2..3)
    }

    @Test fun logIgnoresWritesAfterClose() {
        val file = tmp.newFolder("v3").resolve("s.csv")
        val l = FlightLogWriter(file)
        l.close()
        l.sample(5_000, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, "x", "y", false)
        l.close() // idempotente
        assertEquals(1, file.readLines().size)
    }

    private fun good() = PreflightInput(
        hasPlan = true, connected = true, satellites = 14, linkQuality = 80, homeSet = true,
        batteryPct = 90, batteryRequiredPct = 70, gimbalPitchDeg = -90.0, maxRouteAltitudeM = 60.0,
        videoSecondsAvailable = 3_600.0, videoSecondsRequired = 900.0
    )

    @Test fun healthyDroneHasNoBlockers() {
        assertTrue(Preflight.blockers(Preflight.evaluate(good())).isEmpty())
    }

    private fun home(input: PreflightInput) = Preflight.evaluate(input).first { it.label.startsWith("Punto de origen") }

    @Test fun registeredHomeExplainsWhatItIsFor() {
        val item = home(good())
        assertTrue(item.ok)
        assertTrue(item.help!!.contains("Regresar a casa"))
        assertNull(item.fix)
    }

    @Test fun pendingHomeWithoutGpsExplainsWaitingAndOffersNoFix() {
        val item = home(good().copy(homeSet = false, satellites = 4))
        assertFalse(item.ok)
        assertTrue(item.help!!.contains("4 de ${com.jesus.dronplataneras.telemetry.GpsQuality.MIN_SATELLITES} satélites"))
        assertNull(item.fix)
    }

    @Test fun pendingHomeWithGpsOnTheGroundOffersToSetItHere() {
        val item = home(good().copy(homeSet = false))
        assertFalse(item.ok)
        assertEquals(Fix.SET_HOME_HERE, item.fix)
        assertTrue(Preflight.blockers(Preflight.evaluate(good().copy(homeSet = false))).any { it.label.startsWith("Punto de origen") })
    }

    @Test fun pendingHomeInTheAirOrDisconnectedNeverOffersTheFix() {
        assertNull(home(good().copy(homeSet = false, flying = true)).fix)
        assertNull(home(good().copy(homeSet = false, connected = false)).fix)
    }

    @Test fun lowBatteryBlocks() {
        val items = Preflight.evaluate(good().copy(batteryPct = 50))
        assertEquals(listOf("Batería"), Preflight.blockers(items).map { it.label })
    }

    @Test fun routeOutsideLotBlocksBeyondTolerance() {
        assertTrue(Preflight.blockers(Preflight.evaluate(good().copy(routeOutsideM = 8.0))).isEmpty())
        assertEquals(
            listOf("Ruta dentro del lote"),
            Preflight.blockers(Preflight.evaluate(good().copy(routeOutsideM = 25.0))).map { it.label }
        )
    }

    @Test fun gimbalIsInformativeOnly() {
        val items = Preflight.evaluate(good().copy(gimbalPitchDeg = -45.0))
        assertFalse(items.first { it.label.startsWith("Gimbal") }.ok)
        assertTrue(Preflight.blockers(items).isEmpty())
    }

    @Test fun storageBlocksOnlyWhenKnownAndInsufficient() {
        val unknown = Preflight.evaluate(good().copy(videoSecondsAvailable = Double.NaN))
        assertTrue(Preflight.blockers(unknown).isEmpty())
        val full = Preflight.evaluate(good().copy(videoSecondsAvailable = 600.0, videoSecondsRequired = 900.0))
        assertEquals(listOf("Espacio en microSD"), Preflight.blockers(full).map { it.label })
    }

    @Test fun returningHomeBlocksTheNextSortie() {
        val items = Preflight.evaluate(good().copy(returningHome = true))
        assertEquals(listOf("Regreso a casa"), Preflight.blockers(items).map { it.label })
        assertTrue(Preflight.blockers(Preflight.evaluate(good())).isEmpty())
    }

    @Test fun sdCardStateBlocksWhenUnusable() {
        for (state in listOf("NOT_INSERTED", "READ_ONLY", "FULL", "FORMAT_NEEDED", "INVALID_FILE_SYSTEM")) {
            val blockers = Preflight.blockers(Preflight.evaluate(good().copy(sdCardState = state)))
            assertEquals("estado $state", listOf("Tarjeta microSD"), blockers.map { it.label })
        }
        assertTrue(Preflight.blockers(Preflight.evaluate(good().copy(sdCardState = "NORMAL"))).isEmpty())
        // sin dato todavía: no se bloquea
        assertTrue(Preflight.blockers(Preflight.evaluate(good().copy(sdCardState = ""))).isEmpty())
    }

    @Test fun slowSdCardBlocksVideoButOnlyWarnsPhotos() {
        val video = Preflight.blockers(Preflight.evaluate(good().copy(sdCardState = "WRITING_SLOWLY")))
        assertEquals(listOf("Tarjeta microSD"), video.map { it.label })
        val photo = Preflight.evaluate(good().copy(captureMode = CaptureMode.PHOTO, sdCardState = "SLOW"))
        assertTrue(Preflight.blockers(photo).isEmpty())
        assertFalse(photo.first { it.label == "Tarjeta microSD" }.ok)
    }

    @Test fun photoSpaceBlocksWhenKnownAndInsufficient() {
        val photoInput = good().copy(captureMode = CaptureMode.PHOTO, photosRequired = 300.0)
        assertTrue(Preflight.blockers(Preflight.evaluate(photoInput.copy(photosAvailable = 5000.0))).isEmpty())
        assertEquals(
            listOf("Espacio para fotos"),
            Preflight.blockers(Preflight.evaluate(photoInput.copy(photosAvailable = 100.0))).map { it.label }
        )
        assertTrue(Preflight.blockers(Preflight.evaluate(photoInput.copy(photosAvailable = Double.NaN))).isEmpty())
    }

    @Test fun photoModeSkipsStorageCheck() {
        val items = Preflight.evaluate(good().copy(captureMode = CaptureMode.PHOTO))
        assertTrue(items.none { it.label == "Espacio en microSD" })
    }

    @Test fun missingPlanAndDisconnectedBlock() {
        val labels = Preflight.blockers(Preflight.evaluate(good().copy(hasPlan = false, connected = false))).map { it.label }
        assertTrue("Ruta cargada" in labels)
        assertTrue("Dron conectado" in labels)
    }
}
