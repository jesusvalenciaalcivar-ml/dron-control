package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Marca de la app: el mismo logo y el mismo nombre en todas las barras superiores.
 */
@Composable
internal fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("AgroScan", style = MaterialTheme.typography.titleMedium, color = BrandGreen, fontWeight = FontWeight.Bold)
    }
}

/**
 * Barra superior de las pantallas de conexión, inicio y NTRIP. Las de planificación, verificación y mapa usan [AgroScanTopBar].
 */
@Composable
internal fun AppTopBar(
    center: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(modifier = Modifier.align(Alignment.CenterStart)) { BrandMark() }
        center?.let { Box(modifier = Modifier.align(Alignment.Center)) { it() } }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
    HorizontalDivider()
}

/**
 * Estado de conexión del dron para la barra superior.
 */
@Composable
internal fun StatusPill(isConnected: Boolean) =
    Pill(if (isConnected) "ESTADO: LISTO" else "ESTADO: DESCONECTADO", if (isConnected) AgroGreen else DangerRed)

/**
 * Etiqueta redondeada con un punto del color de [color].
 */
@Composable
internal fun Pill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
