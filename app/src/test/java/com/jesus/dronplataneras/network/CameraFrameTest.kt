package com.jesus.dronplataneras.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFrameTest {

    @Test
    fun laMitadToma1PixelDeCada2YUnBloqueDeColorDeCada2() {
        // NV21 de 8×4: luz con valor = posición; color en pares V,U con valores 100+posición
        val w = 8
        val h = 4
        val src = ByteArray(w * h * 3 / 2)
        for (i in 0 until w * h) src[i] = i.toByte()
        for (i in 0 until w * h / 2) src[w * h + i] = (100 + i).toByte()

        val (out, ow, oh) = CameraFrame.halfNv21(src, w, h)
        assertEquals(4, ow)
        assertEquals(2, oh)
        assertEquals(ow * oh * 3 / 2, out.size)
        // Luz: filas 0 y 2, columnas 0, 2, 4, 6
        assertArrayEquals(byteArrayOf(0, 2, 4, 6, 16, 18, 20, 22), out.copyOfRange(0, 8))
        // Color: 1 fila de 2 bloques; de la fila 0 del color, los pares 0 y 2 (bytes 0,1 y 4,5)
        assertArrayEquals(byteArrayOf(100, 101, 104, 105), out.copyOfRange(8, 12))
    }

    @Test
    fun conMedidasImparesSalenPares() {
        val (out, ow, oh) = CameraFrame.halfNv21(ByteArray(1920 * 1080 * 3 / 2), 1920, 1080)
        assertEquals(960, ow)
        assertEquals(540, oh)
        assertEquals(960 * 540 * 3 / 2, out.size)
        val (_, w2, h2) = CameraFrame.halfNv21(ByteArray(1282 * 722 * 3 / 2), 1282, 722)   // la mitad sería 641×361: se baja a par
        assertEquals(640, w2)
        assertEquals(360, h2)
    }
}
