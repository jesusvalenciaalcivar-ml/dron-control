package com.jesus.dronplataneras.telemetry

/**
 * Calidad mínima de GPS para fiarse de una posición. La usan el levantamiento del perímetro
 * ([com.jesus.dronplataneras.survey.SurveySession]) y el chequeo pre-vuelo
 * ([com.jesus.dronplataneras.mission.Preflight]); estaban repetidos y podían desajustarse.
 */
object GpsQuality {
    const val MIN_SATELLITES = 10

    /** Nivel de señal GPS de DJI: LEVEL_0 (malo) .. LEVEL_10. */
    const val MIN_SIGNAL_LEVEL = 4
}
