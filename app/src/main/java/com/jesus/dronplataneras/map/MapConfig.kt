package com.jesus.dronplataneras.map

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.Style

/**
 * Configuración del mapa (Mapbox Maps SDK).
 *
 * - Estilo satelital de Mapbox (imagen de alta resolución + calles y nombres)
 * - Con internet se ve todo; lo visto queda en caché.
 * - Sin internet se ve lo descargado en "Mapas sin conexión" (OfflineMaps). Mapbox permite esa
 *   descarga en sus términos siempre que se haga con su SDK, desde sus servidores.
 *
 * Token: crear una cuenta gratuita en mapbox.com y copiar el "Default public token" (pk.…) en
 * local.properties como `MAPBOX_ACCESS_TOKEN=pk....`.
 */
object MapConfig {

    private const val TAG = "MapConfig"

    /** Tipos de mapa que el usuario puede elegir */
    enum class MapType(val styleUri: String, val label: String) {
        SATELITE(Style.SATELLITE_STREETS, "Satélite"),
        ESTANDAR(Style.MAPBOX_STREETS, "Estándar")
    }

    /** Tipo de mapa elegido; se recuerda entre sesiones y lo usan todos los mapas de la app. */
    val mapType = mutableStateOf(MapType.SATELITE)

    private const val PREFS = "agroscan_mapa"

    /**
     * Cambia el tipo de mapa de toda la app y lo recuerda para la próxima sesión.
     */
    fun setMapType(context: Context, type: MapType) {
        mapType.value = type
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("tipo", type.name).apply()
    }

    /** Rango de zoom que se descarga para uso sin conexión (18 ≈ 0,6 m/píxel en Ecuador). */
    const val OFFLINE_MIN_ZOOM: Byte = 10
    const val OFFLINE_MAX_ZOOM: Byte = 18

    var hasToken: Boolean = false
        private set

    private var initialized = false

    /**
     * Lee el token de Mapbox del manifiesto, restaura el tipo de mapa y arranca [NetworkMonitor]. Es idempotente.
     */
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val token = try {
            @Suppress("DEPRECATION")
            app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.jesus.dronplataneras.MAPBOX_ACCESS_TOKEN").orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer MAPBOX_ACCESS_TOKEN", e)
            ""
        }
        mapType.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tipo", null)
            ?.let { runCatching { MapType.valueOf(it) }.getOrNull() } ?: MapType.SATELITE
        hasToken = token.startsWith("pk.")
        if (hasToken) MapboxOptions.accessToken = token
        else Log.e(TAG, "Falta MAPBOX_ACCESS_TOKEN (pk.…) en local.properties: el mapa no cargará imágenes")
        NetworkMonitor.start(app)
        initialized = true
    }
}
