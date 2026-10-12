package com.jesus.dronplataneras.survey

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val WGS84_A = 6_378_137.0
private const val WGS84_E2 = 6.69437999014e-3

/** Cualquier cosa con latitud/longitud WGS84 (vértices capturados, waypoints, entidades Room...). */
interface GeoCoord {
    val latitude: Double
    val longitude: Double
}

/**
 * Coordenada WGS84 simple (grados decimales).
 */
data class LatLon(override val latitude: Double, override val longitude: Double) : GeoCoord

/**
 * Esta coordenada como [LatLon] (sin copiar si ya lo es).
 */
fun GeoCoord.toLatLon(): LatLon = if (this is LatLon) this else LatLon(latitude, longitude)

/** Distancia plana en metros hasta [other] (válida a la escala de un lote, de unos pocos km). */
fun GeoCoord.distanceTo(other: GeoCoord): Double = LocalFrame(this).toXY(other).length()

/** Longitud en metros de la poligonal que une las coordenadas en orden. */
fun List<GeoCoord>.pathLengthM(): Double = zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

/** Punto en un plano local en metros: x = este, y = norte. */
data class XY(val x: Double, val y: Double) {
    operator fun plus(o: XY) = XY(x + o.x, y + o.y)
    operator fun minus(o: XY) = XY(x - o.x, y - o.y)
    operator fun times(k: Double) = XY(x * k, y * k)
    /**
     * Módulo del vector en metros.
     */
    fun length() = kotlin.math.hypot(x, y)
}

/**
 * Proyección plana local centrada en [origin] usando los radios de curvatura del elipsoide WGS84
 * en esa latitud (meridiano M y primer vertical N). Para lotes de algunos km el error frente al
 * cálculo geodésico exacto es < 0,05 % (con una esfera era ~0,5 %).
 */
class LocalFrame(origin: GeoCoord) {
    private val lat0 = origin.latitude
    private val lon0 = origin.longitude
    private val phi = Math.toRadians(lat0)
    private val w = 1 - WGS84_E2 * sin(phi) * sin(phi)
    /** metros por radián de latitud */
    private val m = WGS84_A * (1 - WGS84_E2) / (w * sqrt(w))
    /** metros por radián de longitud */
    private val n = WGS84_A / sqrt(w) * cos(phi)

    /**
     * Metros (este, norte) de [p] respecto al origen del marco.
     */
    fun toXY(p: GeoCoord) = XY(
        x = Math.toRadians(p.longitude - lon0) * n,
        y = Math.toRadians(p.latitude - lat0) * m
    )

    /**
     * Inversa de [toXY]: la coordenada que está a esos metros del origen.
     */
    fun toLatLon(p: XY) = LatLon(
        latitude = lat0 + Math.toDegrees(p.y / m),
        longitude = lon0 + Math.toDegrees(p.x / n)
    )
}
