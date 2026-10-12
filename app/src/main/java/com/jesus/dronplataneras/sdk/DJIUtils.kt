package com.jesus.dronplataneras.sdk

import android.os.Handler
import android.os.Looper
import dji.v5.common.error.IDJIError

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Ejecuta [action] en el hilo principal (los callbacks del SDK llegan en otros hilos).
 */
fun runOnMain(action: () -> Unit) {
    mainHandler.post(action)
}

/**
 * Texto corto del error del SDK para mostrárselo al operador.
 */
fun IDJIError.readable(): String =
    listOfNotNull(description(), hint(), errorCode()).firstOrNull { it.isNotBlank() && it != "null" }
        ?: "error desconocido"

/**
 * Todo lo que trae el error, para diagnosticar (p. ej. "SYSTEM_ERROR · FLIGHTCONTROLLER.StartTakeoff:-7").
 * El código interno (innerCode) es el que dice qué módulo del dron rechazó la orden.
 */
fun IDJIError.detail(): String =
    listOfNotNull(errorCode(), innerCode(), description(), hint())
        .filter { it.isNotBlank() && it != "null" }
        .distinct()
        .joinToString(" · ")
        .ifBlank { "error desconocido" }
