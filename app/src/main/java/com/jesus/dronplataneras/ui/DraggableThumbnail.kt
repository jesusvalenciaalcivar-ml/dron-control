package com.jesus.dronplataneras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Ventana pequeña (cámara o mapa) que el operador puede arrastrar a cualquier esquina de la pantalla. Empieza abajo a la
 * izquierda y no sale de ([containerWidth] x [containerHeight]); [topInset] y [endInset] dejan libres la franja de arriba
 * (barra o telemetría) y la de la derecha (botones), para que la ventana nunca los tape.
 * Un toque sigue llegando al [content], así que sirve para intercambiar vistas. [overlay] se dibuja encima y sin recorte (puede salirse del marco). La posición sobrevive a rotaciones.
 */
@Composable
internal fun DraggableThumbnail(
    containerWidth: Dp,
    containerHeight: Dp,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    margin: Dp = 12.dp,
    topInset: Dp = 0.dp,
    endInset: Dp = margin,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    val minX = with(density) { margin.toPx() }
    val maxX = maxOf(minX, with(density) { (containerWidth - width - endInset).toPx() })
    val minY = with(density) { topInset.toPx() }
    val maxY = maxOf(minY, with(density) { (containerHeight - height - margin).toPx() })

    // NaN = todavía no se ha movido: abajo a la izquierda
    var x by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var y by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    val shownX = (if (x.isNaN()) minX else x).coerceIn(minX, maxX)
    val shownY = (if (y.isNaN()) maxY else y).coerceIn(minY, maxY)

    Box(
        modifier
            .offset { IntOffset(shownX.roundToInt(), shownY.roundToInt()) }
            .size(width, height)
            .pointerInput(minX, maxX, minY, maxY) {
                detectDragGestures { change, drag: Offset ->
                    change.consume()
                    x = ((if (x.isNaN()) minX else x) + drag.x).coerceIn(minX, maxX)
                    y = ((if (y.isNaN()) maxY else y) + drag.y).coerceIn(minY, maxY)
                }
            }
    ) {
        Box(
            Modifier.fillMaxSize().shadow(8.dp, shape).clip(shape).background(Color.Black).border(2.dp, Color.White, shape),
            content = content
        )
        // Va fuera del recorte del marco: sirve para marcas que se apoyan en el borde
        overlay()
    }
}
