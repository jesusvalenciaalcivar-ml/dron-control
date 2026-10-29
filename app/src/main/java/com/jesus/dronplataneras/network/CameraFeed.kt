package com.jesus.dronplataneras.network

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import com.jesus.dronplataneras.mission.MissionExecutor
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vista previa de la cámara del dron para la PC mientras se vuela una salida de misión: ~4 imágenes por segundo, a la mitad de
 * resolución y en JPEG, por HTTP al mismo puerto de los registros de vuelo (así la PC no pide otro permiso del Firewall). Es solo
 * para mirar: el video bueno sigue grabándose en la microSD. Formato en docs/PROTOCOLO_PC.md.
 *
 * Cuida el vuelo y la batería:
 * - Solo captura con la misión en curso, el teléfono conectado a la PC y la pantalla de Telemetría de la PC abierta: la PC lo dice
 *   en cada respuesta. Mientras no mira, el teléfono solo le pregunta cada 3 s y no le pide imágenes al SDK.
 * - El callback del SDK solo copia la imagen si toca y no hay otra en camino; achicar, comprimir y enviar va en otro hilo. Si la
 *   red o el teléfono van lentos se pierden imágenes, nunca se acumulan.
 */
object CameraFeed {

    private const val TAG = "CameraFeed"
    private const val EVERY_MS = 250L
    private const val PROBE_MS = 3_000L
    private const val JPEG_QUALITY = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var pcWatching = false
    @Volatile private var listening = false
    @Volatile private var lastFrameAt = 0L
    private val sending = AtomicBoolean(false)
    @Volatile private var sent = 0
    @Volatile private var failed = 0
    private var minuteStart = 0L

    private val listener = ICameraStreamManager.CameraFrameListener { data, offset, length, width, height, format ->
        val now = SystemClock.elapsedRealtime()
        if (format != ICameraStreamManager.FrameFormat.NV21 || length < width * height * 3 / 2) return@CameraFrameListener
        if (!pcWatching || now - lastFrameAt < EVERY_MS || !sending.compareAndSet(false, true)) return@CameraFrameListener
        lastFrameAt = now
        val frame = data.copyOfRange(offset, offset + length)   // el SDK reutiliza el arreglo al volver de aquí
        scope.launch {
            try {
                val (small, w, h) = CameraFrame.halfNv21(frame, width, height)
                pcWatching = post(jpeg(small, w, h))
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo enviar la imagen: ${e.message}")
                if (failed++ == 0) LinkLog.log("camara", "No se pudo enviar una imagen a la PC: ${e.message}")
                pcWatching = false   // vuelve a preguntar antes de seguir mandando
            } finally {
                sending.set(false)
            }
        }
    }

    /** Se llama una vez al abrir la app; llamarla de nuevo no hace nada. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val active = PcLink.state.value == PcLink.State.CONNECTED && MissionExecutor.status.value.running
                if (!active) pcWatching = false
                else if (!pcWatching) pcWatching = runCatching { ask() }.getOrDefault(false)
                setListening(active && pcWatching)
                val now = SystemClock.elapsedRealtime()
                if (minuteStart == 0L) minuteStart = now
                if (now - minuteStart >= 60_000) {
                    if (sent > 0 || failed > 0) LinkLog.log("camara", "Último minuto: $sent imágenes enviadas a la PC, $failed fallaron")
                    minuteStart = now
                    sent = 0
                    failed = 0
                }
                delay(if (active && !pcWatching) PROBE_MS else 1_000L)
            }
        }
    }

    // Pedir imágenes al SDK lo obliga a decodificarlas aparte: solo mientras alguien las mira
    private fun setListening(on: Boolean) {
        if (on == listening) return
        runCatching {
            val streams = MediaDataCenter.getInstance().cameraStreamManager
            if (on) streams.addFrameListener(ComponentIndexType.LEFT_OR_MAIN, ICameraStreamManager.FrameFormat.NV21, listener)
            else streams.removeFrameListener(listener)
            listening = on
            LinkLog.log("camara", if (on) "La PC mira la cámara: se piden imágenes al dron" else "Se dejan de pedir imágenes al dron")
        }.onFailure { Log.w(TAG, "No se pudo ${if (on) "pedir" else "soltar"} la imagen de la cámara: ${it.message}") }
    }

    private fun jpeg(nv21: ByteArray, w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().also { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, it) }
            .toByteArray()

    /** ¿La PC quiere imágenes? (su pantalla de Telemetría está abierta) */
    private fun ask(): Boolean {
        val c = FlightLogSync.connection("/camara", timeoutMs = 2_000)
        try {
            return c.responseCode == 200 && JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("seguir")
        } finally {
            c.disconnect()
        }
    }

    /** Envía una imagen; devuelve si la PC quiere más. */
    private fun post(jpeg: ByteArray): Boolean {
        val c = FlightLogSync.connection("/camara", timeoutMs = 2_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "image/jpeg")
            c.setFixedLengthStreamingMode(jpeg.size)
            c.outputStream.use { it.write(jpeg) }
            return c.responseCode == 200 && JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("seguir")
        } finally {
            c.disconnect()
        }
    }
}
