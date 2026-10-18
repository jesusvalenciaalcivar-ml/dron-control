package com.jesus.dronplataneras.sdk

import androidx.compose.runtime.mutableStateOf

/**
 * Mensaje de estado flotante que cualquier parte de la app puede publicar; `StatusToast` lo muestra unos segundos.
 */
object AppStatus {
    val message = mutableStateOf("")
}