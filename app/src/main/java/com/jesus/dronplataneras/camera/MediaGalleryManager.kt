package com.jesus.dronplataneras.camera

import android.graphics.Bitmap
import com.jesus.dronplataneras.sdk.readable
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.PullMediaFileListParam

object MediaGalleryManager {

    private val mediaManager get() = MediaDataCenter.getInstance().mediaManager

    fun loadPhotos(onResult: (List<MediaFile>) -> Unit, onError: (String) -> Unit) {
        mediaManager.enable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                mediaManager.pullMediaFileListFromCamera(
                    PullMediaFileListParam.Builder().build(),
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            onResult(mediaManager.mediaFileListData.data ?: emptyList())
                        }
                        override fun onFailure(error: IDJIError) {
                            onError("Error al listar fotos: ${error.readable()}")
                        }
                    }
                )
            }
            override fun onFailure(error: IDJIError) {
                onError("Error al activar galería: ${error.readable()}")
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
}
