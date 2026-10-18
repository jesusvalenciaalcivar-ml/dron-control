package com.jesus.dronplataneras.mission

/**
 * Cómo se captura la imagen durante el barrido.
 *
 * VIDEO: el dron graba de corrido y el escritorio extrae los cuadros (la ruta no se detiene ni
 * está limitada por el intervalo mínimo entre fotos). Requiere el registro de vuelo con marcas de
 * tiempo para georreferenciar cada cuadro.
 * PHOTO: disparo por distancia (hay que respetar el intervalo mínimo de la cámara).
 */
enum class CaptureMode(val json: String) {
    VIDEO("video"),
    PHOTO("foto");

    companion object {
        fun fromJson(raw: String?): CaptureMode =
            entries.firstOrNull { it.json.equals(raw, ignoreCase = true) } ?: VIDEO
    }
}
