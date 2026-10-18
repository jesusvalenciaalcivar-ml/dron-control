package com.jesus.dronplataneras.survey

/**
 * Un vértice del lote capturado con el dron.
 * Se guardan también los indicadores de calidad GPS del instante de captura para poder
 * auditar o descartar puntos dudosos antes del Inward Buffer / CPP.
 */
data class SurveyVertex(
    override val latitude: Double,
    override val longitude: Double,
    /** Altitud relativa al punto de despegue (m), tal como la reporta KeyAltitude. */
    val altitude: Double,
    val satellites: Int,
    /** Nivel de señal GPS de DJI (0..10), -1 si no se conoce. */
    val gpsLevel: Int,
    val timestampMs: Long
) : GeoCoord
