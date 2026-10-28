package com.jesus.dronplataneras.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jesus.dronplataneras.camera.MediaGalleryManager
import com.jesus.dronplataneras.mission.MissionExecutor
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.sdk.runOnMain
import dji.v5.manager.datacenter.media.MediaFile

/**
 * Galería del dron: fotos y videos de la microSD, con descarga al teléfono. Es una vista de la tarjeta, no una segunda copia:
 * el dron solo graba en la microSD y lo que está en el teléfono es lo que se descargó aquí.
 */
@Composable
fun GallerySection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connected by DJIConnectionManager.isConnected
    val flying by DJIConnectionManager.isFlying
    val missionRunning = MissionExecutor.status.value.running
    val download by MediaGalleryManager.download
    var files by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MediaFile?>(null) }

    // Con el dron en el aire (o en misión) no se toca la tarjeta: el gestor de archivos del SDK interrumpiría la grabación
    val blocked = when {
        !connected -> "Conecta el dron para ver su galería"
        flying || missionRunning -> "Aterriza el dron para usar la galería"
        else -> null
    }

    LaunchedEffect(blocked == null) {
        if (blocked == null) {
            loading = true
            error = ""
            MediaGalleryManager.loadMedia(
                onResult = { list -> files = list; loading = false },
                onError = { message -> error = message; loading = false }
            )
        } else {
            MediaGalleryManager.release()
        }
    }
    DisposableEffect(Unit) { onDispose { MediaGalleryManager.release() } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        download?.let { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    val speed = if (d.bytesPerSecond > 0) " · %.2f MB/s".format(d.bytesPerSecond / 1_000_000.0) else ""
                    Text("Descargando ${d.name} · ${d.percent} %$speed", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = BrandGreen)
                }
                TextButton(onClick = { MediaGalleryManager.cancelDownload() }) { Text("Cancelar") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                blocked != null -> Text(blocked, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                loading -> CircularProgressIndicator(color = BrandGreen)
                error.isNotEmpty() -> Text(error, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                files.isEmpty() -> Text("No hay fotos ni videos en la tarjeta SD", color = TextMuted)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(files) { file ->
                        MediaThumbnail(
                            file,
                            canDownload = download == null,
                            onOpen = { selected = file },
                            onDownload = {
                                MediaGalleryManager.download(context, file) { message -> runOnMain { AppStatus.message.value = message } }
                            }
                        )
                    }
                }
            }
        }
    }

    selected?.let { MediaPreviewDialog(it, onDismiss = { selected = null }) }
}

@Composable
private fun MediaThumbnail(file: MediaFile, canDownload: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    var thumbnail by remember(file) { mutableStateOf(file.thumbNail) }
    LaunchedEffect(file) {
        if (thumbnail == null) MediaGalleryManager.loadThumbnail(file) { bitmap -> thumbnail = bitmap }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = file.fileName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)

        if (MediaGalleryManager.isVideo(file)) {
            Icon(Icons.Filled.PlayCircle, contentDescription = "Video", tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp))
        }
        IconButton(
            onClick = onDownload,
            enabled = canDownload,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color(0xAA000000))
        ) {
            Icon(Icons.Filled.Download, contentDescription = "Descargar al teléfono", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MediaPreviewDialog(file: MediaFile, onDismiss: () -> Unit) {
    var preview by remember(file) { mutableStateOf(file.thumbNail) }
    LaunchedEffect(file) {
        MediaGalleryManager.loadPreview(file) { bitmap -> if (bitmap != null) preview = bitmap }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            preview?.let {
                Image(it.asImageBitmap(), contentDescription = file.fileName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } ?: CircularProgressIndicator(color = Color.White)
        }
    }
}
