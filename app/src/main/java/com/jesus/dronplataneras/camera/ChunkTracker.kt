package com.jesus.dronplataneras.camera

/**
 * Cuenta los bytes ya recibidos de un archivo que el SDK entrega en trozos, cada uno con su posición en el archivo.
 * Los trozos pueden llegar desordenados o repetidos (reintentos): un trozo repetido en la misma posición no suma dos veces.
 * shortcut: no detecta trozos que se solapan parcialmente; si el SDK llegara a hacerlo, comparar rangos en vez de posiciones.
 */
class ChunkTracker {

    private val chunks = HashMap<Long, Int>()

    /** Bytes recibidos. Se lleva al día en cada trozo: se consulta a cada trozo para el progreso y sumar todo cada vez sería cuadrático. */
    var received: Long = 0
        private set

    fun add(offset: Long, length: Int) {
        received += length - (chunks.put(offset, length) ?: 0)
    }

    /** Completo si se recibió exactamente [expected] bytes (si no se conoce el tamaño, basta con haber recibido algo). */
    fun isComplete(expected: Long) = if (expected <= 0) received > 0 else received == expected
}

/** Porcentaje (0–100) de lo recibido sobre el tamaño del archivo; 0 si el tamaño no se conoce. */
internal fun downloadPercent(received: Long, total: Long): Int =
    if (total <= 0) 0 else (received * 100 / total).toInt().coerceIn(0, 100)
