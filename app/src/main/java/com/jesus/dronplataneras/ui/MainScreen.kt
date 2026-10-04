package com.jesus.dronplataneras.ui

import android.location.Location
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private val HudBackground = Color(0xAA000000)
private val HudBarBackground = Color(0xE6FFFFFF)
private val MarkGreen = Color(0xFF00704A)
private val CloseBlue = Color(0xFF1565C0)

// (latitud, longitud)
private typealias LatLon = Pair<Double, Double>

@Composable
fun MainScreen(onBack: () -> Unit) {
    val statusMessage by AppStatus.message
    val telemetry by TelemetryManager.telemetry
    val context = LocalContext.current
    val vertices = remember { mutableStateListOf<LatLon>() }

    LaunchedEffect(statusMessage) {
        if (statusMessage.isNotEmpty()) {
            Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
        }
    }

    val distance = if (telemetry.homeLocationSet) {
        FloatArray(1).also {
            Location.distanceBetween(
                telemetry.latitude, telemetry.longitude,
                telemetry.homeLatitude, telemetry.homeLongitude, it
            )
        }[0]
    } else 0f

    val hasPosition = telemetry.latitude != 0.0 || telemetry.longitude != 0.0

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val mapWidth = min(150f, maxWidth.value * 0.26f).dp
        val mapHeight = min(90f, maxHeight.value * 0.26f).dp

        CameraPreview(modifier = Modifier.fillMaxSize())

        // HUD superior
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HudBarBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HudStat(Icons.Filled.Satellite, "SATÉLITES", "${telemetry.gpsSatelliteCount}")
            HudStat(Icons.Filled.Height, "ALTITUD", "%.0f m".format(telemetry.altitude))
            HudStat(Icons.Filled.Straighten, "DISTANCIA", "%.0f m".format(distance))
            HudStat(Icons.Filled.Speed, "VELOCIDAD", "%.1f m/s".format(telemetry.speed))
            HudStat(Icons.Filled.Battery5Bar, "BATERÍA", "${telemetry.batteryPercent}%")
        }

        if (statusMessage.isNotEmpty()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(HudBackground, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Izquierda: volver
        Button(
            onClick = onBack,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC37474F)),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(44.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", modifier = Modifier.size(22.dp))
        }

        // Mini mapa (abajo izquierda)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .width(mapWidth)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Map, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Mapa", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            MiniMap(
                vertices = vertices,
                drone = if (hasPosition) telemetry.latitude to telemetry.longitude else null,
                modifier = Modifier.fillMaxWidth().height(mapHeight)
            )
        }

        // Derecha: marcar vértice, deshacer, cerrar polígono
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).width(86.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MissionButton(Icons.Filled.Place, "MARCAR VÉRTICE", MarkGreen, Color.White, 56.dp) {
                if (hasPosition) vertices.add(telemetry.latitude to telemetry.longitude)
                else AppStatus.message.value = "Sin posición GPS para marcar el vértice"
            }
            MissionButton(Icons.AutoMirrored.Filled.Undo, null, Color(0xE6FFFFFF), Color.DarkGray, 36.dp) {
                if (vertices.isNotEmpty()) vertices.removeAt(vertices.lastIndex)
            }
            // ponytail: por ahora no hace nada (pendiente definir qué hace al cerrar el polígono)
            MissionButton(Icons.Filled.CheckCircle, "CERRAR POLÍGONO", CloseBlue, Color.White, 56.dp) {}
        }
    }
}

@Composable
private fun MissionButton(
    icon: ImageVector,
    text: String?,
    background: Color,
    contentColor: Color,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth().height(height)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
            if (text != null) {
                Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 10.sp)
            }
        }
    }
}

@Composable
private fun MiniMap(vertices: List<LatLon>, drone: LatLon?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFFE8EDE6))) {
        val pts = vertices + listOfNotNull(drone)
        if (pts.isEmpty()) return@Canvas

        // proyección plana: la longitud se escala por cos(latitud)
        val k = cos(Math.toRadians(pts.map { it.first }.average()))
        val xs = pts.map { it.second * k }
        val ys = pts.map { -it.first }
        val minX = xs.min()
        val minY = ys.min()
        val span = max(xs.max() - minX, ys.max() - minY).coerceAtLeast(1e-7)
        val pad = 14f
        val scale = (min(size.width, size.height) - 2 * pad) / span
        val offX = (size.width - (xs.max() - minX) * scale) / 2
        val offY = (size.height - (ys.max() - minY) * scale) / 2

        fun project(p: LatLon) = Offset((offX + (p.second * k - minX) * scale).toFloat(), (offY + (-p.first - minY) * scale).toFloat())

        val mapped = vertices.map(::project)
        if (mapped.size >= 3) {
            val path = Path().apply {
                moveTo(mapped[0].x, mapped[0].y)
                mapped.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, Color(0x6676C442))
            drawPath(path, Color(0xFF4CAF50), style = Stroke(width = 3f))
        } else if (mapped.size == 2) {
            drawLine(Color(0xFF4CAF50), mapped[0], mapped[1], strokeWidth = 3f)
        }
        mapped.forEach { drawCircle(Color(0xFF2E7D32), radius = 4f, center = it) }
        drone?.let { drawCircle(Color(0xFF1565C0), radius = 5f, center = project(it)) }
    }
}

@Composable
private fun HudStat(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(11.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}
