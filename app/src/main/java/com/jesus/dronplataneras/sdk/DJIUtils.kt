package com.jesus.dronplataneras.sdk

import android.os.Handler
import android.os.Looper
import dji.v5.common.error.IDJIError

private val mainHandler = Handler(Looper.getMainLooper())

fun runOnMain(action: () -> Unit) {
    mainHandler.post(action)
}

fun IDJIError.readable(): String =
    listOfNotNull(description(), hint(), errorCode()).firstOrNull { it.isNotBlank() }
        ?: "error desconocido"
