package com.jesus.dronplataneras.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.network.FlightLogSync
import com.jesus.dronplataneras.network.PcLink
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.runOnMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NetworkTab(val label: String) { PC("Conectar a PC"), GALLERY("Galería del dron") }

/**
 * Red local y galería. Conexión con la PC (app de escritorio): la app se une sola al Wi-Fi AgroScan con la contraseña que muestra la PC,
 * sin pasar por los Ajustes de Wi-Fi del teléfono.
 */
@Composable
fun LocalNetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val link by PcLink.state
    var tab by remember { mutableStateOf(NetworkTab.PC) }
    var pcIp by remember { mutableStateOf(PcLink.DEFAULT_PC_IP) }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF3F4F5))) {
        AppTopBar(
            center = {
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Volver a inicio", style = MaterialTheme.typography.labelMedium)
                }
            }
        ) {
            when (link) {
                PcLink.State.CONNECTED -> Pill("PC conectada", AgroGreen)
                PcLink.State.CONNECTING -> Pill("PC conectando…", WarnAmber)
                PcLink.State.DISCONNECTED -> Pill("PC desconectada", DangerRed)
            }
        }

        // Sin scroll: el contenido reparte el alto que sobra
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandGreenSoft, selectedLabelColor = BrandGreen)
                        )
                    }
                }
                when (tab) {
                    NetworkTab.PC -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    Text(
                        "Escribe la contraseña que muestra la PC para unirte al Wi-Fi ${PcLink.WIFI_SSID}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        CompactField("IP de la PC", pcIp, { pcIp = it }, Modifier.weight(1f), KeyboardType.Uri)
                        CompactField("Contraseña del Wi-Fi", password, { password = it }, Modifier.weight(1f), KeyboardType.Password, hidden = true)
                        PrimaryButton(
                            if (link == PcLink.State.CONNECTING) "Conectando…" else "Conectar a PC",
                            Icons.Filled.Link,
                            enabled = link != PcLink.State.CONNECTING
                        ) {
                            PcLink.connect(context, pcIp, password) { error -> runOnMain { AppStatus.message.value = error } }
                            // Para unirse a una red, Android muestra su propio aviso de confirmación
                            if (PcLink.state.value == PcLink.State.CONNECTING) {
                                AppStatus.message.value = "Android te pedirá confirmar: toca Conectar en su aviso"
                            }
                        }
                    }
                    // Registros y marcas del video de las salidas que la PC todavía no tiene (las que se volaron sin la PC cerca)
                    val scope = rememberCoroutineScope()
                    var enviando by remember { mutableStateOf(false) }
                    SecondaryButton(
                        if (enviando) "Enviando…" else "Enviar vuelos pendientes", Icons.Filled.CloudUpload, Modifier.fillMaxWidth(),
                        enabled = link == PcLink.State.CONNECTED && !enviando
                    ) {
                        enviando = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { FlightLogSync.sendPending(AgroRepository.get(context)) }
                            AppStatus.message.value = r.fold(
                                { res ->
                                    val sinArchivo = if (res.sinArchivo > 0) " (${res.sinArchivo} sin registro en el teléfono)" else ""
                                    if (res.enviadas > 0) "Enviadas a la PC: ${res.enviadas} salida(s)$sinArchivo"
                                    else "La PC ya tiene todos los vuelos$sinArchivo"
                                },
                                { "No se pudo enviar: ${it.message}" }
                            )
                            enviando = false
                        }
                    }
                    // ponytail: sin servidor en la PC (no hay puerto ni protocolo) el botón no envía nada todavía
                    SecondaryButton(
                        "Enviar Polígono a PC", Icons.Filled.FileUpload, Modifier.fillMaxWidth(),
                        enabled = link == PcLink.State.CONNECTED
                    ) {}
                    }
                    NetworkTab.GALLERY -> GallerySection(Modifier.fillMaxSize())
                }
            }
            StatusToast(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}

/**
 * Campo de texto bajo y con etiqueta encima, para formularios que deben caber en pantallas de poca altura.
 */
@Composable
private fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    hidden: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }   // el "ojito": mostrar u ocultar el contenido de un campo secreto
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(modifier = Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize, color = Color.Black),
            cursorBrush = SolidColor(AgroGreen),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (hidden && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { field() }
                    if (hidden) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp).clickable { visible = !visible }
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF4F5F4))
                .border(BorderStroke(1.dp, Color(0xFFD6D9D6)), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
