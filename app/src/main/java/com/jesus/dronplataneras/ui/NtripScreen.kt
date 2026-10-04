package com.jesus.dronplataneras.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun NtripScreen(onBack: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var mountpoint by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF3F4F5))) {
        AppTopBar(
            title = "AgroScan",
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
            Pill("ESTADO: ESTACIÓN DESCONECTADA", DangerRed)
        }

        // Sin scroll: filas con weight, campos compactos para caber en pantallas bajas
        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Configuración NTRIP (Manual)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactField("Host / Dirección IP", host, { host = it }, Modifier.weight(2f), KeyboardType.Uri)
                    CompactField("Puerto", port, { port = it }, Modifier.weight(1f), KeyboardType.Number)
                    CompactField("Punto de Montaje (Mountpoint)", mountpoint, { mountpoint = it }, Modifier.weight(2f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactField("Usuario", user, { user = it }, Modifier.weight(1f))
                    CompactField("Contraseña", password, { password = it }, Modifier.weight(1f), KeyboardType.Password, hidden = true)
                }

                // ponytail: la conexión NTRIP real no está implementada; el botón solo muestra el diseño
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00704A)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CONECTAR ESTACIÓN", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    hidden: Boolean = false
) {
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
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
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
