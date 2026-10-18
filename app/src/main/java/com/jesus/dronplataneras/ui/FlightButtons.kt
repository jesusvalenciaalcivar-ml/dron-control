package com.jesus.dronplataneras.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Botón grande de acción de vuelo: la misma forma, altura y tipografía en todas las pantallas. [compact] para pantallas bajas.
 */
@Composable
internal fun BigButton(
    text: String,
    icon: ImageVector,
    color: Color,
    compact: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 48.dp else 56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.45f), disabledContentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 20.dp else 26.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Regresar a casa" (RTH): igual en la misión en vuelo y en el control manual. */
@Composable
internal fun ReturnHomeButton(compact: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) =
    BigButton("Regresar a casa", Icons.Filled.Home, NavyButton, compact, modifier, onClick = onClick)

/** "Aterrizar": igual en la misión en vuelo y en la decisión de aterrizaje. [text] solo cambia la palabra, no el aspecto. */
@Composable
internal fun LandButton(compact: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), text: String = "Aterrizar", onClick: () -> Unit) =
    BigButton(text, Icons.Filled.FlightLand, DangerRed, compact, modifier, onClick = onClick)
