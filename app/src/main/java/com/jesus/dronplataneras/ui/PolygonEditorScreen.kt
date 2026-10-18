package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.map.AgroMap
import com.jesus.dronplataneras.map.MapLayers
import com.jesus.dronplataneras.map.NetworkMonitor
import com.jesus.dronplataneras.map.PhoneLocation
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.survey.SurveySession
import com.jesus.dronplataneras.survey.toLatLon
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.launch

/**
 * Mapa del perímetro del lote. Los vértices se fijan desde la cámara (miniatura abajo a la izquierda): el operador vuela a
 * cada esquina y la marca. Aquí se ve el polígono, se centra el mapa (dron o teléfono) y "Confirmar Perímetro" valida y
 * guarda el lote en Room.
 */
@Composable
fun PolygonEditorScreen(
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
    onConfirmed: (loteId: Long) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { AgroRepository.get(context) }
    val scope = rememberCoroutineScope()

    val telemetry by TelemetryManager.telemetry
    val connected by DJIConnectionManager.isConnected
    val online by NetworkMonitor.isOnline
    val vertices = SurveySession.vertices
    val editingId by SurveySession.editingLoteId

    var followDrone by remember { mutableStateOf(vertices.isEmpty()) }
    var showClear by remember { mutableStateOf(false) }
    // Ubicación del teléfono: se busca al pulsar "Mi ubicación"; locateKey le dice al mapa que se centre
    var me by remember { mutableStateOf<LatLon?>(null) }
    var locateKey by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val hasPosition = connected && !telemetry.latitude.isNaN() && (telemetry.latitude != 0.0 || telemetry.longitude != 0.0)
    val drone = if (hasPosition) LatLon(telemetry.latitude, telemetry.longitude) else null
    val home = if (telemetry.homeLocationSet) LatLon(telemetry.homeLatitude, telemetry.homeLongitude) else null
    val areaHa = PolygonGeometry.areaSquareMeters(vertices) / 10_000
    val perimeter = PolygonGeometry.perimeterMeters(vertices)
    val validation = SurveySession.validationError()

    Column(Modifier.fillMaxSize().background(PageBg)) {
        AgroScanTopBar(onBack = onBack)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            AgroMap(
                layers = MapLayers(
                    polygon = vertices.map { it.toLatLon() },
                    polygonClosed = vertices.size >= 3,
                    drone = drone,
                    home = home,
                    me = me,
                    highlightLastVertex = true
                ),
                modifier = Modifier.fillMaxSize(),
                fitKey = editingId,
                followDrone = followDrone,
                locateKey = locateKey,
                fallbackCenter = drone ?: home,
                // la mira de la derecha busca dónde está el operador (sirve aunque el dron aún no tenga GPS)
                onLocateMe = {
                    if (!locating) {
                        locating = true
                        followDrone = false
                        AppStatus.message.value = "Buscando tu ubicación…"
                        PhoneLocation.find(context) { result ->
                            locating = false
                            result.position?.let { me = it; locateKey++ }
                            result.error?.let { AppStatus.message.value = it }
                        }
                    }
                }
            )

            // Arriba izquierda: área estimada + seguir dron + estado de red
            Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgroCard(Modifier.width(220.dp), padding = 12.dp, elevated = true) {
                    SectionLabel("Área estimada")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.2f".format(areaHa), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextStrong)
                        Spacer(Modifier.width(6.dp))
                        Text("Hectáreas", style = MaterialTheme.typography.titleMedium, color = TextMuted, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text(
                        "%,.0f m² · ${vertices.size} vértices · perímetro %.0f m".format(areaHa * 10_000, perimeter),
                        style = MaterialTheme.typography.labelSmall, color = TextMuted
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (validation == null) Icons.Filled.CheckCircle else Icons.Filled.Error, contentDescription = null,
                            tint = if (validation == null) BrandGreen else WarnAmber, modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            validation ?: "Polígono válido",
                            color = if (validation == null) BrandGreen else Color(0xFF92400E),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                MapChip(Icons.Filled.Flight, if (followDrone) "Siguiendo dron" else "Seguir dron", active = followDrone) {
                    followDrone = !followDrone
                }
                if (vertices.isNotEmpty() || editingId != null) {
                    MapChip(Icons.Filled.DeleteOutline, "Limpiar", danger = true) { showClear = true }
                }
                if (!online) {
                    MapChip(Icons.Filled.CloudOff, "Sin internet · caché y cuadrícula") {}
                }
            }

            // Miniatura de la cámara, como en DJI Fly: se arrastra a cualquier esquina y, al tocarla, se abre la cámara
            DraggableThumbnail(
                containerWidth = maxWidth, containerHeight = maxHeight, width = 180.dp, height = 101.dp,
                endInset = 68.dp   // deja libres los botones redondos del mapa
            ) {
                if (connected) CameraPreview(Modifier.fillMaxSize())
                else Text("Sin video", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                // Capa transparente encima: el TextureView no debe recibir el toque
                Box(Modifier.fillMaxSize().clickable(onClick = onOpenCamera))
                Row(
                    Modifier.align(Alignment.TopStart).background(Color(0x99000000), RoundedCornerShape(bottomEnd = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("CÁMARA", color = Color.White, fontSize = 9.sp)
                }
            }

            // Qué hacer ahora, según el estado
            val hint = when {
                vertices.size >= 3 -> null
                drone == null && vertices.isEmpty() ->
                    "Esperando el GPS del dron. Mientras, pulsa la mira (◎) de la derecha para ver dónde estás. " +
                        "Luego vuela a cada esquina del lote y fíjala desde la cámara (toca la miniatura de video)."
                else -> "Vuela el dron a cada esquina del lote y fíjala desde la cámara (toca la miniatura de video). Hacen falta al menos 3."
            }
            hint?.let {
                AgroCard(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp).widthIn(max = 380.dp),
                    padding = 10.dp, elevated = true
                ) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextStrong)
                }
            }

            // Abajo centro: confirmar
            Button(
                onClick = { showConfirm = true },
                enabled = validation == null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).height(52.dp).shadow(8.dp, RoundedCornerShape(50)),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                contentPadding = PaddingValues(horizontal = 28.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Confirmar Perímetro", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            StatusToast(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("¿Limpiar perímetro?") },
            text = { Text("Se borrarán los ${vertices.size} vértices fijados en esta sesión. Los lotes ya guardados no se modifican.") },
            confirmButton = {
                TextButton(onClick = { SurveySession.clear(); showClear = false }) { Text("Limpiar", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancelar") } }
        )
    }

    if (showConfirm) {
        var name by remember {
            mutableStateOf(
                SurveySession.editingLoteName.value.ifBlank {
                    "Lote " + formatShortDateTime(System.currentTimeMillis())
                }
            )
        }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) showConfirm = false },
            title = { Text("Guardar lote") },
            text = {
                Column {
                    Text("%.2f ha · %d vértices".format(areaHa, vertices.size), color = TextMuted)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre del lote") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !saving,
                    onClick = {
                        saving = true
                        val pts = vertices.map { it.toLatLon() }
                        val editId = editingId
                        scope.launch {
                            try {
                                val id = if (editId != null) {
                                    val existing = repo.getLote(editId)?.lote ?: error("El lote ya no existe")
                                    repo.actualizarLote(existing.copy(nombre = name.trim()), pts)
                                    editId
                                } else repo.guardarLote(name, pts)
                                SurveySession.clear()
                                showConfirm = false
                                AppStatus.message.value = "Lote \"${name.trim()}\" guardado"
                                onConfirmed(id)
                            } catch (e: Exception) {
                                AppStatus.message.value = "No se pudo guardar: ${e.message}"
                                saving = false
                            }
                        }
                    }
                ) { Text("Guardar y planificar") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { showConfirm = false }) { Text("Cancelar") } }
        )
    }
}

/**
 * Botón en forma de chip sobre el mapa. [danger] lo pinta en rojo (acción que borra).
 */
@Composable
internal fun MapChip(
    icon: ImageVector,
    text: String,
    active: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val content = when {
        active -> Color.White
        danger -> DangerRed
        else -> TextStrong
    }
    Row(
        Modifier
            .shadow(3.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(if (active) BrandGreen else Color.White)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = content, fontWeight = FontWeight.Medium)
    }
}
