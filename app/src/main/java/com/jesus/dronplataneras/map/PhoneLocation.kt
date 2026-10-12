package com.jesus.dronplataneras.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jesus.dronplataneras.survey.LatLon

/**
 * Ubicación del teléfono (no la del dron), para centrar el mapa donde está el operador cuando el dron aún no tiene GPS.
 * Usa el servicio de ubicación de Android, sin depender de Google Play.
 */
object PhoneLocation {

    /** Una posición reciente se reutiliza; si es más vieja se espera un fix nuevo. */
    private const val FRESH_MS = 2 * 60_000L
    private const val TIMEOUT_MS = 15_000L

    /** [position] si se obtuvo; si no, [error] explica por qué. */
    data class Result(val position: LatLon?, val error: String?)

    /**
     * Busca la ubicación del teléfono y llama a [onResult] una sola vez, en el hilo principal: con la posición, o con el
     * motivo del fallo (sin permiso, ubicación apagada o sin señal a tiempo).
     */
    fun find(context: Context, onResult: (Result) -> Unit) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return onResult(Result(null, "Falta el permiso de ubicación: actívalo en los ajustes de la app"))

        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return onResult(Result(null, "Activa la ubicación (GPS) del teléfono"))

        val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < FRESH_MS }
            .maxByOrNull { it.time }
        if (recent != null) return onResult(Result(LatLon(recent.latitude, recent.longitude), null))

        val handler = Handler(Looper.getMainLooper())
        var done = false
        lateinit var listener: LocationListener
        fun finish(result: Result) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            runCatching { manager.removeUpdates(listener) }
            onResult(result)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(Result(LatLon(location.latitude, location.longitude), null))
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Solo se llama en versiones antiguas de Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        providers.forEach { runCatching { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
        handler.postDelayed({ finish(Result(null, "No se pudo obtener tu ubicación: sal a un lugar abierto y reintenta")) }, TIMEOUT_MS)
    }
}
