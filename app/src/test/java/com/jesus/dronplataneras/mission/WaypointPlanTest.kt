package com.jesus.dronplataneras.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WaypointPlanTest {

    @Test fun parsesNativeFormatAndRoundTrips() {
        val json = """
            {"formato":"agroscan-waypoints","version":1,"mision":"Lote 1","altura_m":60,
             "velocidad_ms":5,"foto_cada_m":18.5,"rumbo_deg":35,
             "waypoints":[{"lat":-3.26,"lon":-79.96,"foto":true},{"lat":-3.261,"lon":-79.959,"alt_m":55}]}
        """
        val p = WaypointPlan.parse(json)
        assertEquals(2, p.waypoints.size)
        assertEquals(60.0, p.waypoints[0].altitudeM, 0.0)
        assertEquals(55.0, p.waypoints[1].altitudeM, 0.0)
        assertTrue(p.waypoints[0].takePhoto)
        assertEquals(18.5, p.photoEveryM, 0.0)
        assertEquals(p, WaypointPlan.parse(p.toJson()))
    }

    @Test fun parsesGeoJsonLineString() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},
            "geometry":{"type":"LineString","coordinates":[[-79.96,-3.26],[-79.959,-3.261,40]]}}]}"""
        val p = WaypointPlan.parse(json, defaultAltitudeM = 50.0)
        assertEquals(-3.26, p.waypoints[0].latitude, 0.0)
        assertEquals(50.0, p.waypoints[0].altitudeM, 0.0)
        assertEquals(40.0, p.waypoints[1].altitudeM, 0.0)
    }

    @Test fun rejectsTooHigh() {
        try {
            WaypointPlan.parse("""{"altura_m":200,"waypoints":[{"lat":1,"lon":1},{"lat":1.001,"lon":1}]}""")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("altura"))
        }
    }
}
