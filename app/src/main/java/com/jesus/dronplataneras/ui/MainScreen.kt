package com.jesus.dronplataneras.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.TelemetryManager

@Composable
fun MainScreen() {
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
        }
        // Panel derecho: estado, telemetría y controles
        Column(
            modifier = Modifier
                .width(200.dp)
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

            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Batería: ${telemetry.batteryPercent}%", style = MaterialTheme.typography.bodySmall)
            Text("Altitud: %.1f m".format(telemetry.altitude), style = MaterialTheme.typography.bodySmall)
            Text("Velocidad: %.1f m/s".format(telemetry.speed), style = MaterialTheme.typography.bodySmall)
            Text(
                "GPS: ${telemetry.gpsSatelliteCount} sat. (nivel ${telemetry.gpsSignalLevel})",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = if (telemetry.homeLocationSet) "Home point: guardado" else "Home point: NO guardado",
                style = MaterialTheme.typography.bodySmall,
                color = if (telemetry.homeLocationSet) Color.Unspecified else MaterialTheme.colorScheme.error
            )
            Text("Estado RTH: ${telemetry.goHomeStatus}", style = MaterialTheme.typography.bodySmall)
            Text("Modo de vuelo: ${telemetry.flightMode}", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(16.dp))

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
        }
    }
}