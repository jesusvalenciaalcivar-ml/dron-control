package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.data.EstadoVuelo
import com.jesus.dronplataneras.data.VueloCompleto
import com.jesus.dronplataneras.data.perimetro
import com.jesus.dronplataneras.data.salidasVoladas
import com.jesus.dronplataneras.data.yaVoloAlgo
import com.jesus.dronplataneras.map.AgroMap
import com.jesus.dronplataneras.map.MapLayers
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.mission.CaptureMode
import com.jesus.dronplataneras.mission.Fix
import com.jesus.dronplataneras.mission.MissionExecutor
import com.jesus.dronplataneras.mission.MissionRunner
import com.jesus.dronplataneras.mission.Options
import com.jesus.dronplataneras.mission.Preflight
import com.jesus.dronplataneras.mission.Sortie
import com.jesus.dronplataneras.mission.SortiePlanner
import com.jesus.dronplataneras.mission.SplitResult
import com.jesus.dronplataneras.mission.WaypointPlan
import com.jesus.dronplataneras.mission.livePreflightInput
import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.planning.Photogrammetry
import com.jesus.dronplataneras.planning.RouteChecks
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.launch

/**
 * Pantalla 3: Verificación y ejecución.
 *
 * Una misión grande no cabe en una batería: aquí se ve cómo se parte en salidas, se valida el
 * pre-vuelo de la siguiente salida y se lanza. Entre salidas la pantalla recuerda en qué punto de
 * la ruta quedó el vuelo (está guardado en el historial), así que se puede cambiar la batería y
 * continuar, incluso si la app se cerró.
 */
@Composable
fun FlightVerificationScreen(
    misionId: Long,
    onModifyParameters: (loteId: Long, misionId: Long) -> Unit,
    onSaveForLater: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { AgroRepository.get(context) }
    val scope = rememberCoroutineScope()
    val profile = DroneProfile.MINI_3

    val mision by remember(misionId) { repo.observeMision(misionId) }.collectAsState(initial = null)
    val vueloAbierto by remember(misionId) { repo.observeVueloActivo(misionId) }.collectAsState(initial = null)
    val vuelos by remember(misionId) { repo.observeVuelos(misionId) }.collectAsState(initial = emptyList())
    val t by TelemetryManager.telemetry
    val connected by DJIConnectionManager.isConnected
    val flying by DJIConnectionManager.isFlying
    val product by DroneInfo.productName
    val exec by MissionExecutor.status
    var showStartDialog by remember { mutableStateOf(false) }
    var showEndFlightDialog by remember { mutableStateOf(false) }

    val m = mision
    val plan = remember(m?.mision?.rutaWaypointsJson) {
        m?.mision?.rutaWaypointsJson?.let { runCatching { WaypointPlan.parse(it) }.getOrNull() }
    }
    val perimetro = m?.lote?.perimetro() ?: emptyList()

    // Mientras vuela, toda la pantalla es la de ejecución
    if (exec.phase != MissionExecutor.Phase.IDLE) {
        MissionExecutionScreen(perimetro = perimetro, onClose = { MissionRunner.dismiss() })
        return
    }

    // El vuelo abierto solo sirve si la ruta no cambió desde que empezó (si cambió, los índices ya no valen)
    val vueloActivo: VueloCompleto? = remember(vueloAbierto, plan) {
        vueloAbierto?.takeIf { v -> plan != null && runCatching { WaypointPlan.parse(v.vuelo.rutaJson) }.getOrNull() == plan }
    }
    val startIndex = vueloActivo?.vuelo?.siguienteWaypoint ?: 0
    val salidasHechas = vueloActivo?.salidasVoladas() ?: 0
    // Un vuelo cuyos intentos se cancelaron antes de despegar no está "en curso": no se ha volado nada
    val vueloEnMarcha = vueloActivo != null && vueloActivo.yaVoloAlgo()
    val ultimaSalida = vueloActivo?.salidas?.maxByOrNull { it.indice }
    val historial = vuelos.filter { it.salidasVoladas() > 0 }

    val areaHa = PolygonGeometry.areaSquareMeters(perimetro) / 10_000
    val routeLength = plan?.lengthM() ?: 0.0
    val outside = remember(plan, perimetro) { plan?.let { RouteChecks.maxDistanceOutsideM(perimetro, it.waypoints) } ?: 0.0 }
    val speed = plan?.let {
        it.speedMs ?: Photogrammetry.missionSpeedMs(
            it.photoEveryM, profile, it.captureMode, Photogrammetry.gsdCmPx(it.altitudeM, profile, it.captureMode)
        )
    } ?: 0.0

    // Plan de salidas con el punto de origen real (si ya se conoce) y desde donde quedó el vuelo
    val split = remember(plan, startIndex, t.homeLocationSet, t.homeLatitude, t.homeLongitude) {
        if (plan == null || speed <= 0) null
        else SortiePlanner.split(plan, startIndex, if (t.homeLocationSet) LatLon(t.homeLatitude, t.homeLongitude) else null, speed, profile)
    }
    val sorties: List<Sortie> = (split as? SplitResult.Ok)?.sorties ?: emptyList()
    val splitError = (split as? SplitResult.Impossible)?.reason
    val next = sorties.firstOrNull()
    val nextPlan = if (plan != null && next != null) plan.slice(next.from, next.to) else null

    val options = Options(
        requiredBatteryPct = next?.batteryRequiredPct ?: 100,
        routeOutsideM = outside,
        videoSecondsRequired = if (plan?.captureMode == CaptureMode.VIDEO && next != null) next.lengthM / speed else 0.0,
        photosRequired = if (plan?.captureMode == CaptureMode.PHOTO && next != null && plan.photoEveryM > 0) next.lengthM / plan.photoEveryM + 2 else 0.0
    )
    val checks = Preflight.evaluate(livePreflightInput(nextPlan, options), profile)
    val blocking = Preflight.blockers(checks)
    val ready = plan != null && next != null && blocking.isEmpty()
    val batteryRequired = next?.batteryRequiredPct ?: 100
    val batteryLow = connected && t.batteryPercent in 1 until batteryRequired
    val totalEstimateMin = sorties.sumOf { it.flightMinutes }

    Column(Modifier.fillMaxSize().background(PageBg)) {
        AgroScanTopBar(onBack = onBack) {
            val (label, color) = when {
                plan == null -> "SIN RUTA" to DangerRed
                splitError != null -> "RUTA NO VOLABLE" to DangerRed
                next == null -> "MISIÓN COMPLETA" to BrandGreen
                batteryLow -> "PRE-VUELO: CAMBIO BATERÍA" to WarnAmber
                ready -> "LISTO PARA VOLAR" to BrandGreen
                else -> "PRE-VUELO: REVISIÓN" to WarnAmber
            }
            Pill(label, color)
        }

        Row(Modifier.fillMaxSize()) {
            // ---------------- Columna principal
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AgroCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (ready) BrandGreenSoft else WarnBg)
                                .border(2.dp, if (ready) BrandGreen else WarnAmber, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = if (ready) BrandGreen else WarnAmber, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(if (connected && plan != null) "TELEMETRÍA & MISIÓN SINCRONIZADA" else "ESPERANDO DRON", if (connected && plan != null) BrandGreen else TextMuted)
                                Spacer(Modifier.width(8.dp))
                                Text("${plan?.waypoints?.size ?: 0} waypoints", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                when {
                                    next == null && plan != null -> "Misión completa"
                                    ready -> "Plan de Vuelo Verificado"
                                    plan == null -> "Misión sin ruta"
                                    else -> "Plan de vuelo pendiente de revisión"
                                },
                                fontWeight = FontWeight.Bold, fontSize = 22.sp, color = TextStrong
                            )
                            Text(
                                if (plan == null) "Genera la ruta en la pantalla de planificación."
                                else "${m?.mision?.nombre ?: ""}. El ${profile.name} no admite waypoints a bordo: la app guía el vuelo por Virtual Stick, " +
                                    "así que el control remoto y el teléfono deben quedar conectados todo el vuelo.",
                                color = TextMuted, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(PageBg).border(1.dp, PanelBorder, RoundedCornerShape(10.dp)).padding(10.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text("DRON CONECTADO", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text(
                                if (connected) "${DroneInfo.prettyName(product).ifBlank { profile.name }} • RC-N1" else "—",
                                fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                    }
                }

                if (splitError != null) {
                    AgroCard {
                        Text("No se puede volar esta ruta", fontWeight = FontWeight.Bold, color = DangerRed)
                        Text(splitError, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Vuelo a medias: se está entre dos baterías
                if (vueloActivo != null && vueloEnMarcha && next != null) {
                    AgroCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = BrandGreen)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Vuelo en curso · salida ${salidasHechas + 1} de ${salidasHechas + sorties.size}", fontWeight = FontWeight.Bold, color = TextStrong)
                                Text(
                                    "Llevas ${vueloActivo.vuelo.siguienteWaypoint} de ${vueloActivo.vuelo.totalWaypoints} waypoints. " +
                                        "Cambia la batería, comprueba el pre-vuelo y continúa: la ruta se retoma donde quedó.",
                                    color = TextMuted, style = MaterialTheme.typography.bodySmall
                                )
                                ultimaSalida?.motivo?.let {
                                    Text("Última salida: $it", color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                }

                if (batteryLow) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WarnBg)
                            .border(2.dp, WarnAmber, RoundedCornerShape(14.dp)).padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(WarnAmber), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Atención: Cambio de Batería Requerido antes del despegue", fontWeight = FontWeight.Bold, color = Color(0xFF78350F))
                                Text(
                                    "Nivel actual insuficiente para esta salida (se requiere $batteryRequired%, " +
                                        "incluye ${profile.batteryReservePct}% de reserva para RTH). Instale una batería cargada antes de armar motores.",
                                    color = Color(0xFF92400E), style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Pill("CRÍTICO PRE-VUELO", WarnAmber)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BatteryBox("Batería actual en aeronave", "${t.batteryPercent}%", "(Insuficiente)", WarnAmber, Icons.Filled.Warning, Modifier.weight(1f))
                            BatteryBox("Batería mínima recomendada", "≥ ${maxOf(batteryRequired, 90)}%", "(Reemplazo fresco)", BrandGreen, Icons.Filled.BatteryChargingFull, Modifier.weight(1f))
                        }
                    }
                }

                AgroCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Assessment, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("RESUMEN DE LA MISIÓN", fontWeight = FontWeight.Bold, color = TextStrong, modifier = Modifier.weight(1f))
                        Pill(if (ready) "Validación de Ruta: Aprobada" else "Validación de Ruta: Pendiente", if (ready) BrandGreen else WarnAmber)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("Área delimitada", "%.2f".format(areaHa), "ha", "%,.0f m²".format(areaHa * 10_000), Modifier.weight(1f))
                        MetricTile(
                            "Tiempo de vuelo", if (sorties.isEmpty()) "-" else formatDuration(totalEstimateMin), "",
                            "Velocidad %.1f m/s".format(speed), Modifier.weight(1f)
                        )
                        MetricTile("Longitud de ruta", "%.2f".format(routeLength / 1000), "km", "Traslape %.0f%%".format(m?.parametros?.traslapePorcentaje ?: 0.0), Modifier.weight(1f))
                        MetricTile(
                            "Resolución terreno",
                            "%.1f".format(Photogrammetry.gsdCmPx(plan?.altitudeM ?: 0.0, profile, plan?.captureMode ?: CaptureMode.VIDEO, m?.parametros?.alturaCopaMetros ?: 0.0)),
                            "cm/px", "Altitud %.0f m".format(plan?.altitudeM ?: 0.0), Modifier.weight(1f), valueColor = BrandGreen
                        )
                    }
                }

                if (sorties.isNotEmpty()) {
                    AgroCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("PLAN DE SALIDAS", fontWeight = FontWeight.Bold, color = TextStrong, modifier = Modifier.weight(1f))
                            Pill(if (sorties.size == 1) "1 batería" else "${sorties.size} baterías", if (sorties.size == 1) BrandGreen else WarnAmber)
                        }
                        Spacer(Modifier.height(8.dp))
                        sorties.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(color = PanelBorder)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Salida ${salidasHechas + i + 1}", fontWeight = FontWeight.SemiBold, color = TextStrong, modifier = Modifier.width(88.dp))
                                Text("waypoints ${s.from + 1}–${s.to + 1}", fontFamily = MonoFamily, fontSize = 12.sp, color = TextMuted, modifier = Modifier.weight(1f))
                                Text("%.1f km · %s".format(s.lengthM / 1000, formatDuration(s.flightMinutes)), fontFamily = MonoFamily, fontSize = 12.sp, color = TextStrong)
                                Spacer(Modifier.width(12.dp))
                                Text("≥ ${s.batteryRequiredPct}%", fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandGreen)
                            }
                        }
                        if (sorties.size > 1) {
                            Text(
                                "Cada salida termina con regreso a casa y aterrizaje. Entre una y otra se cambia la batería; la app retoma la ruta donde quedó. " +
                                    "El plan se recalcula con la posición real del punto de origen.",
                                color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }

                if (plan != null) {
                    AgroCard(padding = 0.dp) {
                        AgroMap(
                            layers = MapLayers(
                                polygon = perimetro,
                                path = plan.waypoints.map { LatLon(it.latitude, it.longitude) },
                                activeWaypoint = startIndex,
                                drone = if (connected && !t.latitude.isNaN() && t.latitude != 0.0) LatLon(t.latitude, t.longitude) else null,
                                home = if (t.homeLocationSet) LatLon(t.homeLatitude, t.homeLongitude) else null
                            ),
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            fitKey = m?.mision?.id
                        )
                    }
                }

                if (historial.isNotEmpty()) {
                    AgroCard {
                        Text("HISTORIAL DE VUELOS", fontWeight = FontWeight.Bold, color = TextStrong)
                        Spacer(Modifier.height(6.dp))
                        historial.forEachIndexed { i, v ->
                            if (i > 0) HorizontalDivider(color = PanelBorder)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(formatShortDateTime(v.vuelo.fechaInicio), fontFamily = MonoFamily, fontSize = 12.sp, color = TextStrong, modifier = Modifier.width(110.dp))
                                Text(
                                    "${v.salidasVoladas()} ${if (v.salidasVoladas() == 1) "salida" else "salidas"} · ${v.salidas.sumOf { it.distanciaM }.div(1000).let { "%.1f km".format(it) }}",
                                    color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                                )
                                val (label, color) = when (v.vuelo.estado) {
                                    EstadoVuelo.COMPLETADO -> "Completado" to BrandGreen
                                    EstadoVuelo.EN_CURSO -> "En curso" to WarnAmber
                                    EstadoVuelo.INTERRUMPIDO -> "Interrumpido" to DangerRed
                                }
                                Pill(label, color)
                            }
                        }
                    }
                }
            }

            // ---------------- Acciones de despegue
            // Todo el panel desplaza (el checklist se ve completo) y solo "Iniciar" queda fijo abajo.
            Column(Modifier.width(320.dp).fillMaxHeight().background(Color.White)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                ) {
                    Text("Acciones de Despegue", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextStrong)
                    Text(
                        if (sorties.size > 1 || vueloEnMarcha) "Checklist de la siguiente salida." else "Revise el checklist para continuar con la aeronave.",
                        color = TextMuted, style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)).background(PageBg)
                            .border(1.dp, PanelBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        checks.forEachIndexed { i, c ->
                            if (i > 0) HorizontalDivider(color = PanelBorder)
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(if (c.ok) BrandGreen else if (c.blocking) DangerRed else WarnAmber, 8.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.label, color = TextStrong, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        c.value, style = MaterialTheme.typography.bodySmall,
                                        color = if (c.ok) BrandGreen else if (c.blocking) DangerRed else Color(0xFFB45309)
                                    )
                                    c.help?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
                                    }
                                    if (c.fix == Fix.SET_HOME_HERE) {
                                        Spacer(Modifier.height(6.dp))
                                        SecondaryButton("Fijar aquí", Icons.Filled.MyLocation, Modifier.fillMaxWidth()) {
                                            FlightActions.setHomeHere { AppStatus.message.value = it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (flying) {
                        Spacer(Modifier.height(6.dp))
                        Text("El dron ya está en el aire: la misión empezará desde su posición actual.", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Guardar Ruta para Después", Icons.Filled.Save, Modifier.fillMaxWidth(), color = NavyButton, onClick = onSaveForLater)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Modificar Parámetros / Volver", Icons.AutoMirrored.Filled.ArrowBack, Modifier.fillMaxWidth()) {
                        m?.let { onModifyParameters(it.mision.loteId, it.mision.id) } ?: onBack()
                    }
                    if (vueloActivo != null && vueloEnMarcha) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            "Terminar vuelo aquí", null, Modifier.fillMaxWidth(),
                            contentColor = DangerRed, borderColor = DangerRed
                        ) { showEndFlightDialog = true }
                    }
                }
                // Fijo abajo, siempre visible
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (!ready && plan != null && next != null) {
                        Text(
                            "Falta: " + blocking.joinToString(", ") { it.label },
                            style = MaterialTheme.typography.labelSmall, color = DangerRed, maxLines = 2
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    PrimaryButton(
                        when {
                            vueloEnMarcha -> "Continuar: salida ${salidasHechas + 1}"
                            sorties.size > 1 -> "Iniciar salida 1 de ${sorties.size}"
                            batteryLow -> "Iniciar Plan de Vuelo (Reemplazo OK)"
                            else -> "Iniciar Plan de Vuelo"
                        },
                        Icons.Filled.FlightTakeoff, Modifier.fillMaxWidth().height(50.dp),
                        enabled = ready
                    ) { showStartDialog = true }
                }
            }
        }
    }

    if (showStartDialog && plan != null && next != null && m != null) {
        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            icon = { Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = BrandGreen) },
            title = { Text("¿Iniciar salida ${salidasHechas + 1}?") },
            text = {
                Text(
                    (if (sorties.size > 1) "La misión necesita ${salidasHechas + sorties.size} salidas (una por batería). " else "") +
                        "El dron ${if (flying) "subirá" else "despegará y subirá"} en vertical a ${plan.altitudeM.toInt()} m y recorrerá " +
                        "${next.to - next.from + 1} waypoints (%.1f km, ≈ %s). ".format(next.lengthM / 1000, formatDuration(next.flightMinutes)) +
                        (if (plan.captureMode == CaptureMode.VIDEO) "Grabará video de corrido durante el barrido. " else "") +
                        "Al terminar volverá a casa y aterrizará.\n\n" +
                        "• Mantén el control remoto en la mano y el dron a la vista.\n" +
                        "• El Mini 3 no detecta obstáculos: la altura debe superar árboles y cables.\n" +
                        "• Mover las palancas o pulsar Pausa en el RC devuelve el control al piloto."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartDialog = false
                    scope.launch {
                        MissionRunner.startSortie(
                            appContext = context.applicationContext, repo = repo, mision = m, plan = plan,
                            vueloActivo = vueloActivo, sortie = next, salidasEstimadas = sorties.size,
                            routeOutsideM = outside, profile = profile
                        )
                    }
                }) { Text("Iniciar", color = BrandGreen, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showStartDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showEndFlightDialog && vueloActivo != null) {
        AlertDialog(
            onDismissRequest = { showEndFlightDialog = false },
            title = { Text("¿Terminar el vuelo aquí?") },
            text = {
                Text(
                    "Quedan ${vueloActivo.vuelo.totalWaypoints - vueloActivo.vuelo.siguienteWaypoint} waypoints sin volar. " +
                        "El vuelo se guarda como interrumpido en el historial y el lote quedará sin cubrir por completo."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEndFlightDialog = false
                    scope.launch { repo.terminarVuelo(vueloActivo.vuelo.id) }
                }) { Text("Terminar", color = DangerRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEndFlightDialog = false }) { Text("Seguir volando") } }
        )
    }
}

@Composable
private fun BatteryBox(label: String, value: String, note: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = color, fontWeight = FontWeight.Bold, fontFamily = MonoFamily)
                Spacer(Modifier.width(6.dp))
                Text(note, color = TextMuted, fontFamily = MonoFamily, fontSize = 12.sp)
            }
        }
        Icon(icon, contentDescription = null, tint = color)
    }
}
