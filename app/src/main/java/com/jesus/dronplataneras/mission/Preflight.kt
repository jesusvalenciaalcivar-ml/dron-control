package com.jesus.dronplataneras.mission

import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.planning.RouteChecks
import com.jesus.dronplataneras.telemetry.GpsQuality
import kotlin.math.abs

/** Todo lo que el chequeo pre-vuelo necesita saber, como datos simples (sin SDK ni UI). */
data class PreflightInput(
    val hasPlan: Boolean,
    val connected: Boolean,
    val satellites: Int,
    /** Calidad del enlace de control, 0–100. */
    val linkQuality: Int,
    val homeSet: Boolean,
    /** El dron está en el aire: ya no se ofrece fijar el punto de origen aquí. */
    val flying: Boolean = false,
    val batteryPct: Int,
    /** % de batería que pide esta salida, con la reserva incluida. */
    val batteryRequiredPct: Int,
    /** Inclinación del gimbal en grados; NaN si se desconoce. */
    val gimbalPitchDeg: Double = Double.NaN,
    /** Distancia máxima a la que la ruta sale del lote (m); 0 si queda dentro. */
    val routeOutsideM: Double = 0.0,
    /** Altura máxima de los waypoints (m). */
    val maxRouteAltitudeM: Double = 0.0,
    val captureMode: CaptureMode = CaptureMode.VIDEO,
    /** Segundos de video que caben aún en la microSD; NaN si se desconoce. */
    val videoSecondsAvailable: Double = Double.NaN,
    val videoSecondsRequired: Double = 0.0,
    /** El dron está volviendo a casa o aterrizando (p. ej. terminando la salida anterior). */
    val returningHome: Boolean = false,
    /** Estado de la microSD que informa la cámara (ver [SdCard]); vacío si todavía no informó. */
    val sdCardState: String = "",
    /** Fotos que caben aún en la microSD; NaN si se desconoce. */
    val photosAvailable: Double = Double.NaN,
    val photosRequired: Double = 0.0
)

/** Algo que el operador puede hacer desde el checklist para resolver un punto. */
enum class Fix {
    /** Fijar el punto de origen en la posición actual del dron. */
    SET_HOME_HERE
}

/**
 * Una línea del checklist: qué se revisó, qué se leyó y si impide despegar.
 */
data class PreflightItem(
    val label: String,
    val value: String,
    val ok: Boolean,
    /** Si falla, impide iniciar la salida. */
    val blocking: Boolean = true,
    /** Qué significa este punto y qué hacer si falla, en lenguaje del operador. */
    val help: String? = null,
    /** Acción que la app puede hacer por el operador para resolverlo. */
    val fix: Fix? = null
)

/**
 * Checklist previo al despegue, como lógica pura y probada. Lo usan la pantalla de verificación, [MissionRunner] y
 * [MissionExecutor], así que el criterio es el mismo en los tres sitios.
 */
object Preflight {

    const val MIN_LINK_QUALITY = 30
    const val GIMBAL_TOLERANCE_DEG = 5.0

    /**
     * Evalúa todas las condiciones y devuelve el checklist completo.
     */
    fun evaluate(i: PreflightInput, profile: DroneProfile = DroneProfile.MINI_3): List<PreflightItem> = buildList {
        add(PreflightItem("Ruta cargada", if (i.hasPlan) "Sí" else "No", i.hasPlan))
        add(PreflightItem("Dron conectado", if (i.connected) "Sí" else "No", i.connected))
        // No se puede iniciar otra salida mientras la anterior aún regresa o aterriza
        if (i.returningHome) add(PreflightItem("Regreso a casa", "En curso: espera a que aterrice", false))
        add(PreflightItem("GPS", "${i.satellites} satélites", i.satellites >= GpsQuality.MIN_SATELLITES))
        add(
            PreflightItem(
                "Enlace C2",
                when {
                    i.linkQuality >= 60 -> "Excelente"
                    i.linkQuality >= MIN_LINK_QUALITY -> "Aceptable"
                    else -> "Débil"
                },
                i.linkQuality >= MIN_LINK_QUALITY
            )
        )
        add(homeItem(i))
        add(
            PreflightItem(
                "Batería", "${i.batteryPct}% / requiere ${i.batteryRequiredPct}%",
                i.batteryPct >= i.batteryRequiredPct
            )
        )
        // El ejecutor lo pone a nadir antes de despegar; aquí solo se informa
        add(
            PreflightItem(
                "Gimbal a nadir (-90°)",
                if (i.gimbalPitchDeg.isNaN()) "Desconocido" else "%.0f°".format(i.gimbalPitchDeg),
                !i.gimbalPitchDeg.isNaN() && abs(i.gimbalPitchDeg + 90) <= GIMBAL_TOLERANCE_DEG,
                blocking = false
            )
        )
        val sd = SdCard.read(i.sdCardState, i.captureMode == CaptureMode.VIDEO)
        add(
            PreflightItem(
                "Tarjeta microSD", sd.text, sd.level == SdCard.Level.OK,
                blocking = sd.level == SdCard.Level.ERROR
            )
        )
        if (i.captureMode == CaptureMode.PHOTO) {
            val known = !i.photosAvailable.isNaN()
            add(
                PreflightItem(
                    "Espacio para fotos",
                    if (!known) "Sin dato" else "%.0f libres / %.0f necesarias".format(i.photosAvailable, i.photosRequired),
                    !known || i.photosAvailable >= i.photosRequired * 1.1,
                    blocking = known
                )
            )
        }
        if (i.captureMode == CaptureMode.VIDEO) {
            val known = !i.videoSecondsAvailable.isNaN()
            add(
                PreflightItem(
                    "Espacio en microSD",
                    if (!known) "Sin dato" else "%.0f min libres / %.0f min necesarios".format(
                        i.videoSecondsAvailable / 60, i.videoSecondsRequired / 60
                    ),
                    !known || i.videoSecondsAvailable >= i.videoSecondsRequired * 1.1,
                    // Si el dron no informa el espacio no se bloquea; si informa y no alcanza, sí
                    blocking = known
                )
            )
        }
        add(
            PreflightItem(
                "Ruta dentro del lote",
                if (i.routeOutsideM <= RouteChecks.MAX_OUTSIDE_M) "OK" else "%.0f m fuera".format(i.routeOutsideM),
                i.routeOutsideM <= RouteChecks.MAX_OUTSIDE_M
            )
        )
        add(
            PreflightItem(
                "Altura ≤ ${profile.maxAltitudeM.toInt()} m",
                if (i.hasPlan) "%.0f m".format(i.maxRouteAltitudeM) else "-",
                i.hasPlan && i.maxRouteAltitudeM <= profile.maxAltitudeM
            )
        )
    }

    /**
     * El punto de origen (Home): donde el dron aterriza y a donde vuelve con "Regresar a casa", si pierde el enlace o si la
     * batería baja. Lo fija el propio dron cuando tiene buen GPS; aquí se explica y, si hace falta, se puede fijar a mano.
     */
    private fun homeItem(i: PreflightInput): PreflightItem {
        val purpose = "Es el punto donde el dron aterriza y a donde vuelve con «Regresar a casa», si pierde el enlace o si la batería baja."
        return when {
            i.homeSet -> PreflightItem("Punto de origen (Home)", "Registrado", true, help = purpose)
            !i.connected -> PreflightItem(
                "Punto de origen (Home)", "Pendiente", false, help = "$purpose Conecta el dron: es él quien lo registra."
            )
            i.flying -> PreflightItem(
                "Punto de origen (Home)", "Pendiente", false,
                help = "$purpose El dron está en el aire sin punto de origen: aterriza y espera a que el GPS lo registre antes de iniciar la ruta."
            )
            i.satellites < GpsQuality.MIN_SATELLITES -> PreflightItem(
                "Punto de origen (Home)", "Pendiente", false,
                help = "$purpose El dron lo fija solo cuando tiene GPS suficiente (${i.satellites} de ${GpsQuality.MIN_SATELLITES} satélites). " +
                    "Colócalo en un lugar abierto, sin techos ni árboles encima, y espera unos segundos."
            )
            else -> PreflightItem(
                "Punto de origen (Home)", "Pendiente", false,
                help = "$purpose Ya hay GPS suficiente: el dron suele fijarlo solo en unos segundos. " +
                    "Si no aparece, usa «Fijar aquí» para tomar la posición actual del dron.",
                fix = Fix.SET_HOME_HERE
            )
        }
    }

    /**
     * Las condiciones que impiden despegar (las que fallan y son bloqueantes).
     */
    fun blockers(items: List<PreflightItem>): List<PreflightItem> = items.filter { it.blocking && !it.ok }
}
