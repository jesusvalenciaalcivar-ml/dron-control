package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.flight.LandingGuard
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager

/**
 * Barra (no un diálogo, para no tapar la cámara) con la decisión de aterrizar. Aparece cuando:
 *  - [LandingGuard] cortó el aterrizaje del regreso a casa a ~2 m, o
 *  - el propio dron pide confirmar el aterrizaje porque duda del terreno.
 * En los dos casos el dron está en hover y es el operador quien decide.
 */
@Composable
fun LandingDecisionBar(modifier: Modifier = Modifier) {
    val held by LandingGuard.holdingForDecision
    val confirmNeeded by DJIConnectionManager.landingConfirmationNeeded
    if (!held && !confirmNeeded) return

    Column(
        modifier
            .padding(12.dp)
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF5FFFFFF))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (confirmNeeded) "El dron pide confirmar el aterrizaje" else "Dron en hover a ~2 m de casa",
            fontWeight = FontWeight.Bold, color = TextStrong
        )
        Text(
            "El aterrizaje automático es impreciso: mira el suelo en la cámara. Aterriza solo si el punto está libre " +
                "(sin plantas, agua, cables ni personas).",
            style = MaterialTheme.typography.bodySmall, color = TextMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LandButton(compact = true, Modifier.weight(1f), text = "Aterrizar aquí") {
                if (confirmNeeded) DJIConnectionManager.confirmLanding { AppStatus.message.value = it }
                else FlightActions.landNow({ AppStatus.message.value = it })
                LandingGuard.dismiss()
            }
            SecondaryButton("Seguir en manual", null, Modifier.weight(1f)) {
                // Con la confirmación pendiente hay que cancelar el aterrizaje; si no, el dron ya está en hover
                if (confirmNeeded) DJIConnectionManager.cancelLanding { AppStatus.message.value = it }
                LandingGuard.dismiss()
            }
        }
    }
}
