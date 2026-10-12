package com.jesus.dronplataneras.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jesus.dronplataneras.map.NetworkMonitor
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.delay

/** Barra superior con telemetría compacta, como en los diseños. */
@Composable
fun AgroScanTopBar(
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { ConnectionPill() }
) {
    val t by TelemetryManager.telemetry
    val connected by DJIConnectionManager.isConnected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextStrong)
            }
            Spacer(Modifier.width(4.dp))
        }
        BrandMark()
        Spacer(Modifier.width(20.dp))
        TopStat(Icons.Filled.Satellite, if (connected) "${t.gpsSatelliteCount}" else "--", "SATS")
        TopStat(Icons.Filled.Height, if (connected) "%.1f".format(t.altitude) else "--", "m")
        TopStat(
            Icons.Filled.BatteryFull, if (connected) "${t.batteryPercent}" else "--", "%",
            valueColor = when {
                !connected -> TextStrong
                t.batteryPercent < 30 -> DangerRed
                t.batteryPercent < 60 -> WarnAmber
                else -> BrandGreen
            }
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
    HorizontalDivider(color = PanelBorder)
}

@Composable
private fun TopStat(icon: ImageVector, value: String, unit: String, valueColor: Color = TextStrong) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, fontWeight = FontWeight.Bold, color = valueColor, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(3.dp))
        Text(unit, color = TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Estado de la conexión con el dron y de internet.
 */
@Composable
fun ConnectionPill() {
    val connected by DJIConnectionManager.isConnected
    val online by NetworkMonitor.isOnline
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!online) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFF1F5F9)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sin internet", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Spacer(Modifier.width(8.dp))
        }
        Pill(if (connected) "DRON CONECTADO" else "DESCONECTADO", if (connected) BrandGreen else DangerRed)
    }
}

/** Tarjeta blanca con borde suave, base de los paneles de los diseños. */
@Composable
fun AgroCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(6.dp, RoundedCornerShape(14.dp)) else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
            .padding(padding),
        content = content
    )
}

/**
 * Rótulo de sección en mayúsculas.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = TextMuted,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp
    )
}

/**
 * Botón principal relleno.
 */
@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    color: Color = BrandGreen,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Botón secundario con borde.
 */
@Composable
fun SecondaryButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    contentColor: Color = TextStrong,
    borderColor: Color = PanelBorder,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** Mensaje de estado flotante (AppStatus), se oculta solo a los 5 s. */
@Composable
fun StatusToast(modifier: Modifier = Modifier) {
    val msg by AppStatus.message
    LaunchedEffect(msg) {
        if (msg.isNotEmpty()) {
            delay(5000)
            AppStatus.message.value = ""
        }
    }
    if (msg.isNotEmpty()) {
        Text(
            msg,
            modifier = modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE6111827))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
    }
}

/**
 * Punto de color de estado.
 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Métrica grande en monoespaciada, como en el resumen de misión. */
@Composable
fun MetricTile(label: String, value: String, unit: String, caption: String? = null, modifier: Modifier = Modifier, valueColor: Color = TextStrong) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PageBg)
            .border(1.dp, PanelBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = valueColor)
            Spacer(Modifier.width(4.dp))
            Text(unit, fontFamily = MonoFamily, color = TextMuted, fontSize = 13.sp)
        }
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}
