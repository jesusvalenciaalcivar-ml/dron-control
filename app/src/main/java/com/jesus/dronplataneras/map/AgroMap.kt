package com.jesus.dronplataneras.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.ComposeMapInitOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PolygonAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotationGroup
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.launch

/**
 * Mapa satelital (Mapbox)
 *
 * Dibuja el lote, el margen interno, la ruta, los vértices numerados, el punto de origen y la
 * flecha del dron. Sin internet usa lo descargado/cacheado y muestra una cuadrícula métrica como
 * referencia donde no haya mapa.
 *
 * @param fitKey cada vez que cambia, el mapa se encuadra en el lote/ruta (o en [fallbackCenter]).
 * @param followDrone mantiene el dron centrado.
 * @param locateKey centra el mapa en [MapLayers.me] cada vez que cambia.
 * @param onLocateMe convierte la mira de los botones redondos en "mi ubicación" (ver [PhoneLocation]).
 * @param interactive false = miniatura (sin gestos ni botones; el toque lo maneja el contenedor).
 */
@Composable
fun AgroMap(
    layers: MapLayers,
    modifier: Modifier = Modifier,
    fitKey: Any? = Unit,
    followDrone: Boolean = false,
    /** Cada vez que cambia (y [MapLayers.me] existe), el mapa se centra en la ubicación del teléfono. */
    locateKey: Int = 0,
    /** Si se da, la mira de los botones redondos busca la ubicación del teléfono (en vez de centrar en el dron). */
    onLocateMe: (() -> Unit)? = null,
    fallbackCenter: LatLon? = null,
    interactive: Boolean = true,
    /** Dónde van los botones redondos de centrado (cambiar si un panel tapa la derecha). */
    controlsAlignment: Alignment = Alignment.CenterEnd,
    controlsModifier: Modifier = Modifier,
    onMapReady: (MapView) -> Unit = {}
) {
    val context = LocalContext.current
    remember { MapConfig.init(context) }
    val online by NetworkMonitor.isOnline
    val mapType by MapConfig.mapType
    val sdkHeading by DroneInfo.heading
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    val start = layers.drone ?: layers.polygon.firstOrNull() ?: layers.path.firstOrNull() ?: fallbackCenter
    val viewport = rememberMapViewportState {
        setCameraOptions {
            zoom(if (interactive) 17.0 else 16.0)
            start?.let { center(it.toPoint()) }
        }
    }

    // Encuadre automático (al entrar o cuando cambia fitKey)
    LaunchedEffect(fitKey) {
        val pts = (layers.polygon + layers.path + listOfNotNull(layers.drone)).map { it.toPoint() }
        when {
            pts.size >= 2 -> {
                val pad = 48.0 * density
                viewport.setCameraOptions(viewport.cameraForCoordinates(pts, cameraOptions { }, EdgeInsets(pad, pad, pad, pad), 19.0, null))
            }
            pts.size == 1 -> viewport.setCameraOptions { center(pts[0]) }
            fallbackCenter != null -> viewport.setCameraOptions { center(fallbackCenter.toPoint()) }
        }
    }
    // "Mi ubicación": centrar en el teléfono cuando el operador lo pide
    LaunchedEffect(locateKey) {
        if (locateKey > 0) layers.me?.let { viewport.easeTo(cameraOptions { center(it.toPoint()); zoom(17.0) }) }
    }
    // Seguir al dron
    LaunchedEffect(followDrone, layers.drone) {
        if (followDrone) layers.drone?.let { viewport.setCameraOptions { center(it.toPoint()) } }
    }

    val heading = layers.droneHeadingDeg ?: sdkHeading.takeUnless { it.isNaN() } ?: 0.0
    val camera = viewport.cameraState
    val showGrid = layers.showGrid && !online && camera != null

    Box(modifier.clipToBounds()) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            // TextureView: se recorta bien con esquinas redondeadas y no tapa otros elementos
            composeMapInitOptions = remember { ComposeMapInitOptions(density, textureView = true) },
            compass = { if (interactive) Compass(alignment = Alignment.TopEnd) },
            scaleBar = { if (interactive) ScaleBar(alignment = Alignment.BottomEnd, textColor = Color.White, primaryColor = Color.White, secondaryColor = Color.Black) },
            mapViewportState = viewport,
            style = { MapStyle(style = mapType.styleUri) }
        ) {
            MapEffect(interactive) { mapView ->
                mapView.gestures.updateSettings {
                    scrollEnabled = interactive
                    pinchToZoomEnabled = interactive
                    rotateEnabled = interactive
                    pitchEnabled = false
                    doubleTapToZoomInEnabled = interactive
                    doubleTouchToZoomOutEnabled = interactive
                    quickZoomEnabled = interactive
                }
                onMapReady(mapView)
            }

            // Cuadrícula métrica (solo sin internet)
            if (showGrid) {
                val c = camera!!.center
                val (_, lines) = metricGrid(LatLon(c.latitude(), c.longitude()), camera.zoom)
                PolylineAnnotationGroup(annotations = lines.map { PolylineAnnotationOptions().withPoints(it) }) {
                    lineColor = if (mapType == MapConfig.MapType.SATELITE) Color(0x55FFFFFF) else Color(0x5500704A)
                    lineWidth = 1.0
                }
            }

            // Lote
            if (layers.polygon.size >= 3 && layers.polygonClosed) {
                PolygonAnnotation(points = listOf(layers.polygon.map { it.toPoint() } + layers.polygon.first().toPoint())) {
                    fillColor = Color(0xFF29E6A3)
                    fillOpacity = 0.22
                }
            }
            if (layers.polygon.size >= 2) {
                val ring = layers.polygon.map { it.toPoint() } +
                    if (layers.polygonClosed && layers.polygon.size >= 3) listOf(layers.polygon.first().toPoint()) else emptyList()
                PolylineAnnotation(points = ring) {
                    lineColor = Color(0xFF29E6A3)
                    lineWidth = 3.0
                }
            }
            // Margen interno
            if (layers.inner.size >= 3) {
                PolylineAnnotation(points = layers.inner.map { it.toPoint() } + layers.inner.first().toPoint()) {
                    lineColor = Color(0xFFFFC400)
                    lineWidth = 1.5
                }
            }
            // Ruta
            if (layers.path.size >= 2) {
                PolylineAnnotation(points = layers.path.map { it.toPoint() }) {
                    lineColor = Color(0xFF4FC3F7)
                    lineWidth = 2.0
                }
            }
            // Línea dron → origen
            if (layers.drone != null && layers.home != null) {
                PolylineAnnotation(points = listOf(layers.home.toPoint(), layers.drone.toPoint())) {
                    lineColor = Color(0xCCFFFFFF)
                    lineWidth = 1.2
                }
            }

            // Marcadores (iconos): inicio/fin de ruta, vértices, origen y dron
            val markers = buildList {
                if (layers.path.size >= 2) {
                    add(PointAnnotationOptions().withPoint(layers.path.first().toPoint()).withIconImage(MapIcons.dot(density, 0xFF3B82F6.toInt())))
                    add(PointAnnotationOptions().withPoint(layers.path.last().toPoint()).withIconImage(MapIcons.dot(density, 0xFFEF4444.toInt())))
                    if (layers.activeWaypoint in layers.path.indices) {
                        add(PointAnnotationOptions().withPoint(layers.path[layers.activeWaypoint].toPoint()).withIconImage(MapIcons.dot(density, 0xFFF59E0B.toInt(), 16f)))
                    }
                }
                layers.polygon.forEachIndexed { i, p ->
                    val hl = layers.highlightLastVertex && i == layers.polygon.lastIndex
                    add(PointAnnotationOptions().withPoint(p.toPoint()).withIconImage(MapIcons.vertex(density, i + 1, hl)))
                }
                layers.home?.let { add(PointAnnotationOptions().withPoint(it.toPoint()).withIconImage(MapIcons.home(density))) }
                layers.me?.let { add(PointAnnotationOptions().withPoint(it.toPoint()).withIconImage(MapIcons.dot(density, 0xFF2563EB.toInt(), 16f))) }
                layers.drone?.let {
                    add(PointAnnotationOptions().withPoint(it.toPoint()).withIconImage(MapIcons.drone(density)).withIconRotate(heading))
                }
            }
            if (markers.isNotEmpty()) {
                PointAnnotationGroup(annotations = markers) {
                    iconAllowOverlap = true
                    iconIgnorePlacement = true
                    // la flecha gira con el mapa: el rumbo es respecto al norte, no a la pantalla
                    iconRotationAlignment = IconRotationAlignment.MAP
                }
            }
        }

        if (!MapConfig.hasToken) {
            Text(
                "Falta MAPBOX_ACCESS_TOKEN en local.properties",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(Color(0xCC000000), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // Botones redondos oscuros, como los del mapa de DJI Fly
        if (interactive) {
            Column(
                Modifier.align(controlsAlignment).then(controlsModifier).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Cambiar entre satélite y estándar (la elección se recuerda en toda la app)
                MapRoundButton(
                    if (mapType == MapConfig.MapType.SATELITE) Icons.Filled.Map else Icons.Filled.Satellite,
                    "Cambiar a mapa ${if (mapType == MapConfig.MapType.SATELITE) "estándar" else "satelital"}"
                ) {
                    MapConfig.setMapType(
                        context,
                        if (mapType == MapConfig.MapType.SATELITE) MapConfig.MapType.ESTANDAR else MapConfig.MapType.SATELITE
                    )
                }
                if (onLocateMe != null) {
                    MapRoundButton(Icons.Filled.MyLocation, "Mi ubicación", onLocateMe)
                } else {
                    layers.drone?.let { d ->
                        MapRoundButton(Icons.Filled.MyLocation, "Centrar en el dron") {
                            viewport.easeTo(cameraOptions { center(d.toPoint()) })
                        }
                    }
                }
                layers.home?.let { h ->
                    MapRoundButton(Icons.Filled.Home, "Centrar en el punto de origen") {
                        viewport.easeTo(cameraOptions { center(h.toPoint()) })
                    }
                }
                val all = (layers.polygon + layers.path).map { it.toPoint() }
                if (all.size >= 2) {
                    MapRoundButton(Icons.Filled.CropFree, "Ver todo el lote") {
                        val pad = 48.0 * density
                        scope.launch {
                            viewport.easeTo(viewport.cameraForCoordinates(all, cameraOptions { }, EdgeInsets(pad, pad, pad, pad), 19.0, null))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
