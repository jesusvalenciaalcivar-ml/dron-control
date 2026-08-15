package com.jesus.dronplataneras.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jesus.dronplataneras.camera.MediaGalleryManager
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.v5.manager.datacenter.media.MediaFile

private val photoFileTypes = setOf(
    MediaFileType.JPEG, MediaFileType.DNG, MediaFileType.TIFF, MediaFileType.PANORAMA
)

@Composable
fun GalleryScreen(onBack: () -> Unit) {
    var photos by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selectedPhoto by remember { mutableStateOf<MediaFile?>(null) }

    LaunchedEffect(Unit) {
        MediaGalleryManager.loadPhotos(
            onResult = { files ->
                photos = files.filter { it.fileType in photoFileTypes }
                loading = false
            },
            onError = { message ->
                error = message
                loading = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Volver") }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Galería del dron", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay fotos en la tarjeta SD")
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(photos) { photo -> PhotoThumbnail(photo, onClick = { selectedPhoto = photo }) }
            }
        }
    }

    selectedPhoto?.let { photo ->
        PhotoPreviewDialog(photo, onDismiss = { selectedPhoto = null })
    }
}

@Composable
private fun PhotoThumbnail(photo: MediaFile, onClick: () -> Unit) {
    var thumbnail by remember(photo) { mutableStateOf(photo.thumbNail) }

    LaunchedEffect(photo) {
        if (thumbnail == null) {
            MediaGalleryManager.loadThumbnail(photo) { bitmap -> thumbnail = bitmap }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color.DarkGray)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = photo.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PhotoPreviewDialog(photo: MediaFile, onDismiss: () -> Unit) {
    var preview by remember(photo) { mutableStateOf(photo.thumbNail) }

    LaunchedEffect(photo) {
        MediaGalleryManager.loadPreview(photo) { bitmap -> if (bitmap != null) preview = bitmap }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = photo.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } ?: CircularProgressIndicator(color = Color.White)
        }
    }
}
