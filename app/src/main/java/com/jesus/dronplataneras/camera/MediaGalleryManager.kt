package com.jesus.dronplataneras.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.sdk.readable
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Archivos (fotos y videos) de la microSD del dron: listarlos, ver su miniatura y descargarlos al teléfono (Pictures/AgroScan y
 * Movies/AgroScan). Con el gestor del SDK activo la cámara no puede grabar ni volar: se activa solo mientras la galería se usa
 * ([loadMedia]) y se suelta con [release].
 */
object MediaGalleryManager {

    /** [percent] sobre los bytes realmente guardados; [bytesPerSecond] es la velocidad reciente (0 mientras no hay dato). */
    class Download(val name: String, val percent: Int, val bytesPerSecond: Long = 0)

    /** Descarga en curso (null si no hay). Un solo archivo a la vez. */
    val download = mutableStateOf<Download?>(null)

    private const val TAG = "MediaGallery"
    private val PHOTO_TYPES = setOf(MediaFileType.JPEG, MediaFileType.DNG, MediaFileType.TIFF, MediaFileType.PANORAMA)
    private val VIDEO_TYPES = setOf(MediaFileType.MP4, MediaFileType.MOV)

    private val NOOP = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {}
        override fun onFailure(error: IDJIError) {}
    }

    private var inUse = false
    private var active: Job? = null
    private val mediaManager get() = MediaDataCenter.getInstance().mediaManager

    fun isVideo(file: MediaFile) = file.fileType in VIDEO_TYPES

    fun loadMedia(onResult: (List<MediaFile>) -> Unit, onError: (String) -> Unit) {
        inUse = true
        mediaManager.enable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                mediaManager.pullMediaFileListFromCamera(
                    PullMediaFileListParam.Builder().build(),
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            val all = mediaManager.mediaFileListData.data ?: emptyList()
                            onResult(all.filter { it.fileType in PHOTO_TYPES || it.fileType in VIDEO_TYPES })
                        }
                        override fun onFailure(error: IDJIError) {
                            onError("Error al listar los archivos: ${error.readable()}")
                        }
                    }
                )
            }
            override fun onFailure(error: IDJIError) {
                onError("Error al activar la galería: ${error.readable()}")
            }
        })
    }

    /** La galería ya no se usa: devuelve la cámara a su modo normal (si hay una descarga en curso, lo hace al terminar). */
    fun release() {
        inUse = false
        disableIfIdle()
    }

    private fun disableIfIdle() {
        if (inUse || download.value != null) return
        mediaManager.disable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {
                Log.w(TAG, "disable: ${error.readable()}")
            }
        })
    }

    fun loadThumbnail(file: MediaFile, onResult: (Bitmap?) -> Unit) {
        file.pullThumbnailFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<Bitmap> {
            override fun onSuccess(bitmap: Bitmap) = onResult(bitmap)
            override fun onFailure(error: IDJIError) = onResult(null)
        })
    }

    fun loadPreview(file: MediaFile, onResult: (Bitmap?) -> Unit) {
        file.pullPreviewFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<Bitmap> {
            override fun onSuccess(bitmap: Bitmap) = onResult(bitmap)
            override fun onFailure(error: IDJIError) = onResult(null)
        })
    }

    /** Descarga el original al teléfono. [onResult] recibe el mensaje final (éxito o motivo del fallo). */
    fun download(context: Context, file: MediaFile, onResult: (String) -> Unit) {
        if (download.value != null) return onResult("Ya hay una descarga en curso")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return onResult("Descargar al teléfono requiere Android 10 o más")
        val video = isVideo(file)
        val folder = if (video) "Movies/AgroScan" else "Pictures/AgroScan"
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file.fileType))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)   // no aparece en la galería del teléfono hasta que termina
        }
        val uri = resolver.insert(collection, values) ?: return onResult("No se pudo crear el archivo en el teléfono")
        // Descriptor de archivo (no un flujo): cada trozo del SDK trae su posición y se escribe exactamente ahí
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: run {
            resolver.delete(uri, null, null)
            return onResult("No se pudo abrir el archivo en el teléfono")
        }
        val job = Job(file, pfd, uri, folder, context.applicationContext, onResult)
        active = job
        download.value = Download(file.fileName, 0)
        file.pullOriginalMediaFileFromCamera(0L, job)
    }

    /** Cancela la descarga en curso y borra el archivo a medias. */
    fun cancelDownload() {
        active?.let { it.file.stopPullOriginalMediaFileFromCamera(NOOP); it.fail("Descarga cancelada") }
    }

    private fun mimeOf(type: MediaFileType) = when (type) {
        MediaFileType.MP4 -> "video/mp4"
        MediaFileType.MOV -> "video/quicktime"
        MediaFileType.DNG -> "image/x-adobe-dng"
        MediaFileType.TIFF -> "image/tiff"
        else -> "image/jpeg"
    }

    /**
     * Recibe el archivo del SDK. Cada trozo llega con su posición en el archivo (`FileDataRequest.offset`), puede llegar desordenado
     * o repetido, y el aviso de "terminó" llega por otro hilo y también con códigos de error: por eso se escribe en la posición
     * de cada trozo, se espera a los trozos tardíos y solo se da por buena la descarga si se recibieron todos los bytes.
     */
    private class Job(
        val file: MediaFile,
        private val pfd: ParcelFileDescriptor,
        private val uri: Uri,
        private val folder: String,
        private val context: Context,
        private val onResult: (String) -> Unit
    ) : MediaFileDownloadListener {
        private val channel = FileOutputStream(pfd.fileDescriptor).channel
        private val lock = Any()
        private val tracker = ChunkTracker()
        private var done = false
        private var writeError: Exception? = null

        override fun onStart() {}

        // No se usa: sus dos números llegan en otro orden del que se esperaba y marcaba 100 % desde el primer instante.
        // El progreso se calcula con los bytes ya guardados (publishProgress).
        override fun onProgress(total: Long, current: Long) {}

        private var lastUiMs = SystemClock.elapsedRealtime()
        private var lastUiBytes = 0L

        /** Publica porcentaje y velocidad con lo ya guardado, como mucho cada [PROGRESS_EVERY_MS] para no recomponer la pantalla en cada trozo. */
        private fun publishProgress() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUiMs < PROGRESS_EVERY_MS) return
            val received = tracker.received
            val speed = (received - lastUiBytes) * 1000 / (now - lastUiMs)
            lastUiMs = now
            lastUiBytes = received
            download.value = Download(file.fileName, downloadPercent(received, file.fileSize), speed)
        }

        override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
            synchronized(lock) {
                if (done || writeError != null) return
                try {
                    val buffer = ByteBuffer.wrap(data)
                    var at = position
                    while (buffer.hasRemaining()) at += channel.write(buffer, at)
                    tracker.add(position, data.size)
                    publishProgress()
                } catch (e: Exception) {
                    writeError = e
                    file.stopPullOriginalMediaFileFromCamera(NOOP)
                }
            }
        }

        override fun onFinish() = verify(attempt = 0)

        /** Da por buena la descarga cuando ya llegó todo; si faltan bytes, da unos segundos a que lleguen los trozos tardíos. */
        private fun verify(attempt: Int) {
            synchronized(lock) {
                if (done) return
                writeError?.let { return failLocked("No se pudo guardar en el teléfono (¿sin espacio?): ${it.message}") }
                if (tracker.isComplete(file.fileSize)) return completeLocked()
                if (attempt >= MAX_VERIFY_ATTEMPTS) {
                    return failLocked("La descarga quedó incompleta (${tracker.received} de ${file.fileSize} bytes)")
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({ verify(attempt + 1) }, VERIFY_DELAY_MS)
        }

        override fun onFailure(error: IDJIError) = fail("Falló la descarga: ${error.readable()}")

        fun fail(message: String) = synchronized(lock) { failLocked(message) }

        private fun completeLocked() {
            done = true
            closeFile()
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, ready, null, null)
            end("Guardado en el teléfono: $folder/${file.fileName}")
        }

        private fun failLocked(message: String) {
            if (done) return
            done = true
            closeFile()
            context.contentResolver.delete(uri, null, null)
            end(message)
        }

        private fun closeFile() {
            runCatching { channel.close() }
            runCatching { pfd.close() }
        }

        private fun end(message: String) {
            active = null
            download.value = null
            disableIfIdle()
            onResult(message)
        }
    }

    private const val PROGRESS_EVERY_MS = 500L

    // Tras el aviso de "terminó" se espera hasta 5 s (10 x 500 ms) a que lleguen los últimos trozos
    private const val MAX_VERIFY_ATTEMPTS = 10
    private const val VERIFY_DELAY_MS = 500L
}
