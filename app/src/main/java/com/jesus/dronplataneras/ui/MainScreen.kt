package com.jesus.dronplataneras.ui

import android.location.Location
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.R
import com.jesus.dronplataneras.camera.CameraControl
import com.jesus.dronplataneras.camera.GimbalLook
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.sdk.runOnMain
import com.jesus.dronplataneras.survey.PolygonGeometry
import com.jesus.dronplataneras.map.AgroMap
import com.jesus.dronplataneras.map.MapLayers
import com.jesus.dronplataneras.survey.SurveySession
import com.jesus.dronplataneras.survey.toLatLon
import com.jesus.dronplataneras.survey.LatLon as GeoLatLon
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.math.min

private val HudBackground = Color(0xAA000000)
private val HudBarBackground = Color(0xE6FFFFFF)
private val MarkGreen = Color(0xFF00704A)
private val CloseBlue = Color(0xFF1565C0)


/** Baja la cámara al marcar un vértice y la devuelve a su ángulo (ver [GimbalLook]). Una sola para toda la app: la cámara también es una sola. */
private val gimbalLook = GimbalLook(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    pitch = { DroneInfo.gimbalPitch.value },
    rotate = { degrees -> CameraControl.rotateGimbal(degrees) { runOnMain { AppStatus.message.value = "No se pudo mover la cámara: $it" } } },
    atNadir = ::isNadir
)

/**
 * Vista de cámara a pantalla completa. Comparte los vértices con el editor de perímetro
 * (SurveySession), así se puede fijar esquinas mirando el video y terminar en el mapa.
 */
@Composable
fun MainScreen(onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val isConnected by DJIConnectionManager.isConnected
    val isFlying by DJIConnectionManager.isFlying
    val statusMessage by AppStatus.message
    val telemetry by TelemetryManager.telemetry
    // Los vértices viven en SurveySession: sobreviven a salir/volver de la pantalla
    val vertices = SurveySession.vertices
    val areaHa = PolygonGeometry.areaSquareMeters(vertices) / 10_000

    val gimbalPitch by DroneInfo.gimbalPitch

    fun report(result: SurveySession.Result) {
        AppStatus.message.value = when (result) {
            is SurveySession.Result.Ok -> result.message
            is SurveySession.Result.Rejected -> result.reason
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage.isNotEmpty()) {
            delay(5000)
            AppStatus.message.value = ""
        }
    }

    val hasPosition = !telemetry.latitude.isNaN() && !telemetry.longitude.isNaN() &&
        (telemetry.latitude != 0.0 || telemetry.longitude != 0.0)

    val distance = if (telemetry.homeLocationSet && hasPosition) {
        FloatArray(1).also {
            Location.distanceBetween(
                telemetry.latitude, telemetry.longitude,
                telemetry.homeLatitude, telemetry.homeLongitude, it
            )
        }[0]
    } else 0f

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Proporción 16:9 como la miniatura de DJI Fly
        val mapWidth = min(220f, maxWidth.value * 0.28f).dp
        val mapHeight = (mapWidth.value * 0.5625f).dp

        CameraPreview(modifier = Modifier.fillMaxSize())

        // Mira del punto que se marca (el punto bajo el dron): siempre en vuelo; se corre en la imagen si se inclina la cámara (ver VertexReticle)
        if (isConnected && isFlying) VertexReticle(gimbalPitch, maxWidth, maxHeight)

        // Fila superior: datos | notificación (centro) | volver
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier
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

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier
                            .background(HudBackground, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Button(
                onClick = onBack,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC37474F)),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", modifier = Modifier.size(20.dp))
            }
        }

        // Izquierda: despegar (en tierra) / aterrizar (volando), manteniendo presionado
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) {
            HoldToConfirmButton(
                text = if (isFlying) "Aterrizar" else "Despegar",
                color = Color(0xE6FFFFFF),
                ringColor = if (isFlying) DangerRed else AgroGreen,
                size = 64.dp,
                enabled = isConnected,
                icon = {
                    Image(
                        painter = painterResource(R.drawable.ic_land_arrow),
                        contentDescription = if (isFlying) "Aterrizar" else "Despegar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().rotate(if (isFlying) 0f else 180f)
                    )
                },
                onConfirm = {
                    if (isFlying) {
                        FlightActions.landNow(onStatus = { status -> AppStatus.message.value = status })
                    } else {
                        FlightActions.takeOffToTargetHeight(onStatus = { status -> AppStatus.message.value = status })
                    }
                }
            )
        }

        // Mini mapa, como en DJI Fly: es el mismo mapa del editor de perímetro. Se arrastra a cualquier esquina;
        // al tocarlo se intercambia: el mapa pasa a pantalla completa y la cámara a la miniatura.
        DraggableThumbnail(
            containerWidth = maxWidth, containerHeight = maxHeight,
            width = mapWidth, height = mapHeight, margin = 10.dp, topInset = 56.dp,
            endInset = 106.dp   // deja libres los botones de la derecha
        ) {
            AgroMap(
                layers = MapLayers(
                    polygon = vertices.map { it.toLatLon() },
                    polygonClosed = vertices.size >= 3,
                    drone = if (hasPosition) GeoLatLon(telemetry.latitude, telemetry.longitude) else null,
                    home = if (telemetry.homeLocationSet) GeoLatLon(telemetry.homeLatitude, telemetry.homeLongitude) else null,
                    highlightLastVertex = true
                ),
                modifier = Modifier.fillMaxSize(),
                followDrone = hasPosition,
                interactive = false
            )
            // Capa encima del mapa: recibe el toque (el MapView no debe moverse en miniatura)
            Box(Modifier.fillMaxSize().clickable(onClick = onOpenEditor))
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0x99000000), RoundedCornerShape(bottomEnd = 8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    when {
                        vertices.size >= 3 -> "${vertices.size} pts · %.2f ha".format(areaHa)
                        vertices.isNotEmpty() -> "${vertices.size} pts"
                        else -> "MAPA"
                    },
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // Derecha: marcar vértice, deshacer, cerrar polígono
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).width(86.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MissionButton(Icons.Filled.Place, "MARCAR VÉRTICE ${vertices.size + 1}", MarkGreen, Color.White, 56.dp) {
                val result = SurveySession.capture(telemetry, isConnected)
                report(result)
                // El vértice es la posición del dron, no lo que se ve: si la cámara estaba inclinada baja sola para mostrar el punto marcado
                // y vuelve al ángulo anterior en cuanto llega (se cancela si el operador la mueve). No baja al entrar a la pantalla ni con
                // un rechazo: al volar a mano se mira al frente.
                if (result is SurveySession.Result.Ok) {
                    gimbalLook.lookDownThenRestore { runOnMain { AppStatus.message.value = "Cámara de vuelta al ángulo anterior" } }
                        ?.let { AppStatus.message.value += " · La cámara baja un instante para mostrar el punto marcado" }
                }
            }
            MissionButton(Icons.AutoMirrored.Filled.Undo, null, Color(0xE6FFFFFF), Color.DarkGray, 36.dp) {
                SurveySession.undo()
            }
            // El cierre y el guardado se hacen en el editor de perímetro (mapa)
            MissionButton(
                Icons.Filled.CheckCircle, "EDITOR / CERRAR",
                if (vertices.size >= 3) CloseBlue else Color(0xFF90A4AE), Color.White, 56.dp,
                onClick = onOpenEditor
            )
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
