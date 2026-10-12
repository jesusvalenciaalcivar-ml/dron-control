package com.jesus.dronplataneras.planning

import com.jesus.dronplataneras.mission.CaptureMode
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cálculos de fotogrametría: huella de la cámara en el suelo, GSD, separación entre pasadas y entre capturas,
 * velocidad, tiempo de vuelo y batería. Todo es puro (sin SDK) y está probado.
 */
object Photogrammetry {

    /** En cuántas pasadas aparece, de media, un mismo punto del terreno con este traslape lateral. */
    fun timesCovered(sideOverlapPct: Double): Double = 1.0 / (1.0 - sideOverlapPct.coerceIn(0.0, 95.0) / 100.0)

    /** Segundos que se pierden al frenar y girar al final de cada pasada. */
    const val TURN_SECONDS = 6.0

    /**
     * Huella en el terreno (ancho x alto, m) de una captura nadir a [altitudeM].
     * [canopyHeightM] es la altura del cultivo: el traslape debe cumplirse en la copa, que está
     * más cerca de la cámara que el suelo.
     */
    fun footprint(
        altitudeM: Double,
        p: DroneProfile = DroneProfile.MINI_3,
        mode: CaptureMode = CaptureMode.PHOTO,
        canopyHeightM: Double = 0.0
    ): Pair<Double, Double> {
        val h = (altitudeM - canopyHeightM).coerceAtLeast(1.0)
        val diagPx = sqrt(p.imageWidthPx.toDouble() * p.imageWidthPx + p.imageHeightPx.toDouble() * p.imageHeightPx)
        val halfDiag = h * tan(Math.toRadians(p.diagonalFovDeg / 2))
        val w = 2 * halfDiag * p.imageWidthPx / diagPx
        val (pxW, pxH) = if (mode == CaptureMode.VIDEO) p.videoWidthPx to p.videoHeightPx else p.imageWidthPx to p.imageHeightPx
        return w to w * pxH / pxW
    }

    /**
     * GSD: centímetros de terreno por píxel de la imagen.
     */
    fun gsdCmPx(
        altitudeM: Double,
        p: DroneProfile = DroneProfile.MINI_3,
        mode: CaptureMode = CaptureMode.PHOTO,
        canopyHeightM: Double = 0.0
    ): Double {
        val pxW = if (mode == CaptureMode.VIDEO) p.videoWidthPx else p.imageWidthPx
        return footprint(altitudeM, p, mode, canopyHeightM).first / pxW * 100
    }

    /**
     * La cámara mira hacia abajo con el lado largo de la captura perpendicular a la pasada,
     * así el traslape lateral usa el ancho y el frontal usa el alto.
     */
    fun lineSpacingM(
        altitudeM: Double,
        overlapPct: Double,
        p: DroneProfile = DroneProfile.MINI_3,
        mode: CaptureMode = CaptureMode.PHOTO,
        canopyHeightM: Double = 0.0
    ) = footprint(altitudeM, p, mode, canopyHeightM).first * (1 - overlapPct / 100)

    /**
     * Separación entre capturas consecutivas a lo largo de la pasada (la que sale del traslape frontal).
     */
    fun photoSpacingM(
        altitudeM: Double,
        overlapPct: Double,
        p: DroneProfile = DroneProfile.MINI_3,
        mode: CaptureMode = CaptureMode.PHOTO,
        canopyHeightM: Double = 0.0
    ) = footprint(altitudeM, p, mode, canopyHeightM).second * (1 - overlapPct / 100)

    /**
     * Velocidad de la pasada.
     * FOTO: la que respeta el intervalo mínimo entre fotos.
     * VIDEO: sin límite de intervalo; la limita el desenfoque de movimiento (≈1 píxel con el
     * obturador supuesto) y [DroneProfile.maxVideoSpeedMs].
     */
    fun missionSpeedMs(
        photoSpacingM: Double,
        p: DroneProfile = DroneProfile.MINI_3,
        mode: CaptureMode = CaptureMode.PHOTO,
        gsdCmPx: Double = 0.0
    ): Double = when (mode) {
        CaptureMode.PHOTO -> minOf(p.maxMissionSpeedMs, photoSpacingM / p.minPhotoIntervalS).coerceAtLeast(1.0)
        CaptureMode.VIDEO -> {
            val blurLimit = if (gsdCmPx > 0) gsdCmPx / 100 / p.videoShutterS else Double.MAX_VALUE
            minOf(p.maxVideoSpeedMs, p.maxMissionSpeedMs, blurLimit).coerceAtLeast(1.0)
        }
    }

    /** Segundos de vuelo de una ruta: pasadas, giros, subida/bajada y tránsito de ida y vuelta. */
    fun flightSeconds(
        pathLengthM: Double,
        turns: Int,
        altitudeM: Double,
        speedMs: Double,
        transitM: Double = 0.0,
        p: DroneProfile = DroneProfile.MINI_3
    ): Double =
        pathLengthM / speedMs +
            turns * TURN_SECONDS +
            2 * altitudeM / p.climbSpeedMs +
            2 * transitM / p.maxMissionSpeedMs

    /** % de batería para volar [seconds], con la reserva de seguridad incluida. */
    fun batteryRequiredPct(seconds: Double, p: DroneProfile = DroneProfile.MINI_3): Int =
        // 1e-9: que el redondeo de coma flotante no convierta un 75,0000000001 % en 76 %
        ceil(seconds / 60 / (p.maxFlightTimeMin * p.usableFlightFraction) * 100 - 1e-9).toInt() + p.batteryReservePct

    /**
     * Estima un vuelo completo a partir de la longitud de la ruta; ver [FlightEstimate].
     */
    fun estimate(
        pathLengthM: Double,
        passes: Int,
        altitudeM: Double,
        overlapPct: Double,
        transitM: Double = 0.0,
        p: DroneProfile = DroneProfile.MINI_3,
        mode: CaptureMode = CaptureMode.PHOTO,
        canopyHeightM: Double = 0.0,
        /** Traslape frontal; por defecto el mismo que el lateral. En video se puede subir sin costo de vuelo. */
        frontOverlapPct: Double = overlapPct
    ): FlightEstimate {
        val (w, h) = footprint(altitudeM, p, mode, canopyHeightM)
        val pxW = if (mode == CaptureMode.VIDEO) p.videoWidthPx else p.imageWidthPx
        val gsd = w / pxW * 100
        val lineSpacing = w * (1 - overlapPct / 100)
        val photoSpacing = h * (1 - frontOverlapPct / 100)
        val speed = missionSpeedMs(photoSpacing, p, mode, gsd)
        val photos = if (pathLengthM <= 0) 0 else ceil(pathLengthM / photoSpacing).toInt() + passes
        val seconds = flightSeconds(pathLengthM, passes, altitudeM, speed, transitM, p)
        val videoMin = if (mode == CaptureMode.VIDEO) pathLengthM / speed / 60 else 0.0
        return FlightEstimate(
            gsdCmPx = gsd,
            footprintWidthM = w,
            footprintHeightM = h,
            lineSpacingM = lineSpacing,
            photoSpacingM = photoSpacing,
            speedMs = speed,
            photos = photos,
            durationMin = seconds / 60,
            batteryRequiredPct = batteryRequiredPct(seconds, p),
            mode = mode,
            frameIntervalS = if (mode == CaptureMode.VIDEO) photoSpacing / speed else 0.0,
            videoMinutes = videoMin,
            videoGb = videoMin * 60 * p.videoBitrateMbps / 8 / 1000
        )
    }
}
