package com.jesus.dronplataneras.map

import android.util.Log
import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.StylePackLoadOptions
import com.mapbox.maps.TilesetDescriptorOptions
import com.mapbox.maps.mapsOptions

/**
 * Descarga de áreas de mapa satelital para usar sin internet (como "Mapas sin conexión").
 * Usa el mecanismo oficial de Mapbox: style pack + tile region en el TileStore.
 */
object OfflineMaps {

    private const val TAG = "OfflineMaps"
    private const val PREFIX = "agroscan-"

    data class Region(val id: String, val createdAt: Long, val sizeBytes: Long, val widthM: Int, val heightM: Int)

    private val offlineManager by lazy { OfflineManager() }
    private val tileStore: TileStore
        get() = MapboxOptions.mapsOptions.tileStore ?: TileStore.create()

    /**
     * Descarga el rectángulo [sw]–[ne]. [onProgress] recibe 0..1; [onDone] recibe null si terminó
     * bien o el mensaje de error. Devuelve lo necesario para cancelar.
     */
    fun download(
        sw: Point,
        ne: Point,
        widthM: Int,
        heightM: Int,
        pixelRatio: Float,
        onProgress: (Float) -> Unit,
        onDone: (error: String?) -> Unit
    ): List<Cancelable> {
        val cancelables = mutableListOf<Cancelable>()

        // 1. Estilos (fuentes, iconos, definición) de satélite y estándar: así se puede cambiar
        //    de tipo de mapa sin internet. Se descargan una sola vez.
        val styles = MapConfig.MapType.entries.map { it.styleUri }
        styles.forEach { uri ->
            cancelables += offlineManager.loadStylePack(
                uri,
                StylePackLoadOptions.Builder()
                    .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY)
                    .build(),
                { },
                { result -> result.error?.let { Log.w(TAG, "StylePack $uri: $it") } }
            )
        }

        // 2. Mosaicos del área para ambos estilos
        val descriptors = styles.map { uri ->
            offlineManager.createTilesetDescriptor(
                TilesetDescriptorOptions.Builder()
                    .styleURI(uri)
                    .pixelRatio(pixelRatio)
                    .minZoom(MapConfig.OFFLINE_MIN_ZOOM)
                    .maxZoom(MapConfig.OFFLINE_MAX_ZOOM)
                    .build()
            )
        }
        val area = Polygon.fromLngLats(
            listOf(
                listOf(
                    sw,
                    Point.fromLngLat(ne.longitude(), sw.latitude()),
                    ne,
                    Point.fromLngLat(sw.longitude(), ne.latitude()),
                    sw
                )
            )
        )
        val id = "$PREFIX${System.currentTimeMillis()}-${widthM}x${heightM}"
        cancelables += tileStore.loadTileRegion(
            id,
            TileRegionLoadOptions.Builder()
                .geometry(area)
                .descriptors(descriptors)
                .metadata(Value(id))
                .acceptExpired(true)
                .networkRestriction(NetworkRestriction.NONE)
                .build(),
            { progress ->
                if (progress.requiredResourceCount > 0) {
                    onProgress(progress.completedResourceCount.toFloat() / progress.requiredResourceCount)
                }
            }
        ) { result ->
            onDone(result.error?.let { "No se pudo descargar: ${it.message}" })
        }
        return cancelables
    }

    /** Áreas descargadas por AgroScan, de la más reciente a la más antigua. */
    fun list(onResult: (List<Region>) -> Unit) {
        tileStore.getAllTileRegions { result ->
            val regions = result.value.orEmpty()
                .filter { it.id.startsWith(PREFIX) }
                .mapNotNull { r ->
                    // id = agroscan-<epoch>-<ancho>x<alto>
                    val parts = r.id.removePrefix(PREFIX).split("-", "x")
                    val created = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    Region(
                        id = r.id,
                        createdAt = created,
                        sizeBytes = r.completedResourceSize,
                        widthM = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                        heightM = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    )
                }
                .sortedByDescending { it.createdAt }
            onResult(regions)
        }
    }

    /**
     * Borra una región descargada.
     */
    fun delete(id: String) {
        tileStore.removeTileRegion(id)
    }
}
