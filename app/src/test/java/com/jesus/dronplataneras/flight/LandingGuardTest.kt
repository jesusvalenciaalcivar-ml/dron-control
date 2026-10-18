package com.jesus.dronplataneras.flight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingGuardTest {

    private fun hold(
        status: String = "LANDING", flying: Boolean = true, altitude: Double = 2.0,
        battery: Int = 60, held: Boolean = false
    ) = LandingGuard.shouldHold(status, flying, altitude, battery, held)

    @Test fun holdsWhenTheReturnLandingGetsLow() {
        assertTrue(hold(altitude = 2.0))
        assertTrue(hold(altitude = LandingGuard.HOLD_BELOW_M))
    }

    @Test fun doesNotInterruptWhileStillHigh() {
        assertFalse(hold(altitude = 30.0))
        assertFalse(hold(altitude = LandingGuard.HOLD_BELOW_M + 0.5))
    }

    @Test fun onlyActsOnTheReturnToHomeLanding() {
        assertFalse(hold(status = "RETURNING_TO_HOME"))
        assertFalse(hold(status = "IDLE"))
        assertFalse(hold(status = "COMPLETED"))
    }

    @Test fun doesNotRepeatTheOrderAndIgnoresGroundedDrones() {
        assertFalse(hold(held = true))
        assertFalse(hold(flying = false))
    }

    @Test fun letsTheDroneLandWhenTheBatteryIsCritical() {
        assertFalse(hold(battery = 8))
        assertFalse(hold(battery = LandingGuard.MIN_BATTERY_PCT))
        assertTrue(hold(battery = LandingGuard.MIN_BATTERY_PCT + 1))
        // batería desconocida: sí se interrumpe
        assertTrue(hold(battery = 0))
    }
}
