package com.jesus.dronplataneras.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Colores y tipografía compartidos por todas las pantallas de AgroScan .
 *
 * Hay dos verdes por herencia de las dos generaciones de pantallas: [AgroGreen] (conexión, inicio,
 * cámara, red local) y [BrandGreen] (planificación, verificación, misión). Se conservan los dos para no
 * cambiar el aspecto de las pantallas existentes.
 */
internal val AgroGreen = Color(0xFF1E8E5A)
internal val DangerRed = Color(0xFFD9484A)
internal val DangerBg = Color(0xFFFBEAEA)
internal val CardBg = Color(0xFFF4F5F4)

internal val BrandGreen = Color(0xFF00704A)
internal val BrandGreenSoft = Color(0xFFE3F4EC)
internal val PageBg = Color(0xFFF6F8FA)
internal val PanelBorder = Color(0xFFE2E8F0)
internal val TextMuted = Color(0xFF64748B)
internal val TextStrong = Color(0xFF0F172A)
internal val WarnAmber = Color(0xFFF59E0B)
internal val WarnBg = Color(0xFFFFF8E6)
internal val NavyButton = Color(0xFF0F172A)

/** Cifras y coordenadas. */
internal val MonoFamily = FontFamily.Monospace
