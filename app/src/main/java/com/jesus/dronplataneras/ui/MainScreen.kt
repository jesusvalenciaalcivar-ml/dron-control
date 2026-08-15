package com.jesus.dronplataneras.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jesus.dronplataneras.camera.CameraActions
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.TelemetryManager

private val HudBackground = Color(0xAA000000)

@Composable
private fun HudText(text: String, color: Color = Color.White) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun MainScreen(onOpenGallery: () -> Unit) {
    var isConnected by DJIConnectionManager.isConnected
    val isFlying by DJIConnectionManager.isFlying
    var connectPressed by remember { mutableStateOf(false) }
    var isLanding by remember { mutableStateOf(false) }
    val statusMessage by AppStatus.message
    val telemetry by TelemetryManager.telemetry
    val context = LocalContext.current

    LaunchedEffect(statusMessage) {
        if (statusMessage.isNotEmpty()) {
            Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isFlying) {
        if (!isFlying) isLanding = false
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            DJIConnectionManager.checkGoHomeKeySupport { support -> AppStatus.message.value = support }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Panel izquierdo: cámara, ocupa todo el espacio que sobra
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            CameraPreview(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )

            // HUD: telemetría superpuesta sobre la imagen de la cámara, repartida en ambas esquinas
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(HudBackground, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                HudText("Batería: ${telemetry.batteryPercent}%")
                HudText("Altitud: %.1f m".format(telemetry.altitude))
                HudText("Velocidad: %.1f m/s".format(telemetry.speed))
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(HudBackground, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                HudText("GPS: ${telemetry.gpsSatelliteCount} sat. (nivel ${telemetry.gpsSignalLevel})")
                HudText(
                    text = if (telemetry.homeLocationSet) "Home point: guardado" else "Home point: NO guardado",
                    color = if (telemetry.homeLocationSet) Color.White else Color(0xFFFF6B6B)
                )
                HudText("Estado RTH: ${telemetry.goHomeStatus}")
                HudText("Modo de vuelo: ${telemetry.flightMode}")
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
        }
        // Panel derecho: solo controles
        Column(
            modifier = Modifier
                .width(165.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Mini 3",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when {
                    !connectPressed -> "Sin verificar"
                    isConnected -> "Dron conectado"
                    else -> "Buscando dron"
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { connectPressed = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)), // ← nuevo
                    modifier = Modifier.size(70.dp)
                ) {
                    Text("Conectar", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }

                HoldToConfirmButton(
                    text = "Despegar",
                    color = Color(0xFF4CAF50), // verde
                    size = 70.dp,
                    enabled = connectPressed && isConnected && !isFlying,
                    onConfirm = {
                        FlightActions.takeOffToTargetHeight(
                            onStatus = { status -> AppStatus.message.value = status }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(text = "Emergencia", style = MaterialTheme.typography.labelLarge)

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        FlightActions.returnToHome { status ->
                            AppStatus.message.value = status
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.tertiary
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.size(70.dp)
                ) {
                    Text("RTH", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }

                Button(
                    onClick = {
                        if (isLanding) {
                            FlightActions.cancelLanding(
                                onStatus = { status -> AppStatus.message.value = status },
                                onResult = { isLanding = false }
                            )
                        } else {
                            FlightActions.landNow(
                                onStatus = { status -> AppStatus.message.value = status },
                                onResult = { success -> if (success) isLanding = true }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLanding) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.size(70.dp)
                ) {
                    Text(
                        if (isLanding) "Cancelar" else "Aterrizar",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        CameraActions.takePhoto { status -> AppStatus.message.value = status }
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575)),
                    modifier = Modifier.size(70.dp)
                ) {
                    Text("Foto", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }

                OutlinedButton(
                    onClick = onOpenGallery,
                    shape = CircleShape,
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.size(70.dp)
                ) {
                    Text("Galería", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}