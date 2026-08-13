package com.jesus.dronplataneras.ui

import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HoldToConfirmButton(
    text: String,
    color: Color,
    size: Dp,
    enabled: Boolean = true,
    holdDurationMillis: Int = 1200,
    onConfirm: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val baseColor = if (enabled) color else color.copy(alpha = 0.4f)
    val displayColor = if (isPressed) baseColor.copy(alpha = 0.7f) else baseColor

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 10.dp)) {
        if (progress > 0f) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(size + 10.dp),
                color = color,
                strokeWidth = 4.dp
            )
        }

        Surface(
            shape = CircleShape,
            color = displayColor,
            modifier = Modifier
                .size(size)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            Log.d("MyApp", "Botón '$text' presionado")   // ← nueva
                            isPressed = true
                            val job = scope.launch {
                                val steps = 30
                                val stepMillis = (holdDurationMillis / steps).toLong()
                                for (i in 1..steps) {
                                    delay(stepMillis)
                                    progress = i / steps.toFloat()
                                }
                                Log.d("MyApp", "Hold completado para '$text', ejecutando onConfirm")   // ← nueva
                                onConfirm()
                            }
                            tryAwaitRelease()
                            job.cancel()
                            progress = 0f
                            isPressed = false
                        }
                    )
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
            }
        }
    }
}