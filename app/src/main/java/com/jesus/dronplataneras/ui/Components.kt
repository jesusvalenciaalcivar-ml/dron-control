package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val AgroGreen = Color(0xFF1E8E5A)
internal val DangerRed = Color(0xFFD9484A)
internal val DangerBg = Color(0xFFFBEAEA)
internal val CardBg = Color(0xFFF4F5F4)

@Composable
internal fun AppTopBar(
    title: String,
    center: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = AgroGreen, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = AgroGreen, fontWeight = FontWeight.Bold)
        }
        center?.let { Box(modifier = Modifier.align(Alignment.Center)) { it() } }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
    HorizontalDivider()
}

@Composable
internal fun StatusPill(isConnected: Boolean) =
    Pill(if (isConnected) "ESTADO: LISTO" else "ESTADO: DESCONECTADO", if (isConnected) AgroGreen else DangerRed)

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
