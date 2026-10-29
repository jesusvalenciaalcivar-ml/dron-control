package com.jesus.dronplataneras.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Baja el gimbal al suelo para que el operador vea el punto que marca y lo devuelve al ángulo que tenía **en cuanto llega abajo**, sin
 * esperar. Si el operador lo mueve a mano mientras baja, no llega al suelo y el regreso se cancela: no se lo quita de las manos.
 * Las dependencias entran por parámetro para probarlo sin dron.
 *
 * @param pitch ángulo actual del gimbal en grados (NaN si se desconoce)
 * @param rotate manda la orden de girar a un ángulo (solo la manda; el avance se lee con [pitch])
 * @param atNadir si un ángulo cuenta como "mirando al suelo"
 */
class GimbalLook(
    private val scope: CoroutineScope,
    private val pitch: () -> Double,
    private val rotate: (Double) -> Unit,
    private val atNadir: (Double) -> Boolean,
    /** Si no llega al suelo en este tiempo, el operador lo está moviendo: se deja como él lo puso. */
    private val arriveTimeoutMs: Long = 3_000,
    private val pollMs: Long = 50
) {
    private var job: Job? = null

    /** Hay una bajada en curso (otra marca durante ese tiempo no la reinicia). */
    val busy: Boolean get() = job?.isActive == true

    /**
     * Si el gimbal no mira al suelo, lo baja y, al llegar, lo devuelve al ángulo anterior ([onRestored] avisa cuando lo pide).
     * Devuelve el trabajo iniciado, o null si no hizo nada (ya miraba al suelo o ya hay uno en curso).
     * shortcut: si el ángulo anterior se desconoce (NaN) baja pero no puede volver; no hay dato que restaurar.
     */
    fun lookDownThenRestore(onRestored: () -> Unit = {}): Job? {
        if (busy) return null
        val before = pitch()
        if (atNadir(before)) return null
        rotate(NADIR_DEG)
        return scope.launch {
            if (!arrived()) return@launch
            if (!before.isNaN()) {
                rotate(before)
                onRestored()
            }
        }.also { job = it }
    }

    private suspend fun arrived(): Boolean {
        var waited = 0L
        while (!atNadir(pitch())) {
            if (waited >= arriveTimeoutMs) return false
            delay(pollMs)
            waited += pollMs
        }
        return true
    }

    companion object {
        const val NADIR_DEG = -90.0
    }
}
