package com.jesus.dronplataneras.ui

import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.data.EstadoMision
import com.jesus.dronplataneras.data.MisionCompleta
import com.jesus.dronplataneras.data.perimetro
import com.jesus.dronplataneras.map.NetworkMonitor
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.telemetry.DroneInfo
import kotlinx.coroutines.launch

private enum class Filtro(val label: String) { TODAS("Todas"), PENDIENTES("Pendientes"), COMPLETADAS("Completadas") }

/** Pantalla 4: Historial de rutas guardadas (consulta reactiva a Room). */
@Composable
fun SavedMissionsScreen(
    onBack: () -> Unit,
    onNewMission: () -> Unit,
    onEdit: (loteId: Long, misionId: Long) -> Unit,
    onLoad: (misionId: Long) -> Unit,
    onOfflineMaps: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { AgroRepository.get(context) }
    val scope = rememberCoroutineScope()
    val misiones by remember { repo.observeMisiones() }.collectAsState(initial = emptyList())
    var filtro by remember { mutableStateOf(Filtro.TODAS) }
    var toDelete by remember { mutableStateOf<MisionCompleta?>(null) }

    val pendientes = misiones.count { it.mision.estado != EstadoMision.COMPLETADO }
    val completadas = misiones.size - pendientes
    val visibles = when (filtro) {
        Filtro.TODAS -> misiones
        Filtro.PENDIENTES -> misiones.filter { it.mision.estado != EstadoMision.COMPLETADO }
        Filtro.COMPLETADAS -> misiones.filter { it.mision.estado == EstadoMision.COMPLETADO }
    }
    val newestId = misiones.firstOrNull()?.mision?.id

    Column(Modifier.fillMaxSize().background(Color.White)) {
        AgroScanTopBar(onBack = onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rutas Guardadas", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = TextStrong)
                        Text("Selecciona una misión para cargarla en el dron", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!wide) {
                        SecondaryButton("Mapas offline", Icons.Filled.Map, onClick = onOfflineMaps)
                        Spacer(Modifier.width(8.dp))
                    }
                    PrimaryButton("Nueva Misión", Icons.Filled.Add, onClick = onNewMission)
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filtro.entries.forEach { f ->
                        val n = when (f) { Filtro.TODAS -> misiones.size; Filtro.PENDIENTES -> pendientes; Filtro.COMPLETADAS -> completadas }
                        FilterChip(
                            selected = filtro == f,
                            onClick = { filtro = f },
                            label = { Text("${f.label} ($n)", fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandGreen, selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp), color = PanelBorder)
                if (visibles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (misiones.isEmpty()) "Aún no hay misiones. Crea una con \"Nueva Misión\"." else "No hay misiones en este filtro.",
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.background(PageBg).fillMaxSize()
                    ) {
                        items(visibles, key = { it.mision.id }) { m ->
                            MissionRow(
                                m = m,
                                highlighted = m.mision.id == newestId,
                                onEdit = { onEdit(m.mision.loteId, m.mision.id) },
                                onLoad = { onLoad(m.mision.id) },
                                onDelete = { toDelete = m }
                            )
                        }
                    }
                }
            }

            // Panel lateral: dispositivo y almacenamiento (solo en pantallas anchas)
            if (wide) SyncPanel(misiones.size, onOfflineMaps, onBack)
        }
        }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Eliminar \"${m.mision.nombre}\"?") },
            text = { Text("Se borra la misión y su ruta. El lote \"${m.lote.lote.nombre}\" se conserva.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.eliminarMision(m.mision) }
                    toDelete = null
                }) { Text("Eliminar", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun MissionRow(m: MisionCompleta, highlighted: Boolean, onEdit: () -> Unit, onLoad: () -> Unit, onDelete: () -> Unit) {
    val estado = m.mision.estado
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) Color(0xFF1DB98A) else PanelBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PolygonThumb(m, Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.mision.nombre, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextStrong)
                Spacer(Modifier.width(8.dp))
                val (label, color) = when {
                    estado == EstadoMision.COMPLETADO -> "Completada" to BrandGreen
                    estado == EstadoMision.BORRADOR -> "Borrador" to Color(0xFF0369A1)
                    highlighted -> "Reciente" to BrandGreen
                    else -> "Lista para volar" to BrandGreen
                }
                Text(
                    label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.1f))
                        .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text("${m.lote.lote.nombre} · ${friendlyDate(m.mision.fechaActualizacion)}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTag("Superficie: %.1f ha".format(m.lote.lote.areaEstimadaHa))
                InfoTag("Duración: %.0f min".format(m.mision.duracionEstimadaMin))
                InfoTag("Altura: %.0f m".format(m.parametros.alturaMetros))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(if (estado == EstadoMision.COMPLETADO) "Ver Detalles" else "Editar", onClick = onEdit)
            when (estado) {
                EstadoMision.COMPLETADO -> SecondaryButton("Re-volar", Icons.Filled.Replay, onClick = onLoad)
                EstadoMision.SINCRONIZADO -> PrimaryButton("Cargar en Dron", Icons.Filled.FileUpload, color = if (highlighted) BrandGreen else NavyButton, onClick = onLoad)
                EstadoMision.BORRADOR -> PrimaryButton("Completar ruta", null, color = NavyButton, onClick = onEdit)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Eliminar", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun InfoTag(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextStrong,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF1F5F9)).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** Miniatura del lote dibujada a partir de sus vértices (sin mapa: funciona offline). */
@Composable
private fun PolygonThumb(m: MisionCompleta, modifier: Modifier) {
    val pts = m.lote.perimetro()
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1B2D)), contentAlignment = Alignment.Center) {
        if (pts.size < 3) {
            Icon(Icons.Filled.Map, contentDescription = null, tint = Color(0xFF1DB98A))
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val f = LocalFrame(pts.first())
            val xy = pts.map { f.toXY(it) }
            val minX = xy.minOf { it.x }; val maxX = xy.maxOf { it.x }
            val minY = xy.minOf { it.y }; val maxY = xy.maxOf { it.y }
            val span = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1.0)
            val s = minOf(size.width, size.height) / span.toFloat()
            val ox = (size.width - (maxX - minX).toFloat() * s) / 2
            val oy = (size.height - (maxY - minY).toFloat() * s) / 2
            fun p(i: Int) = Offset(ox + (xy[i].x - minX).toFloat() * s, oy + (maxY - xy[i].y).toFloat() * s)
            val path = Path().apply {
                moveTo(p(0).x, p(0).y)
                for (i in 1 until xy.size) lineTo(p(i).x, p(i).y)
                close()
            }
            drawPath(path, Color(0x331DB98A))
            drawPath(path, Color(0xFF1DB98A), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun SyncPanel(totalMisiones: Int, onOfflineMaps: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by DJIConnectionManager.isConnected
    val product by DroneInfo.productName
    val online by NetworkMonitor.isOnline
    val stat = remember { StatFs(context.filesDir.path) }
    val freePct = (stat.availableBytes.toDouble() / stat.totalBytes.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    Column(
        Modifier.width(300.dp).fillMaxHeight().background(Color.White)
            .border(1.dp, PanelBorder).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dispositivo & Sincronización", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextStrong)
        HorizontalDivider(color = PanelBorder)
        AgroCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Estado del Dron", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Pill(if (connected) "Conectado" else "Desconectado", if (connected) BrandGreen else DangerRed)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (connected) DroneInfo.prettyName(product).ifBlank { "DJI Mini 3" } else "Sin aeronave",
                fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextStrong
            )
        }
        AgroCard {
            Row {
                Text("Memoria Local", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("%.0f%% libre".format(freePct * 100), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (1 - freePct).toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = BrandGreen, trackColor = Color(0xFFE2E8F0)
            )
            Spacer(Modifier.height(6.dp))
            Text("$totalMisiones misiones disponibles sin conexión", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BrandGreenSoft).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (online) "Datos guardados localmente" else "Modo sin conexión · todo local",
                color = BrandGreen, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall
            )
        }
        SecondaryButton("Mapas sin conexión", Icons.Filled.Map, Modifier.fillMaxWidth(), onClick = onOfflineMaps)
        Spacer(Modifier.weight(1f))
        SecondaryButton("Volver al Monitor", Icons.AutoMirrored.Filled.ArrowBack, Modifier.fillMaxWidth(), onClick = onBack)
    }
}
