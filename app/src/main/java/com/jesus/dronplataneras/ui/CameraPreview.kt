package com.jesus.dronplataneras.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture, width: Int, height: Int
                    ) {
                        val surface = Surface(texture)
                        MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
                            ComponentIndexType.LEFT_OR_MAIN,
                            surface,
                            width,
                            height,
                            ICameraStreamManager.ScaleType.CENTER_INSIDE
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture, width: Int, height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        MediaDataCenter.getInstance().cameraStreamManager
                            .removeCameraStreamSurface(Surface(texture))
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        }
    )
}