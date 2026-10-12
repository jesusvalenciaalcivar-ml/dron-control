package com.jesus.dronplataneras.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.EditLocation
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.data.EstadoMision
import com.jesus.dronplataneras.data.LoteConVertices
import com.jesus.dronplataneras.data.ParametrosVueloEntity
import com.jesus.dronplataneras.data.perimetro
import com.jesus.dronplataneras.map.AgroMap
import com.jesus.dronplataneras.map.MapLayers
import com.jesus.dronplataneras.mission.CaptureMode
import com.jesus.dronplataneras.mission.SortiePlanner
import com.jesus.dronplataneras.mission.SplitResult
import com.jesus.dronplataneras.mission.WaypointPlan
import com.jesus.dronplataneras.planning.CoveragePlanner
import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.planning.OverlapPreset
import com.jesus.dronplataneras.planning.Photogrammetry
import com.jesus.dronplataneras.planning.RouteChecks
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.survey.SurveySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Pantalla 2: Planificación de Vuelo.
 * Nombre de la ruta, puntos del perímetro, parámetros (altura, traslape, margen interno) y una
 * vista previa del zigzag. La ruta se genera aquí mismo (planificador del móvil, sin internet); también
 * se puede importar una ruta ya calculada (JSON).
 */
@Composable
fun FlightPlanningScreen(
    loteId: Long,
    misionId: Long?,
    onBack: () -> Unit,
    onEditPerimeterWithDrone: () -> Unit,
    onContinue: (misionId: Long) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { AgroRepository.get(context) }
    val scope = rememberCoroutineScope()

    var lote by remember { mutableStateOf<LoteConVertices?>(null) }
    var currentMisionId by remember { mutableStateOf(misionId) }
    val puntos = remember { mutableStateListOf<LatLon>() }
    var puntosDirty by remember { mutableStateOf(false) }

    var nombre by remember { mutableStateOf("") }
    var altura by remember { mutableFloatStateOf(60f) }
    var traslape by remember { mutableFloatStateOf(70f) }
    var margen by remember { mutableFloatStateOf(5f) }
    var copa by remember { mutableFloatStateOf(4f) }
    var frontal by remember { mutableFloatStateOf(75f) }
    var captura by remember { mutableStateOf(CaptureMode.VIDEO) }
    var loaded by remember { mutableStateOf(false) }
    var mapExpanded by remember { mutableStateOf(false) }

    // Carga inicial: lote + misión existente (la indicada o la última del lote)
    LaunchedEffect(loteId, misionId) {
        val l = repo.getLote(loteId)
        lote = l
        puntos.clear()
        l?.let { puntos.addAll(it.perimetro()) }
        val m = (misionId ?: repo.ultimaMisionDeLote(loteId)?.id)?.let { repo.getMision(it) }
        if (m != null) {
            currentMisionId = m.mision.id
            nombre = m.mision.nombre
            altura = m.parametros.alturaMetros.toFloat()
            traslape = m.parametros.traslapePorcentaje.toFloat()
            margen = m.parametros.margenInternoMetros.toFloat()
            copa = m.parametros.alturaCopaMetros.toFloat()
            frontal = m.parametros.traslapeFrontalPorcentaje.toFloat()
            captura = m.parametros.modoCaptura
        } else {
            nombre = l?.lote?.nombre ?: ""
        }
        loaded = true
    }

    val misionFlow = remember(currentMisionId) { currentMisionId?.let { repo.observeMision(it) } ?: flowOf(null) }
    val mision by misionFlow.collectAsState(initial = null)

    // Vista previa (se recalcula al mover cualquier control)
    val profile = DroneProfile.MINI_3
    // El traslape se calcula en la copa del cultivo, que está más cerca de la cámara que el suelo
    val lineSpacing = Photogrammetry.lineSpacingM(altura.toDouble(), traslape.toDouble(), profile, captura, copa.toDouble())
    val preview = remember(puntos.toList(), altura, traslape, margen, copa, captura) {
        CoveragePlanner.plan(puntos.toList(), margen.toDouble(), lineSpacing)
    }
    val estimate = Photogrammetry.estimate(
        pathLengthM = preview?.pathLengthM ?: 0.0,
        passes = preview?.passes ?: 0,
        altitudeM = altura.toDouble(),
        overlapPct = traslape.toDouble(),
        p = profile,
        mode = captura,
        canopyHeightM = copa.toDouble(),
        frontOverlapPct = frontal.toDouble()
    )
    // Cuántas baterías pide la ruta (se recalcula con el punto de origen real en la verificación)
    val batteries = remember(preview, estimate.speedMs, nombre, altura) {
        preview?.let {
            val previewPlan = RouteChecks.previewToPlan(nombre, it, altura.toDouble(), estimate)
            (SortiePlanner.split(previewPlan, 0, null, estimate.speedMs, profile) as? SplitResult.Ok)?.sorties?.size
        }
    }
    val areaHa = PolygonGeometry.areaSquareMeters(puntos) / 10_000
    val importedPlan = remember(mision?.mision?.rutaWaypointsJson) {
        mision?.mision?.rutaWaypointsJson?.let { runCatching { WaypointPlan.parse(it) }.getOrNull() }
    }

    fun polygonError(): String? = when {
        puntos.size < 3 -> "El perímetro necesita al menos 3 puntos"
        PolygonGeometry.isSelfIntersecting(puntos) -> "El perímetro se cruza a sí mismo"
        nombre.isBlank() -> "Escribe un nombre para la ruta"
        else -> null
    }

    /** Guarda lote (si se editaron puntos) + misión. Devuelve el id de la misión o null si hubo error. */
    suspend fun save(): Long? {
        polygonError()?.let { AppStatus.message.value = it; return null }
        return try {
            val l = lote ?: error("Lote no encontrado")
            val perimetroCambio = puntosDirty
            if (perimetroCambio) {
                repo.actualizarLote(l.lote, puntos.toList())
                lote = repo.getLote(loteId)
                puntosDirty = false
            }
            val id = repo.guardarPlanificacion(
                currentMisionId, nombre, loteId,
                ParametrosVueloEntity(
                    alturaMetros = altura.toDouble(), traslapePorcentaje = traslape.toDouble(), margenInternoMetros = margen.toDouble(),
                    alturaCopaMetros = copa.toDouble(), traslapeFrontalPorcentaje = frontal.toDouble(), modoCaptura = captura
                ),
                estimate.durationMin, estimate.photos,
                invalidarRuta = perimetroCambio
            )
            currentMisionId = id
            id
        } catch (e: Exception) {
            AppStatus.message.value = "No se pudo guardar: ${e.message}"
            null
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: error("No se pudo leer el archivo")
                var plan = WaypointPlan.parse(text, altura.toDouble())
                val outside = RouteChecks.maxDistanceOutsideM(puntos, plan.waypoints)
                require(outside <= RouteChecks.MAX_OUTSIDE_M) {
                    "La ruta sale %.0f m fuera del lote: ¿es de otro lote?".format(outside)
                }
                val length = plan.lengthM()
                // pasadas = giros entre ellas; si la ruta no trae marcas de tramo se estima por pares de waypoints
                val passes = plan.waypoints.count { !it.surveyLeg }.takeIf { it > 0 } ?: (plan.waypoints.size / 2)
                val est = Photogrammetry.estimate(
                    length, passes, plan.altitudeM, traslape.toDouble(), 0.0, profile,
                    plan.captureMode, copa.toDouble(), frontal.toDouble()
                )
                if (plan.photoEveryM <= 0 && plan.waypoints.none { it.takePhoto }) {
                    plan = plan.copy(photoEveryM = est.photoSpacingM)
                }
                val id = save() ?: return@launch
                repo.guardarRuta(id, plan, est.photos, est.durationMin)
                AppStatus.message.value = "Ruta importada: ${plan.waypoints.size} waypoints, %.1f km".format(length / 1000)
            } catch (e: Exception) {
                AppStatus.message.value = "Ruta no válida: ${e.message}"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                save() ?: return@launch
                val l = repo.getLote(loteId) ?: error("Lote no encontrado")
                val params = currentMisionId?.let { repo.getMision(it) }?.parametros
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { repo.exportarLoteGeoJson(l, params, it) }
                        ?: error("No se pudo escribir")
                }
                AppStatus.message.value = "Lote exportado (GeoJSON)"
            } catch (e: Exception) {
                AppStatus.message.value = "Error al exportar: ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(PageBg)) {
        AgroScanTopBar(onBack = onBack)

        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Panel más angosto en teléfonos; en tablets 340 dp
        val panelWidth = if (maxWidth < 900.dp) (maxWidth * 0.42f).coerceIn(260.dp, 340.dp) else 340.dp
        Row(Modifier.fillMaxSize()) {
            // ---------------- Panel izquierdo (se oculta con "Ampliar mapa")
            if (!mapExpanded) Column(
                Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(Color.White)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SectionLabel("Ingresar nombre de la ruta")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nombre, onValueChange = { nombre = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Finca Sanchez - Sector 1") },
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "Lote: ${lote?.lote?.nombre ?: "…"} · %.2f ha".format(areaHa),
                    style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(top = 4.dp)
                )

                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = PanelBorder)
                ReferencePointsEditor(
                    puntos = puntos,
                    onChanged = { puntosDirty = true }
                )
                TextButton(onClick = {
                    lote?.let { SurveySession.loadForEdit(it.lote.id, it.lote.nombre, puntos.toList()) }
                    onEditPerimeterWithDrone()
                }) {
                    Icon(Icons.Filled.EditLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Editar perímetro con el dron")
                }

                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = PanelBorder)
                SectionLabel("Parámetros de vuelo")
                Spacer(Modifier.height(10.dp))
                ParamSlider("Altura", altura, "%.0f m", 20f..120f, 19) { altura = it }
                Text("Traslape", fontWeight = FontWeight.SemiBold, color = TextStrong)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    OverlapPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = OverlapPreset.match(traslape.toDouble(), frontal.toDouble()) == preset,
                            onClick = { traslape = preset.sidePct.toFloat(); frontal = preset.frontPct.toFloat() },
                            label = { Text(preset.label) }
                        )
                    }
                }
                OverlapPreset.match(traslape.toDouble(), frontal.toDouble())?.let {
                    Text(it.hint, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                ParamSlider("Traslape lateral", traslape, "%.0f %%", 50f..90f, 7) { traslape = it }
                ParamSlider("Traslape frontal", frontal, "%.0f %%", 60f..95f, 6) { frontal = it }
                Text(
                    "Cada punto del terreno sale en ≈ %.1f pasadas (separación entre pasadas %.0f m).".format(
                        Photogrammetry.timesCovered(traslape.toDouble()), estimate.lineSpacingM
                    ),
                    style = MaterialTheme.typography.labelSmall, color = TextMuted
                )
                ParamSlider("Margen interno", margen, "%.0f m", 0f..30f, 29) { margen = it }
                ParamSlider("Altura del cultivo", copa, "%.0f m", 0f..10f, 9) { copa = it }
                Spacer(Modifier.height(4.dp))
                Text("Captura", fontWeight = FontWeight.SemiBold, color = TextStrong)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    FilterChip(captura == CaptureMode.VIDEO, { captura = CaptureMode.VIDEO }, { Text("Video") })
                    FilterChip(captura == CaptureMode.PHOTO, { captura = CaptureMode.PHOTO }, { Text("Fotos") })
                }
                Text(
                    "GSD %.1f cm/px · pasadas cada %.0f m · %.1f m/s".format(
                        estimate.gsdCmPx, estimate.lineSpacingM, estimate.speedMs
                    ),
                    style = MaterialTheme.typography.labelSmall, color = TextMuted
                )
                Text(
                    if (captura == CaptureMode.VIDEO)
                        "Video 4K ≈ %.0f min (%.1f GB). Extraer un cuadro cada %.1f m (%.1f s) al procesar.".format(
                            estimate.videoMinutes, estimate.videoGb, estimate.photoSpacingM, estimate.frameIntervalS
                        )
                    else "≈ ${estimate.photos} fotos, una cada %.1f m".format(estimate.photoSpacingM),
                    style = MaterialTheme.typography.labelSmall, color = TextMuted
                )

                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = PanelBorder)
                SectionLabel("Ruta de vuelo")
                Spacer(Modifier.height(6.dp))
                val estado = mision?.mision?.estado
                Text(
                    when {
                        importedPlan != null -> "Lista: ${importedPlan.waypoints.size} waypoints · %.1f km".format(importedPlan.lengthM() / 1000)
                        currentMisionId == null -> "Ajusta los parámetros y genera la ruta con el planificador."
                        else -> "Sin ruta. Genérala con el planificador o importa un JSON."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importedPlan != null) BrandGreen else TextMuted
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Guardar misión", Icons.Filled.Save, Modifier.fillMaxWidth()) {
                    scope.launch { save()?.let { AppStatus.message.value = "Misión guardada" } }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Generar ruta de vuelo", Icons.Filled.Timeline, Modifier.fillMaxWidth(), enabled = preview != null) {
                    val p = preview ?: return@PrimaryButton
                    scope.launch {
                        val id = save() ?: return@launch
                        val plan = RouteChecks.previewToPlan(nombre, p, altura.toDouble(), estimate)
                        repo.guardarRuta(id, plan, estimate.photos, estimate.durationMin)
                        AppStatus.message.value = "Ruta generada: ${plan.waypoints.size} waypoints, %.1f km".format(plan.lengthM() / 1000)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Exportar lote", Icons.Filled.FileUpload, Modifier.weight(1f)) {
                        val safe = (lote?.lote?.nombre ?: "lote").replace(Regex("[^A-Za-z0-9_-]+"), "_")
                        exportLauncher.launch("$safe.geojson")
                    }
                    SecondaryButton("Importar ruta", Icons.Filled.FileDownload, Modifier.weight(1f)) {
                        importLauncher.launch(arrayOf("application/json", "application/geo+json", "application/octet-stream", "text/plain"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    "Continuar a verificación", Icons.AutoMirrored.Filled.ArrowForward, Modifier.fillMaxWidth(),
                    color = NavyButton,
                    enabled = importedPlan != null && estado != null
                ) {
                    scope.launch {
                        val id = save() ?: return@launch
                        if (repo.getMision(id)?.mision?.rutaWaypointsJson == null) {
                            AppStatus.message.value = "Cambiaron los parámetros o el perímetro: vuelve a generar o importar la ruta"
                        } else onContinue(id)
                    }
                }
            }

            // ---------------- Mapa con vista previa
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                AgroMap(
                    layers = MapLayers(
                        polygon = puntos.toList(),
                        inner = preview?.inner ?: emptyList(),
                        path = importedPlan?.waypoints?.map { LatLon(it.latitude, it.longitude) } ?: preview?.path ?: emptyList()
                    ),
                    modifier = Modifier.fillMaxSize(),
                    fitKey = loaded
                )
                // Arriba izquierda: ampliar/reducir mapa + estado de la ruta
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MapChip(
                        if (mapExpanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                        if (mapExpanded) "Mostrar panel" else "Ampliar mapa"
                    ) { mapExpanded = !mapExpanded }
                    if (importedPlan != null) {
                        Text(
                            if (mision?.mision?.estado == EstadoMision.COMPLETADO) "Ruta volada" else "Ruta importada",
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(BrandGreenSoft)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            color = BrandGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                        )
                    }
                }
                // Abajo izquierda: resumen compacto (una línea por dato, nunca tapa el panel)
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = 34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xE6FFFFFF))
                        .border(1.dp, PanelBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    SummaryLine("Área", formatArea(areaHa))
                    SummaryLine("Vuelo", "≈ %.0f min".format(estimate.durationMin))
                    batteries?.let { SummaryLine("Baterías", if (it == 1) "1" else "$it (cambio entre salidas)") }
                }
                if (preview != null && !preview.convex) {
                    Text(
                        "El lote es cóncavo: la ruta puede salirse del lote. Revísala en el mapa; la verificación la bloquea si sale más de ${RouteChecks.MAX_OUTSIDE_M.toInt()} m",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp, start = 80.dp, end = 80.dp)
                            .clip(RoundedCornerShape(8.dp)).background(WarnBg).padding(8.dp),
                        color = Color(0xFF92400E), style = MaterialTheme.typography.bodySmall
                    )
                }
                if (preview == null && puntos.size >= 3) {
                    Text(
                        "El margen interno es mayor que el lote: reduce el margen",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                            .clip(RoundedCornerShape(8.dp)).background(WarnBg).padding(8.dp),
                        color = Color(0xFF92400E), style = MaterialTheme.typography.bodySmall
                    )
                }
                StatusToast(Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
            }
        }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(44.dp))
        Text(value, fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextStrong)
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    format: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold, color = TextStrong)
        Text(format.format(Locale.US, value), fontFamily = MonoFamily, color = BrandGreen)
    }
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = BrandGreen, activeTrackColor = BrandGreen, inactiveTrackColor = Color(0xFFDBE7FB))
    )
}

/** Tabla de vértices con campos Latitud (Y) / Longitud (X) para agregar o corregir a mano. */
@Composable
private fun ReferencePointsEditor(puntos: MutableList<LatLon>, onChanged: () -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Puntos de referencia", Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                val lat = latText.replace(',', '.').toDoubleOrNull()
                val lon = lonText.replace(',', '.').toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    AppStatus.message.value = "Coordenadas no válidas"
                    return@clickable
                }
                val sel = selected
                if (sel != null && sel in puntos.indices) puntos[sel] = LatLon(lat, lon) else puntos.add(LatLon(lat, lon))
                selected = null
                latText = ""
                lonText = ""
                onChanged()
            }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AddLocationAlt, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (selected != null) "Actualizar ${selected!! + 1}" else "Añadir Punto", color = BrandGreen, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CoordField("Latitud (Y)", latText, Modifier.weight(1f)) { latText = it }
        CoordField("Longitud (X)", lonText, Modifier.weight(1f)) { lonText = it }
    }
    Spacer(Modifier.height(8.dp))
    puntos.forEachIndexed { i, p ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected == i) BrandGreenSoft else Color.Transparent)
                .clickable {
                    selected = if (selected == i) null else i
                    latText = if (selected == null) "" else "%.7f".format(Locale.US, p.latitude)
                    lonText = if (selected == null) "" else "%.7f".format(Locale.US, p.longitude)
                }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${i + 1}", fontFamily = MonoFamily, fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(22.dp))
            Text("%.7f".format(Locale.US, p.latitude), fontFamily = MonoFamily, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("%.7f".format(Locale.US, p.longitude), fontFamily = MonoFamily, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Close, contentDescription = "Quitar",
                tint = TextMuted,
                modifier = Modifier.size(16.dp).clickable {
                    puntos.removeAt(i)
                    selected = null
                    onChanged()
                }
            )
        }
    }
}

@Composable
private fun CoordField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextStrong, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            placeholder = { Text("-3.2600000", fontFamily = MonoFamily, fontSize = 12.sp) },
            shape = RoundedCornerShape(10.dp)
        )
    }
}
