package com.jesus.dronplataneras.ui

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.map.MapConfig
import com.jesus.dronplataneras.map.NetworkMonitor
import com.jesus.dronplataneras.map.OfflineMaps
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.telemetry.TelemetryManager
import com.mapbox.common.Cancelable
import com.mapbox.geojson.Point
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.extension.compose.ComposeMapInitOptions
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.extension.compose.style.MapStyle
import kotlinx.coroutines.launch

/** Recuadro de selección en fracciones del mapa (como DJI Fly: ancho, centrado arriba). */
private const val SEL_LEFT = 0.15f
private const val SEL_RIGHT = 0.85f
private const val SEL_TOP = 0.03f
private const val SEL_BOTTOM = 0.80f

/** Área máxima por descarga (km²): mantiene la descarga rápida y bajo el límite de Mapbox. */
private const val MAX_AREA_KM2 = 25.0

/**
 * Mapas sin conexión, al estilo DJI Fly: se mueve el mapa, lo que queda dentro del recuadro se
 * descarga con un toque. La pestaña "Descargas" lista y borra las áreas guardadas.
 */
@Composable
fun OfflineMapDownloadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { MapConfig.init(context) }
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // Barra superior: atrás + pestañas centradas
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "Volver", tint = Color(0xFF333333))
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TabLabel("Seleccionar mapa", tab == 0) { tab = 0 }
                TabLabel("Descargas", tab == 1) { tab = 1 }
            }
        }
        if (tab == 0) SelectArea(onDownloaded = { tab = 1 }) else DownloadsList()
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text, fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color(0xFF222222) else Color(0xFF777777)
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(36.dp).height(3.dp).background(if (selected) Color(0xFF222222) else Color.Transparent))
    }
}

@Composable
private fun SelectArea(onDownloaded: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val online by NetworkMonitor.isOnline
    val t by TelemetryManager.telemetry

    val mapType by MapConfig.mapType
    val mapState = rememberMapState()
    val viewport = rememberMapViewportState {
        setCameraOptions {
            zoom(15.0)
            if (!t.latitude.isNaN() && t.latitude != 0.0) center(Point.fromLngLat(t.longitude, t.latitude))
            else center(Point.fromLngLat(-79.17, -0.25)) // Ecuador
        }
    }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var corners by remember { mutableStateOf<Pair<Point, Point>?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var cancelables by remember { mutableStateOf<List<Cancelable>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    // Recalcular las esquinas del recuadro cuando se mueve el mapa
    val camera = viewport.cameraState
    LaunchedEffect(camera, mapSize) {
        if (mapSize.width == 0) return@LaunchedEffect
        val w = mapSize.width.toDouble()
        val h = mapSize.height.toDouble()
        val nw = mapState.coordinateForPixel(ScreenCoordinate(w * SEL_LEFT, h * SEL_TOP))
        val se = mapState.coordinateForPixel(ScreenCoordinate(w * SEL_RIGHT, h * SEL_BOTTOM))
        corners = Point.fromLngLat(nw.longitude(), se.latitude()) to Point.fromLngLat(se.longitude(), nw.latitude())
    }
    val sizeM = corners?.let { (sw, ne) ->
        val wM = PolygonGeometry.distanceMeters(sw.latitude(), sw.longitude(), sw.latitude(), ne.longitude())
        val hM = PolygonGeometry.distanceMeters(sw.latitude(), sw.longitude(), ne.latitude(), sw.longitude())
        wM to hM
    }
    val areaKm2 = sizeM?.let { it.first * it.second / 1e6 } ?: 0.0
    val tooBig = areaKm2 > MAX_AREA_KM2

    DisposableEffect(Unit) { onDispose { cancelables.forEach { it.cancel() } } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { mapSize = it }) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                composeMapInitOptions = remember { ComposeMapInitOptions(density, textureView = true) },
                compass = { },
                scaleBar = { },
                mapViewportState = viewport,
                mapState = mapState,
                style = { MapStyle(style = mapType.styleUri) }
            )
            // Oscurecer fuera del recuadro y marco blanco
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val l = maxWidth * SEL_LEFT
                val r = maxWidth * (1 - SEL_RIGHT)
                val top = maxHeight * SEL_TOP
                val bottom = maxHeight * (1 - SEL_BOTTOM)
                val shade = Color(0x55000000)
                Box(Modifier.align(Alignment.TopStart).fillMaxHeight().width(l).background(shade))
                Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(r).background(shade))
                Box(Modifier.align(Alignment.TopCenter).padding(start = l, end = r).fillMaxWidth().height(top).background(shade))
                Box(Modifier.align(Alignment.BottomCenter).padding(start = l, end = r).fillMaxWidth().height(bottom).background(shade))
                Box(
                    Modifier.padding(start = l, end = r, top = top, bottom = bottom).fillMaxSize()
                        .border(2.dp, Color.White)
                )
            }
        }

        // Selector de tipo de mapa (se descargan ambos tipos para el área)
        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xE6FFFFFF))
        ) {
            MapConfig.MapType.entries.forEach { type ->
                val sel = type == mapType
                Text(
                    type.label,
                    modifier = Modifier
                        .clickable { MapConfig.setMapType(context, type) }
                        .background(if (sel) Color(0xFF222222) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (sel) Color.White else Color(0xFF444444), fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }

        // Píldora inferior: instrucción + botón de descarga (o progreso)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val p = progress
            Text(
                when {
                    p != null -> "Descargando… ${(p * 100).toInt()} %"
                    message != null -> message!!
                    !online -> "Conéctate a internet para descargar"
                    tooBig -> "Área muy grande: acerca el mapa"
                    else -> "Mover el mapa para seleccionar el área de descarga"
                },
                color = Color(0xFF555555), fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFE5E5E5)))
            val enabled = p == null && online && !tooBig && corners != null
            IconButton(
                enabled = p != null || enabled,
                onClick = {
                    if (p != null) {
                        cancelables.forEach { it.cancel() }
                        cancelables = emptyList()
                        progress = null
                        message = "Descarga cancelada"
                        return@IconButton
                    }
                    val (sw, ne) = corners ?: return@IconButton
                    message = null
                    progress = 0f
                    cancelables = OfflineMaps.download(
                        sw = sw, ne = ne,
                        widthM = sizeM?.first?.toInt() ?: 0, heightM = sizeM?.second?.toInt() ?: 0,
                        pixelRatio = density,
                        onProgress = { progress = it },
                        onDone = { error ->
                            scope.launch {
                                progress = null
                                cancelables = emptyList()
                                if (error == null) {
                                    message = "Área descargada"
                                    onDownloaded()
                                } else message = error
                            }
                        }
                    )
                }
            ) {
                Icon(
                    if (p != null) Icons.Filled.Close else Icons.Filled.Download,
                    contentDescription = if (p != null) "Cancelar" else "Descargar",
                    tint = if (p != null || enabled) Color(0xFF222222) else Color(0xFFBBBBBB)
                )
            }
        }

        // Franja inferior: espacio disponible
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp).background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            val free = remember { StatFs(context.filesDir.path).availableBytes }
            Text("Disponible %.2f GB".format(free / 1_073_741_824.0), color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DownloadsList() {
    var regions by remember { mutableStateOf<List<OfflineMaps.Region>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { OfflineMaps.list { regions = it } }

    val list = regions
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No hay mapas descargados", color = Color(0xFF888888))
        }
        else -> LazyColumn(
            Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = Color(0xFF00704A))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Área de %.1f × %.1f km".format(r.widthM / 1000.0, r.heightM / 1000.0),
                            fontWeight = FontWeight.SemiBold, color = Color(0xFF222222)
                        )
                        Text(
                            "${formatFullDateTime(r.createdAt)} · %.0f MB".format(r.sizeBytes / 1_048_576.0),
                            color = Color(0xFF777777), fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = { OfflineMaps.delete(r.id); refresh++ }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Eliminar", tint = Color(0xFF888888))
                    }
                }
            }
        }
    }
}
