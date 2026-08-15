package com.jesus.dronplataneras.camera

import android.util.Log
import com.jesus.dronplataneras.sdk.readable
import com.jesus.dronplataneras.sdk.runOnMain
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.set

object CameraActions {

    fun takePhoto(onStatus: (String) -> Unit) {
        CameraKey.KeyCameraMode.create(ComponentIndexType.LEFT_OR_MAIN).set(
            CameraMode.PHOTO_NORMAL,
            onSuccess = {
                CameraKey.KeyStartShootPhoto.create(ComponentIndexType.LEFT_OR_MAIN).action(
                    onSuccess = {
                        Log.d("MyApp", "KeyStartShootPhoto onSuccess")
                        runOnMain { onStatus("Foto tomada") }
                    },
                    onFailure = { error ->
                        Log.e("MyApp", "KeyStartShootPhoto onFailure: $error")
                        runOnMain { onStatus("Error al tomar foto: ${error.readable()}") }
                    }
                )
            },
            onFailure = { error ->
                Log.e("MyApp", "KeyCameraMode onFailure: $error")
                runOnMain { onStatus("Error al preparar cámara: ${error.readable()}") }
            }
        )
    }
}
