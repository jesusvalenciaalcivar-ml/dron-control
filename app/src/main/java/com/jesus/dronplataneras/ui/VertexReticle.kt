package com.jesus.dronplataneras.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.tan

/** Campo de visión vertical de la cámara del Mini 3 en video 16:9 (~24 mm equivalentes). Ajustar si la mira se corre al inclinar el gimbal. */
private const val MINI3_VERTICAL_FOV_DEG = 45.8

private const val RETICLE_SIZE_DP = 48f
private val OutOfFrameAmber = Color(0xFFFFB300)

/**
 * Cuánto baja, desde el centro de la imagen, el punto que queda justo bajo el dron, como fracción de la mitad de la altura de la imagen:
 * 0 = en el centro (cámara al suelo), 1 = justo en el borde de abajo, más de 1 = fuera de la imagen por abajo. null si no se conoce el
 * ángulo del gimbal. Modelo de cámara simple (sin distorsión): vale mientras el dron esté casi nivelado, como en hover.
 */
internal fun nadirDrop(gimbalPitchDeg: Double, verticalFovDeg: Double = MINI3_VERTICAL_FOV_DEG): Double? {
    if (gimbalPitchDeg.isNaN()) return null
    // Grados entre el eje de la cámara y la vertical; se limita a 89 porque a 90 la tangente se dispara
    val offAxis = (gimbalPitchDeg + 90).coerceIn(0.0, 89.0)
    return tan(Math.toRadians(offAxis)) / tan(Math.toRadians(verticalFovDeg / 2))
}

/**
 * Mira que señala sobre la cámara el punto que se marca como vértice. El vértice es la posición del dron, o sea el punto justo debajo de
 * él: con el gimbal al suelo cae en el centro de la imagen y, al inclinar la cámara, baja en la imagen ([nadirDrop]). Si queda fuera de la
 * imagen se pega al borde de abajo en ámbar y lo dice. Así la mira se mantiene siempre y no engaña.
 */
@Composable
internal fun BoxScope.VertexReticle(gimbalPitchDeg: Double, containerWidth: Dp, containerHeight: Dp) {
    val drop = nadirDrop(gimbalPitchDeg) ?: return
    // La imagen es 16:9 y se ajusta a la pantalla: en pantallas más anchas ocupa todo el alto, en las más angostas sobra arriba y abajo
    val halfImage = min(containerHeight.value, containerWidth.value * 9f / 16f) / 2f
    val bottomLimit = halfImage - RETICLE_SIZE_DP / 2 - 4f
    val outOfFrame = drop > 1.0
    val y = min((drop * halfImage).toFloat(), bottomLimit)
    val color = if (outOfFrame) OutOfFrameAmber else Color.White

    Canvas(Modifier.align(Alignment.Center).offset(y = y.dp).size(RETICLE_SIZE_DP.dp)) {
        // Contorno oscuro debajo del trazo para que se vea sobre cultivo claro y sobre sombra
        drawReticle(Color(0xCC000000), 5f)
        drawReticle(color, 2.5f)
        drawCircle(DangerRed, 3.5f, center)
    }
    // El rótulo va debajo de la mira; pegada al borde de abajo, lo pone encima para no salirse
    val labelY = if (y > bottomLimit - 40f) y - 36f else y + 36f
    Text(
        if (outOfFrame) "PUNTO BAJO EL DRON, FUERA DE LA IMAGEN" else "PUNTO A MARCAR",
        color = if (outOfFrame) OutOfFrameAmber else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.Center).offset(y = labelY.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Aro con cuatro marcas hacia afuera y hueco en el centro, para no tapar la esquina que se quiere ver. */
private fun DrawScope.drawReticle(color: Color, width: Float) {
    val r = size.minDimension * 0.28f
    val outer = size.minDimension * 0.5f
    drawCircle(color, r, style = Stroke(width))
    drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y - outer), width)
    drawLine(color, Offset(center.x, center.y + r), Offset(center.x, center.y + outer), width)
    drawLine(color, Offset(center.x - r, center.y), Offset(center.x - outer, center.y), width)
    drawLine(color, Offset(center.x + r, center.y), Offset(center.x + outer, center.y), width)
}
