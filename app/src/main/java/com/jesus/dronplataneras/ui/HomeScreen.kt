package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.TelemetryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SampleMission(val name: String, val date: String, val hectares: String, val color: Color)

private val sampleMissions = listOf(
    SampleMission("Campo Norte Lote A", "Ayer, 14:30", "12.5", Color(0xFF4C8DFF)),
    SampleMission("Sector Este Viñedo", "Oct 22, 09:15", "8.2", Color(0xFF7C6CF0)),
    SampleMission("Perímetro Sur", "Oct 20, 16:45", "24.0", Color(0xFF4C8DFF))
)

@Composable
fun HomeScreen(onEnterFlightScreen: () -> Unit, onConnect: () -> Unit, onOpenNtrip: () -> Unit) {
    val isConnected by DJIConnectionManager.isConnected
    val telemetry by TelemetryManager.telemetry

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = "AgroScan",
            center = {
                OutlinedButton(
                    onClick = onOpenNtrip,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Estación NTRIP", style = MaterialTheme.typography.labelMedium)
                }
            }
        ) { StatusPill(isConnected) }

        // Sin scroll: todo se reparte con weight() para caber en cualquier alto de pantalla
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            if (!isConnected) {
                DisconnectedBanner(onConnect)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Fila principal: bienvenida+stats, Nueva Misión, Explorar Mapas
            Row(
                modifier = Modifier.fillMaxWidth().weight(1.3f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Bienvenido, Operador", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-ES")).format(Date()),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatTile(
                            label = "Batería",
                            value = if (isConnected) "${telemetry.batteryPercent}%" else "--%",
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        StatTile(
                            label = "Señal",
                            value = if (isConnected) "${telemetry.signalQuality}%" else "Sin Señal",
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        StatTile(
                            label = "Satélites",
                            value = if (isConnected) "${telemetry.gpsSatelliteCount}" else "0",
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }

                FeatureCard(
                    title = "Nueva Misión",
                    subtitle = "Configurar parámetros de vuelo automatizado.",
                    icon = Icons.Filled.FlightTakeoff,
                    background = if (isConnected) Color(0xFF00704A) else Color(0xFFB9C2BC),
                    contentColor = Color.White,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = if (isConnected) onEnterFlightScreen else null
                )

                FeatureCard(
                    title = "Explorar Mapas",
                    subtitle = "Revisar ortomosaicos y topografía.",
                    icon = Icons.Filled.Map,
                    background = CardBg,
                    contentColor = Color.Black,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = null
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Misiones recientes: comparte el resto del alto disponible
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Misiones Recientes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Ver Todo", color = AgroGreen, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    sampleMissions.forEach { mission ->
                        MissionCard(mission, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun DisconnectedBanner(onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DangerBg)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("Drone no detectado", color = DangerRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Verifique la conexión del enlace de datos y el encendido de la aeronave.",
                    color = DangerRed,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
        OutlinedButton(onClick = onConnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Conectar Dron", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AgroGreen, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(26.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
        }
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Text(title, color = contentColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(subtitle, color = contentColor, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

@Composable
private fun MissionCard(mission: SampleMission, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(mission.color),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(mission.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(mission.date, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
            Text("${mission.hectares} ha", color = AgroGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
    }
}
