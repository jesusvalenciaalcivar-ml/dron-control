package com.jesus.dronplataneras.network

/** Manejo de las imágenes de la cámara para la vista previa de la PC ([CameraFeed]). Funciones puras: no tocan el SDK ni Android. */
object CameraFrame {

    /**
     * Una imagen NV21 a la mitad de ancho y alto (toma un píxel de cada dos: barato y suficiente para mirar). Las medidas salen
     * pares porque NV21 guarda el color por bloques de 2×2.
     */
    fun halfNv21(src: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val w = (width / 2) and 1.inv()
        val h = (height / 2) and 1.inv()
        val out = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = src[(2 * y) * width + 2 * x]
        // Color: pares V,U por cada bloque de 2×2; se toma un bloque de cada dos en cada dirección
        val srcUv = width * height
        val outUv = w * h
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            val s = srcUv + (2 * cy) * width + (2 * cx) * 2
            val d = outUv + cy * w + cx * 2
            out[d] = src[s]
            out[d + 1] = src[s + 1]
        }
        return Triple(out, w, h)
    }
}
