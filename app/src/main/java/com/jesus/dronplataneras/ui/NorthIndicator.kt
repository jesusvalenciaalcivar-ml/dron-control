package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.jesus.dronplataneras.mission.Preflight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dónde cae el norte en el borde de una imagen de [w] x [h] tomada con la cámara mirando hacia abajo: es el punto del borde
 * en la dirección del norte, medido desde el centro (x a la derecha, y hacia abajo). La parte de arriba de la imagen apunta
 * hacia donde mira el dron ([yawDeg], 0 = norte, 90 = este), así que el norte queda girado -yaw respecto a "arriba".
 */
internal fun northEdgeOffset(w: Float, h: Float, yawDeg: Double): Pair<Float, Float> {
    val rad = Math.toRadians(yawDeg)
    val dx = -sin(rad)
    val dy = -cos(rad)
    val tx = if (abs(dx) < 1e-9) Double.MAX_VALUE else (w / 2) / abs(dx)
    val ty = if (abs(dy) < 1e-9) Double.MAX_VALUE else (h / 2) / abs(dy)
    val t = min(tx, ty)
    return (dx * t).toFloat() to (dy * t).toFloat()
}

/** La cámara mira al suelo (gimbal a -90°, con la misma tolerancia del chequeo pre-vuelo). Con otro ángulo el norte no cae en el borde. */
internal fun isNadir(gimbalPitchDeg: Double): Boolean =
    !gimbalPitchDeg.isNaN() && abs(gimbalPitchDeg + 90) <= Preflight.GIMBAL_TOLERANCE_DEG

/**
 * Marca "N" sobre el borde de la ventana de cámara (mitad dentro, mitad fuera del marco): se desliza por el borde para quedar
 * siempre del lado del norte aunque el dron gire. Solo se dibuja con el gimbal a nadir ([isNadir]), como en las misiones;
 * con la cámara inclinada o sin dato no se muestra, para no marcar un norte falso.
 * Va en el `overlay` de [DraggableThumbnail] para no quedar recortada por el marco.
 */
@Composable
internal fun BoxScope.NorthMarker(width: Dp, height: Dp, yawDeg: Double, gimbalPitchDeg: Double, size: Dp = 20.dp) {
    if (yawDeg.isNaN() || !isNadir(gimbalPitchDeg)) return
    val (dx, dy) = northEdgeOffset(width.value, height.value, yawDeg)
    Box(
        Modifier
            .align(Alignment.Center)
            .offset(dx.dp, dy.dp)
            .size(size)
            .clip(CircleShape)
            .background(DangerRed)
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color.White, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold)
    }
}
