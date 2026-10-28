package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.distanceTo

/**
 * Dice cuándo toca poner una marca en el video: una al empezar la pasada y luego una cada [spacingM] metros recorridos
 * (medidos en línea recta desde la marca anterior). Se reinicia con [reset] al empezar cada tramo.
 */
class DistanceMarker(private val spacingM: Double) {

    private var last: LatLon? = null

    fun reset() {
        last = null
    }

    /** true si en [here] toca marcar. */
    fun due(here: LatLon): Boolean {
        val prev = last
        if (prev != null && prev.distanceTo(here) < spacingM) return false
        last = here
        return true
    }
}
