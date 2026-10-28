package com.jesus.dronplataneras.camera

import android.util.Log
import com.jesus.dronplataneras.sdk.detail
import com.jesus.dronplataneras.sdk.readable
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.camera.CameraFlatMode
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraShootPhotoMode
import dji.sdk.keyvalue.value.camera.VideoFrameRate
import dji.sdk.keyvalue.value.camera.VideoResolution
import dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.set

/** Órdenes a la cámara del dron que comparten la misión en vuelo y la prueba de cámara en tierra. */
object CameraControl {

    private const val TAG = "CameraControl"
    private val cam = ComponentIndexType.LEFT_OR_MAIN

    /**
     * Pide el modo de la cámara (video o foto) y, ya cambiado, sus ajustes: 4K a 30 fps en video; disparo simple
     * (sin ráfaga, HDR ni intervalo) en foto. [onSettingError] recibe el paso que falló ("modo", "resolucion" o
     * "modo_disparo") y el detalle del SDK; [onModeAccepted] se llama cuando el SDK aceptó el cambio de modo.
     */
    fun setMode(video: Boolean, onSettingError: (step: String, detail: String) -> Unit, onModeAccepted: () -> Unit = {}) {
        // El Mini 3 cambia de modo por "modo plano" (KeyCameraFlatMode). El cambio clásico (KeyCameraMode) lo rechaza siempre con
        // NEED_REQUEST_AGAIN / CameraFlatMode:-15 (visto en 4 salidas en modo foto); se deja como respaldo para otros modelos.
        CameraKey.KeyCameraFlatMode.create(cam).set(
            if (video) CameraFlatMode.VIDEO_NORMAL else CameraFlatMode.PHOTO_NORMAL,
            onSuccess = { applyModeSettings(video, onSettingError, onModeAccepted) },
            onFailure = { flatError ->
                Log.w(TAG, "Modo plano: ${flatError.readable()}")
                CameraKey.KeyCameraMode.create(cam).set(
                    if (video) CameraMode.VIDEO_NORMAL else CameraMode.PHOTO_NORMAL,
                    onSuccess = { applyModeSettings(video, onSettingError, onModeAccepted) },
                    onFailure = { e ->
                        Log.w(TAG, "Modo de cámara: ${e.readable()}")
                        onSettingError("modo", "${flatError.detail()} | clásico: ${e.detail()}")
                    }
                )
            }
        )
    }

    /** Ajustes que van después de aceptado el modo: resolución en video; disparo simple en foto. */
    private fun applyModeSettings(video: Boolean, onSettingError: (step: String, detail: String) -> Unit, onModeAccepted: () -> Unit) {
        onModeAccepted()
        if (video) {
            // Perfil del Mini 3: 3840x2160 a 30 fps
            CameraKey.KeyVideoResolutionFrameRate.create(cam).set(
                VideoResolutionFrameRate(VideoResolution.RESOLUTION_3840x2160, VideoFrameRate.RATE_30FPS),
                onSuccess = {},
                onFailure = { e ->
                    Log.w(TAG, "Resolución de video: ${e.readable()}")
                    onSettingError("resolucion", e.detail())
                }
            )
        } else {
            CameraKey.KeyShootPhotoMode.create(cam).set(
                CameraShootPhotoMode.NORMAL,
                onSuccess = {},
                onFailure = { e ->
                    Log.w(TAG, "Modo de disparo: ${e.readable()}")
                    onSettingError("modo_disparo", e.detail())
                }
            )
        }
    }

    /** Pide una foto. [onResult] recibe null si la cámara aceptó la orden o el motivo del rechazo (no prueba que se guardara). */
    fun shootPhoto(onResult: (error: String?) -> Unit) {
        CameraKey.KeyStartShootPhoto.create(cam).action(
            onSuccess = { onResult(null) },
            onFailure = { e ->
                Log.w(TAG, "Foto: ${e.readable()}")
                onResult(e.detail())
            }
        )
    }
}
