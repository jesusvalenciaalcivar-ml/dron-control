package com.jesus.dronplataneras.flight

import org.junit.Assert.assertEquals
import org.junit.Test

class ReturnHomeHeightTest {

    @Test fun laRutaMasUnMargen() = assertEquals(30, FlightActions.returnHomeHeightFor(25.0))

    @Test fun redondeaHaciaArribaNuncaHaciaAbajo() = assertEquals(31, FlightActions.returnHomeHeightFor(25.2))

    @Test fun noBajaDelMinimoDelDron() = assertEquals(20, FlightActions.returnHomeHeightFor(5.0))

    @Test fun noPasaDelMaximoDelDron() = assertEquals(500, FlightActions.returnHomeHeightFor(900.0))
}
