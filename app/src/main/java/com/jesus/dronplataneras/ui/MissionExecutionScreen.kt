package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.map.AgroMap
import com.jesus.dronplataneras.map.MapLayers
import com.jesus.dronplataneras.mission.CaptureMode
import com.jesus.dronplataneras.mission.MissionExecutor
import com.jesus.dronplataneras.mission.MissionExecutor.Phase
import com.jesus.dronplataneras.mission.MissionRunner
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.telemetry.TelemetryManager

private val CardGlass = Color(0xF2FFFFFF)
private val AmberButton = Color(0xFFFF9800)

/**
 * Pantalla de la misión en vuelo: vista principal a pantalla completa (mapa con la ruta o cámara),
 * telemetría esencial, progreso de TODA la misión (no solo de esta batería), estado de la grabación
 * y los controles de pausa y de regreso a casa.
 *
 * La ventana pequeña muestra la otra vista (se arrastra a cualquier esquina); tocándola se intercambian
 * (la cámara pasa a pantalla completa y el mapa queda en miniatura, o al revés).
 *
 * Al terminar la ruta el dron vuelve solo y la pantalla sigue mostrando el regreso hasta que
 * aterriza; solo entonces se puede continuar.
 */
@Composable
fun MissionExecutionScreen(perimetro: List<LatLon>, onClose: () -> Unit) {
    val s by MissionExecutor.status
    val ctx by MissionRunner.current
    val t by TelemetryManager.telemetry
    val flying by DJIConnectionManager.isFlying
    // false = mapa grande y cámara pequeña; true = cámara grande y mapa pequeño
    var cameraMain by rememberSaveable { mutableStateOf(false) }

    val c = ctx
    val plan = c?.plan
    val route = remember(plan) { plan?.waypoints?.map { LatLon(it.latitude, it.longitude) } ?: emptyList() }
    val waypointGlobal = (c?.waypointOffset ?: 0) + s.waypointIndex
    val doneM = (c?.distanciaPreviaM ?: 0.0) + s.distanceDoneM
    val overall = if (c != null && c.distanciaTotalM > 0) (doneM / c.distanciaTotalM).coerceIn(0.0, 1.0) else s.progress
    val videoMode = plan?.captureMode != CaptureMode.PHOTO
    val frames = if (plan != null && plan.photoEveryM > 0) (doneM / plan.photoEveryM).toInt() else 0

    // Tras recorrer la ruta el dron vuelve solo: esta pantalla sigue mostrando mapa, cámara y regreso hasta que aterriza
    val postFlight = !s.running
    val returning = t.goHomeStatus == "RETURNING_TO_HOME"
    val landing = t.goHomeStatus == "LANDING"
    val hasPosition = !t.latitude.isNaN() && t.latitude != 0.0
    val homeDistance =
        if (t.homeLocationSet && hasPosition) PolygonGeometry.distanceMeters(t.latitude, t.longitude, t.homeLatitude, t.homeLongitude)
        else Double.NaN
    val homeText = if (homeDistance.isNaN()) "" else " · %.0f m de casa".format(homeDistance)
    val layers = MapLayers(
        polygon = perimetro,
        path = route,
        activeWaypoint = waypointGlobal,
        drone = if (hasPosition) LatLon(t.latitude, t.longitude) else null,
        home = if (t.homeLocationSet) LatLon(t.homeLatitude, t.homeLongitude) else null
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
        val compact = maxHeight < 480.dp
        val gutter = if (compact) 8.dp else 14.dp
        val barHeight = if (compact) 44.dp else 56.dp

        // ---- Vista principal
        if (cameraMain) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { CameraPreview(Modifier.fillMaxSize()) }
        } else {
            AgroMap(
                layers = layers,
                modifier = Modifier.fillMaxSize(),
                fitKey = c?.vueloId,
                controlsAlignment = Alignment.TopEnd,
                controlsModifier = Modifier.padding(top = barHeight + if (compact) 4.dp else 20.dp)
            )
        }

        // ---- Barra superior: marca y estado
        Row(
            Modifier.fillMaxWidth().height(barHeight).background(Color(0xE6F1F5F9)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark()
            Spacer(Modifier.weight(1f))
            val (label, color) = phaseLabel(s.phase)
            Pill("ESTADO: $label", color)
        }

        // ---- Telemetría esencial (centro arriba), estado del regreso a casa y avisos
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = barHeight + gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(CardGlass)
                    .padding(horizontal = if (compact) 14.dp else 22.dp, vertical = if (compact) 6.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudValue(Icons.Filled.Height, "%.0f m".format(t.altitude), TextStrong, compact)
                HudValue(Icons.Filled.Speed, "%.1f m/s".format(t.speed), TextStrong, compact)
                HudValue(
                    Icons.Filled.BatteryFull, "${t.batteryPercent}%",
                    when {
                        t.batteryPercent < 30 -> DangerRed
                        t.batteryPercent < 50 -> WarnAmber
                        else -> BrandGreen
                    },
                    compact
                )
                // Con la cámara en grande el indicador REC de la ventana pequeña no se ve: se repite aquí
                if (s.recording) HudValue(Icons.Filled.Videocam, "REC ${formatClock(s.recordingS)}", DangerRed, compact)
            }
            if (s.phase == Phase.PREPARING && s.message.isNotBlank()) {
                Text(
                    s.message, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 12.sp else 14.sp,
                    modifier = Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE6B45309))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            if (postFlight && flying) {
                Text(
                    when {
                        landing -> "ATERRIZANDO$homeText"
                        returning -> "REGRESANDO A CASA$homeText"
                        else -> "DRON EN EL AIRE$homeText"
                    },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (landing || returning) BrandGreen else WarnAmber)
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
            StatusToast()
        }

        // ---- Columna izquierda: progreso y waypoint (o el resultado mientras el dron regresa)
        Column(
            Modifier.align(Alignment.TopStart).padding(start = gutter, top = barHeight + gutter).width(if (compact) 196.dp else 230.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            GlassCard(compact) {
                Text("PROGRESO DE MISIÓN", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.0f%%".format(overall * 100), fontSize = if (compact) 30.sp else 44.sp, fontWeight = FontWeight.Bold, color = BrandGreen)
                    Spacer(Modifier.weight(1f))
                    Text("Completado", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                }
                LinearProgressIndicator(
                    progress = { overall.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(if (compact) 6.dp else 8.dp).clip(CircleShape),
                    color = BrandGreen, trackColor = Color(0xFFE2E8F0)
                )
                if (c != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Salida ${c.salidaNumero} de ${c.salidasTotal}",
                        color = TextStrong, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (!postFlight) {
                GlassCard(compact) {
                    Text("WAYPOINT", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${(waypointGlobal + 1).coerceAtMost(c?.totalWaypoints ?: s.waypointCount)}",
                            fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold, color = TextStrong
                        )
                        Text(" / ${c?.totalWaypoints ?: s.waypointCount}", color = TextMuted, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = PanelBorder)
                    if (videoMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Videocam, contentDescription = null, tint = if (s.recording) DangerRed else TextMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (s.recording) "GRABANDO  ${formatClock(s.recordingS)}" else "Video en espera",
                                fontWeight = FontWeight.Bold, color = if (s.recording) DangerRed else TextMuted, fontSize = if (compact) 12.sp else 14.sp
                            )
                        }
                        Text("CUADROS PREVISTOS ≈ $frames", style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${s.photos}", fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Bold, color = BrandGreen)
                        }
                        Text("FOTOS TOMADAS", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        if (s.photoErrors > 0) {
                            Text(
                                "Rechazadas: ${s.photoErrors} · ${s.cameraError}",
                                style = MaterialTheme.typography.labelSmall, color = DangerRed, maxLines = 3
                            )
                        }
                    }
                }
            } else if (flying) {
                // El dron sigue en el aire: resumen corto (el estado del regreso va en el cartel de arriba)
                val ok = s.phase == Phase.FINISHED
                GlassCard(compact) {
                    ResultHeader(ok)
                    Text(
                        s.message, color = TextMuted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp), maxLines = if (compact) 3 else 5
                    )
                }
            }
        }

        // ---- Controles (abajo derecha)
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = gutter, bottom = gutter).width(if (compact) 190.dp else 270.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            if (s.running) {
                if (s.phase == Phase.PAUSED) {
                    BigButton("Reanudar vuelo", Icons.Filled.PlayArrow, BrandGreen, compact) { MissionExecutor.resume() }
                } else {
                    BigButton("Pausar vuelo", Icons.Filled.Pause, AmberButton, compact, enabled = s.phase == Phase.FLYING) { MissionExecutor.pause() }
                }
                ReturnHomeButton(compact) { MissionExecutor.returnHome() }
                SecondaryButton(
                    "Detener y tomar control", Icons.Filled.Stop, Modifier.fillMaxWidth().background(CardGlass, RoundedCornerShape(12.dp)),
                    contentColor = DangerRed, borderColor = DangerRed
                ) { MissionExecutor.stopAndHover() }
            } else if (flying) {
                if (returning || landing) {
                    // El dron regresa o aterriza solo: un único botón para quitarle el mando automático
                    BigButton("Control manual", Icons.Filled.TouchApp, AmberButton, compact) {
                        FlightActions.takeManualControl { AppStatus.message.value = it }
                    }
                } else {
                    // Control manual (el dron está en hover): ahora sí se puede mandar a casa o aterrizar
                    ReturnHomeButton(compact) { FlightActions.returnToHome { AppStatus.message.value = it } }
                    LandButton(compact) { FlightActions.landNow({ AppStatus.message.value = it }) }
                }
                Text(
                    if (returning || landing) "Podrás continuar cuando el dron aterrice" else "Control manual: tú guías el dron",
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // ---- Resultado con el dron ya en tierra: tarjeta central con el mensaje completo
        if (postFlight && !flying) {
            val ok = s.phase == Phase.FINISHED
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = gutter * 2)
                    .widthIn(max = 460.dp)
                    .heightIn(max = maxHeight - barHeight - gutter * 2)
                    .clip(RoundedCornerShape(16.dp)).background(CardGlass)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                ResultHeader(ok)
                Text(s.message, color = TextStrong, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Dron en tierra", color = BrandGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Continuar", null, Modifier.fillMaxWidth(), color = NavyButton, onClick = onClose)
            }
        }

        // ---- Ventana pequeña: la otra vista. Se arrastra a cualquier esquina; tocarla intercambia mapa y cámara
        DraggableThumbnail(
            containerWidth = maxWidth, containerHeight = maxHeight,
            width = if (compact) 144.dp else 230.dp, height = if (compact) 81.dp else 130.dp,
            margin = gutter, topInset = barHeight + gutter,
            endInset = (if (compact) 190.dp else 270.dp) + gutter * 2   // deja libres los controles de vuelo
        ) {
            if (cameraMain) {
                AgroMap(layers = layers, modifier = Modifier.fillMaxSize(), fitKey = c?.vueloId, interactive = false)
            } else {
                CameraPreview(Modifier.fillMaxSize())
                if (s.recording) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(DangerRed)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                        Spacer(Modifier.width(4.dp))
                        Text("REC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // Capa transparente que recibe el toque (la vista de cámara y la del mapa no deben quedarse con él)
            Box(Modifier.fillMaxSize().clickable { cameraMain = !cameraMain })
            Icon(
                Icons.Filled.SwapHoriz, contentDescription = "Intercambiar mapa y cámara", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp)
                    .clip(CircleShape).background(Color(0x99000000)).padding(2.dp)
            )
        }
    }
}

@Composable
private fun ResultHeader(ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null,
            tint = if (ok) BrandGreen else WarnAmber, modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(if (ok) "Salida completada" else "Salida interrumpida", fontWeight = FontWeight.Bold, color = TextStrong)
    }
}

@Composable
private fun GlassCard(compact: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardGlass)
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
        content = content
    )
}

@Composable
private fun HudValue(icon: ImageVector, text: String, color: Color, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (compact) 16.dp else 22.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 20.sp)
    }
}

private fun phaseLabel(p: Phase): Pair<String, Color> = when (p) {
    Phase.IDLE -> "EN ESPERA" to TextMuted
    Phase.PREPARING -> "PREPARANDO" to WarnAmber
    Phase.TAKING_OFF -> "DESPEGANDO" to WarnAmber
    Phase.CLIMBING -> "SUBIENDO" to BrandGreen
    Phase.FLYING -> "EN VUELO" to BrandGreen
    Phase.PAUSED -> "EN PAUSA" to WarnAmber
    Phase.HOLDING -> "DETENIDO" to DangerRed
    Phase.RETURNING -> "REGRESANDO" to NavyButton
    Phase.FINISHED -> "SALIDA COMPLETA" to BrandGreen
}

private fun formatClock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
