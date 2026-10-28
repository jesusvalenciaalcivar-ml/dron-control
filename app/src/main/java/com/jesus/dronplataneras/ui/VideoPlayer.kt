package com.jesus.dronplataneras.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jesus.dronplataneras.camera.MediaGalleryManager
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.VideoPlayState
import kotlinx.coroutines.delay

/** Si en este tiempo el dron no empieza a mandar video, se avisa en vez de dejar el círculo girando. */
private const val START_TIMEOUT_MS = 15_000L

/** Ya llegó algo del dron: duración conocida o un estado de reproducción real (no solo "cargando"). */
private fun hasStarted(p: MediaGalleryManager.Playback?): Boolean =
    p != null && (p.durationS > 0 || p.state == VideoPlayState.PLAYING || p.state == VideoPlayState.PAUSED || p.state == VideoPlayState.ENDED)

private fun mmss(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Reproduce un video de la microSD del dron **sin descargarlo**, en la baja resolución que manda el propio dron (como "ver en baja
 * resolución" de DJI Fly). Sirve para videos 4K que el teléfono no puede decodificar. Al salir de pantalla detiene la reproducción.
 */
@Composable
fun DroneVideoPlayer(file: MediaFile, modifier: Modifier = Modifier) {
    val status by MediaGalleryManager.playback
    val error by MediaGalleryManager.playbackError
    var dragging by remember(file) { mutableStateOf<Float?>(null) }
    var timedOut by remember(file) { mutableStateOf(false) }
    val started = hasStarted(status)

    LaunchedEffect(file) {
        delay(START_TIMEOUT_MS)
        if (!hasStarted(MediaGalleryManager.playback.value)) timedOut = true
    }
    DisposableEffect(file) { onDispose { MediaGalleryManager.stopPlayback() } }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).apply {
                        var surface: Surface? = null
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture).also { MediaGalleryManager.playOnSurface(file, it, width, height) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                MediaGalleryManager.stopPlayback()
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                    }
                }
            )
            when {
                error.isNotEmpty() -> Text("No se pudo reproducir: $error", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                timedOut && !started -> Text(
                    "El dron no respondió. Cierra y vuelve a intentarlo; si sigue igual, descarga el video y míralo en la PC.",
                    color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(16.dp)
                )
                !started -> CircularProgressIndicator(color = Color.White)
            }
        }

        val duration = status?.durationS ?: 0.0
        val position = status?.positionS ?: 0.0
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = started,
                onClick = {
                    when (status?.state) {
                        VideoPlayState.PLAYING -> MediaGalleryManager.pausePlayback()
                        // shortcut: al terminar se vuelve al inicio con seek(0) + reanudar; no probado con el dron, ajustar si el SDK no reinicia así
                        VideoPlayState.ENDED, VideoPlayState.STOPPED -> {
                            MediaGalleryManager.seekPlayback(0)
                            MediaGalleryManager.resumePlayback()
                        }
                        else -> MediaGalleryManager.resumePlayback()
                    }
                }
            ) {
                Icon(
                    if (status?.state == VideoPlayState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (status?.state == VideoPlayState.PLAYING) "Pausar" else "Reproducir", tint = Color.White
                )
            }
            Slider(
                value = dragging ?: if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                // El SDK mueve la reproducción en segundos enteros
                onValueChangeFinished = {
                    dragging?.let { MediaGalleryManager.seekPlayback((it * duration).toInt()) }
                    dragging = null
                },
                enabled = started && duration > 0,
                colors = SliderDefaults.colors(thumbColor = BrandGreen, activeTrackColor = BrandGreen),
                modifier = Modifier.weight(1f)
            )
            Text("${mmss(position)} / ${mmss(duration)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
        }
    }
}
