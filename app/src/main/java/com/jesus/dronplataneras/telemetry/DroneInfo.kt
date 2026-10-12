package com.jesus.dronplataneras.telemetry

import androidx.compose.runtime.mutableStateOf
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager

/** Datos del dron que necesita el checklist pre-vuelo y que TelemetryManager no cubre. */
object DroneInfo {

    /** Nombre del modelo según el SDK (p. ej. "DJI_MINI_3"); vacío si no hay dron. */
    val productName = mutableStateOf("")

    /** Inclinación del gimbal en grados (-90 = cámara apuntando al suelo). NaN si se desconoce. */
    val gimbalPitch = mutableStateOf(Double.NaN)

    val motorsOn = mutableStateOf(false)

    /** Rumbo del dron en grados brújula (yaw, 0 = norte). NaN si se desconoce. Para la flecha del mapa. */
    val heading = mutableStateOf(Double.NaN)

    /** Segundos de video que aún caben en la microSD del dron. NaN si se desconoce. */
    val videoSecondsAvailable = mutableStateOf(Double.NaN)

    /** Fotos que aún caben en la microSD del dron. NaN si se desconoce. */
    val photosAvailable = mutableStateOf(Double.NaN)

    /**
     * Estado de la microSD según la cámara (NORMAL, NOT_INSERTED, READ_ONLY, FULL, SLOW,
     * WRITING_SLOWLY, FORMAT_NEEDED, ...). Vacío si todavía no informó.
     */
    val sdCardState = mutableStateOf("")

    /** Modo actual de la cámara (PHOTO_NORMAL, VIDEO_NORMAL, ...). Vacío si se desconoce. */
    val cameraMode = mutableStateOf("")

    /** La cámara informa que ahora mismo no permite tomar fotos (tarjeta ausente, modo equivocado, ocupada...). */
    val shootPhotoNotAllowed = mutableStateOf(false)

    /**
     * Empieza a escuchar el modelo, el gimbal, los motores, el rumbo y el estado de la cámara y la microSD.
     */
    fun startListening() {
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(CameraKey.KeyCameraStorageInfos, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            val info = v?.currentCameraStorageInfo
            // 0 puede significar "aún no leí la tarjeta"; una tarjeta realmente llena la detecta sdCardState (FULL)
            videoSecondsAvailable.value = info?.availableVideoDuration?.takeIf { it > 0 }?.toDouble() ?: Double.NaN
            photosAvailable.value = info?.availablePhotoCount?.takeIf { it > 0 }?.toDouble() ?: Double.NaN
        }
        km.listen(KeyTools.createKey(CameraKey.KeyCameraSDCardState, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            sdCardState.value = v?.name ?: ""
        }
        km.listen(KeyTools.createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            cameraMode.value = v?.name ?: ""
        }
        km.listen(KeyTools.createKey(CameraKey.KeyShootPhotoNotAllowed, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            shootPhotoNotAllowed.value = v ?: false
        }
        km.listen(KeyTools.createKey(ProductKey.KeyProductType), this) { _, v ->
            productName.value = v?.name ?: ""
        }
        km.listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            gimbalPitch.value = v?.pitch ?: Double.NaN
        }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn), this) { _, v ->
            motorsOn.value = v ?: false
        }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this) { _, v ->
            heading.value = v?.yaw ?: Double.NaN
        }
    }

    /**
     * Deja de escuchar las llaves de este objeto.
     */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
    }

    /** "DJI_MINI_3" -> "DJI Mini 3" */
    fun prettyName(raw: String): String =
        raw.split('_').joinToString(" ") { part ->
            if (part.equals("DJI", true)) "DJI" else part.lowercase().replaceFirstChar { it.uppercase() }
        }
}
