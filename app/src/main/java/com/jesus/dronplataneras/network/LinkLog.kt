package com.jesus.dronplataneras.network

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de lo que pasa con el enlace a la PC (Wi-Fi, telemetría, cámara, envíos), para revisarlo después de volar: una línea
 * por evento en `Android/data/<app>/files/logs/enlace-pc-AAAA-MM-DD.log` (un archivo por día). [FlightLogSync] los manda a la PC,
 * que los guarda en `Documentos\AgroScan\logs\telefono\` junto a los suyos. Nunca falla hacia afuera.
 */
object LinkLog {

    private const val TAG = "LinkLog"
    val FILE_NAME = Regex("""enlace-pc-\d{4}-\d{2}-\d{2}\.log""")

    @Volatile private var dir: File? = null
    private val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dia = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Se llama una vez al abrir la app. */
    fun init(context: Context) {
        dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
    }

    @Synchronized
    fun log(tema: String, mensaje: String) {
        Log.i(TAG, "[$tema] $mensaje")
        val d = dir ?: return
        runCatching {
            val now = Date()
            File(d, "enlace-pc-${dia.format(now)}.log").appendText("${hora.format(now)} [$tema] $mensaje\n")
        }
    }

    /** Los registros de los últimos 7 días, para mandarlos a la PC. */
    fun recentFiles(): List<File> =
        dir?.listFiles { f -> FILE_NAME.matches(f.name) }?.sortedBy { it.name }?.takeLast(7).orEmpty()
}
