package com.jesus.dronplataneras.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jesus.dronplataneras.R
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import kotlinx.coroutines.delay

private val ScreenBg = Color(0xFFF3F4F5)
private const val SEARCH_TIMEOUT_MS = 10_000L

@Composable
fun ConnectScreen(onConnected: () -> Unit, onNotDetected: () -> Unit) {
    val isConnected by DJIConnectionManager.isConnected
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(isConnected) { if (isConnected) onConnected() }
    LaunchedEffect(attempt) {
        delay(SEARCH_TIMEOUT_MS)
        if (!DJIConnectionManager.isConnected.value) onNotDetected()
    }

    Column(modifier = Modifier.fillMaxSize().background(ScreenBg)) {
        AppTopBar("AgroScan Pro") { StatusPill(isConnected) }

        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ScreenBg),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.hardware_connect),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Conexión de Hardware", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "Conectar cable USB-C al control remoto.",
                            "Encender el control remoto (presionar una vez y luego mantener).",
                            "Encender el dron AgroScan Pro."
                        ).forEachIndexed { i, step -> StepRow(i + 1, step, Modifier.weight(1f)) }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AgroGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buscando aeronave...", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = { attempt++ },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AgroGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Iniciar Sincronización", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ScreenBg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(18.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

@Composable
fun NotDetectedScreen(onRetry: () -> Unit, onHome: () -> Unit) {
    val isConnected by DJIConnectionManager.isConnected
    LaunchedEffect(isConnected) { if (isConnected) onHome() }

    Column(modifier = Modifier.fillMaxSize().background(ScreenBg)) {
        AppTopBar("AgroScan Pro") {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(50)).background(DangerBg).padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(DangerBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.UsbOff, contentDescription = null, tint = DangerRed, modifier = Modifier.size(40.dp))
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Text("Dron No Detectado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardBg)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TipRow(Icons.Filled.Usb, "Compruebe la conexión del cable entre el control y el dispositivo.")
                        TipRow(Icons.Filled.PowerSettingsNew, "Verifique que el dron tenga batería y esté encendido.")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = AgroGreen),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Conectar", style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(
                            onClick = onHome,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Volver a inicio", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TipRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}
