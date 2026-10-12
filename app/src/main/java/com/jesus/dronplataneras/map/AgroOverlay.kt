package com.jesus.dronplataneras.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.XY
import com.mapbox.geojson.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Todo lo que se dibuja sobre el mapa. Inmutable: Compose crea uno nuevo cuando algo cambia. */
data class MapLayers(
    /** Perímetro del lote (vértices fijados). */
    val polygon: List<LatLon> = emptyList(),
    /** true = dibujar cerrado y relleno; false = polilínea en construcción. */
    val polygonClosed: Boolean = true,
    /** Polígono con margen interno. */
    val inner: List<LatLon> = emptyList(),
    /** Ruta del barrido / waypoints. */
    val path: List<LatLon> = emptyList(),
    /** Índice del waypoint en curso durante la ejecución (-1 = ninguno). */
    val activeWaypoint: Int = -1,
    val drone: LatLon? = null,
    /** Rumbo del dron en grados brújula; null = se usa el del SDK. */
    val droneHeadingDeg: Double? = null,
    val home: LatLon? = null,
    /** Ubicación del teléfono (el operador), si se pidió. */
    val me: LatLon? = null,
    val highlightLastVertex: Boolean = false,
    /** Cuadrícula métrica: se muestra solo sin internet (donde no haya mapa descargado sirve de referencia). */
    val showGrid: Boolean = true
)

/**
 * Convierte a la coordenada de Mapbox (longitud, latitud).
 */
internal fun LatLon.toPoint(): Point = Point.fromLngLat(longitude, latitude)

/** Iconos dibujados una sola vez */
internal object MapIcons {

    private val cache = HashMap<String, Bitmap>()

    /** Flecha roja del dron con borde blanco. Apunta hacia arriba; el mapa la gira con el rumbo. */
    fun drone(density: Float): Bitmap = cache.getOrPut("drone") {
        val s = (34 * density).toInt()
        Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val cx = s / 2f
            val path = Path().apply {
                moveTo(cx, s * 0.06f)
                lineTo(s * 0.85f, s * 0.86f)
                lineTo(cx, s * 0.66f)
                lineTo(s * 0.15f, s * 0.86f)
                close()
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeJoin = Paint.Join.ROUND
            })
        }
    }

    /** Punto de origen: círculo amarillo con "H". */
    fun home(density: Float): Bitmap = cache.getOrPut("home") { badge(density, 0xFFFFC400.toInt(), 0xFF111111.toInt(), "H", 24f) }

    /** Vértice numerado (verde; ámbar el último que se puede ajustar). */
    fun vertex(density: Float, number: Int, highlighted: Boolean): Bitmap =
        cache.getOrPut("v$number$highlighted") {
            badge(density, if (highlighted) 0xFFF59E0B.toInt() else 0xFF00704A.toInt(), 0xFFFFFFFF.toInt(), "$number", if (highlighted) 24f else 20f)
        }

    /** Punto simple de color (inicio/fin de ruta, waypoint activo). */
    fun dot(density: Float, color: Int, sizeDp: Float = 12f): Bitmap =
        cache.getOrPut("dot$color$sizeDp") { badge(density, color, 0, "", sizeDp) }

    private fun badge(density: Float, fill: Int, textColor: Int, text: String, sizeDp: Float): Bitmap {
        val s = (sizeDp * density).toInt()
        return Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val r = s / 2f
            c.drawCircle(r, r, r - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            c.drawCircle(r, r, r - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
            })
            if (text.isNotEmpty()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textColor; textSize = s * 0.5f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
                }
                c.drawText(text, r, r - (p.descent() + p.ascent()) / 2, p)
            }
        }
    }
}

/**
 * Líneas de una cuadrícula métrica alrededor de [center], con separación "redonda" según el zoom.
 * Las líneas están en coordenadas reales, así que se mueven con el mapa y conservan la escala exacta.
 */
internal fun metricGrid(center: LatLon, zoom: Double, latitude: Double = center.latitude): Pair<Double, List<List<Point>>> {
    // metros por píxel lógico (512 px por mosaico en Mapbox)
    val metersPerPx = 40_075_016.686 * kotlin.math.cos(Math.toRadians(latitude)) / (512 * 2.0.pow(zoom))
    val target = metersPerPx * 90 // ~90 dp entre líneas
    val spacing = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
        .firstOrNull { it >= target } ?: 5000.0
    val half = spacing * 12
    val f = LocalFrame(center)
    // alinear a múltiplos de la separación respecto a una referencia fija (centro redondeado)
    val anchor = LatLon(floor(center.latitude * 100) / 100, floor(center.longitude * 100) / 100)
    val a = f.toXY(anchor)
    val x0 = a.x + floor((-half - a.x) / spacing) * spacing
    val y0 = a.y + floor((-half - a.y) / spacing) * spacing
    val n = ceil(2 * half / spacing).toInt() + 1
    val lines = ArrayList<List<Point>>(2 * n)
    for (i in 0..n) {
        val x = x0 + i * spacing
        lines += listOf(f.toLatLon(XY(x, -half)).toPoint(), f.toLatLon(XY(x, half)).toPoint())
        val y = y0 + i * spacing
        lines += listOf(f.toLatLon(XY(-half, y)).toPoint(), f.toLatLon(XY(half, y)).toPoint())
    }
    return spacing to lines
}
