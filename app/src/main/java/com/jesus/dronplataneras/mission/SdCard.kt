package com.jesus.dronplataneras.mission

/** Lectura del estado de la microSD que reporta la cámara. */
object SdCard {

    enum class Level { OK, WARN, ERROR }

    data class Reading(val text: String, val level: Level)

    /**
     * @param videoMode en video una tarjeta lenta es un error (el 4K necesita escritura sostenida);
     *                  en fotos solo es un aviso.
     */
    fun read(state: String, videoMode: Boolean): Reading = when (state) {
        "", "UNKNOWN", "NORMAL" -> Reading(if (state == "NORMAL") "Lista" else "Sin dato", Level.OK)
        "FORMAT_RECOMMENDED" -> Reading("Se recomienda formatear", Level.WARN)
        "SLOW" -> Reading("Demasiado lenta para esta captura", if (videoMode) Level.ERROR else Level.WARN)
        "WRITING_SLOWLY" -> Reading("Está escribiendo lento", if (videoMode) Level.ERROR else Level.WARN)
        "NOT_INSERTED" -> Reading("No hay tarjeta", Level.ERROR)
        "INVALID" -> Reading("Tarjeta no válida", Level.ERROR)
        "READ_ONLY" -> Reading("Protegida contra escritura", Level.ERROR)
        "FORMAT_NEEDED" -> Reading("Necesita formato", Level.ERROR)
        "FORMATTING" -> Reading("Formateando", Level.ERROR)
        "INVALID_FILE_SYSTEM" -> Reading("Formato no compatible (usa exFAT)", Level.ERROR)
        "BUSY" -> Reading("Ocupada", Level.ERROR)
        "FULL" -> Reading("Llena", Level.ERROR)
        "NO_REMAINING_FILE_INDICES" -> Reading("Sin índices de archivo: formatea", Level.ERROR)
        "INITIALIZING" -> Reading("Iniciando", Level.ERROR)
        "RECOVERING_FILES" -> Reading("Recuperando archivos", Level.ERROR)
        "USB_CONNECTED" -> Reading("Conectada por USB", Level.ERROR)
        else -> Reading("Error ($state)", Level.ERROR)
    }
}
