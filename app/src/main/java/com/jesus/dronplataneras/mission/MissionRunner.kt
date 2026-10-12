package com.jesus.dronplataneras.mission

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.data.MisionCompleta
import com.jesus.dronplataneras.data.VueloCompleto
import com.jesus.dronplataneras.data.salidasVoladas
import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Coordina el vuelo de una misión completa, que puede necesitar varias baterías: cada [Sortie] se
 * vuela con [MissionExecutor], se guarda en el historial (vuelo → salidas) y el siguiente despegue
 * retoma donde quedó la ruta.
 *
 * El estado que sobrevive entre salidas vive en la base de datos (el [VueloCompleto] activo), no
 * aquí: si la app se cierra, el operador puede continuar el vuelo desde la pantalla de la misión.
 */
object MissionRunner {

    private const val TAG = "MissionRunner"

    /** Lo que la pantalla de ejecución necesita saber de la salida en curso. */
    data class RunContext(
        val misionId: Long,
        val misionNombre: String,
        val vueloId: Long,
        val salidaId: Long,
        /** Posición de la salida en el historial del vuelo (desde 0; cuenta también los intentos cancelados). */
        val salidaIndice: Int,
        /** Número de esta salida para el operador (1, 2, 3…) y total estimado; solo cuentan las que despegaron. */
        val salidaNumero: Int,
        val salidasTotal: Int,
        /** Índice, en la ruta completa, del primer waypoint de esta salida. */
        val waypointOffset: Int,
        val totalWaypoints: Int,
        /** Metros de ruta que ya quedaron atrás antes de esta salida, y de toda la ruta. */
        val distanciaPreviaM: Double,
        val distanciaTotalM: Double,
        val plan: WaypointPlan
    )

    val current = mutableStateOf<RunContext?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Despega una salida. Valida el pre-vuelo, abre (o continúa) el vuelo en el historial y lanza el
     * ejecutor. Devuelve false, avisando por [AppStatus], si no se pudo empezar.
     *
     * @param plan la ruta completa de la misión
     * @param vueloActivo el vuelo en curso de esta misión, o null para empezar uno nuevo
     * @param salidasEstimadas cuántas salidas faltan en total (para mostrar "Salida 2 de 3")
     */
    suspend fun startSortie(
        appContext: Context,
        repo: AgroRepository,
        mision: MisionCompleta,
        plan: WaypointPlan,
        vueloActivo: VueloCompleto?,
        sortie: Sortie,
        salidasEstimadas: Int,
        routeOutsideM: Double,
        profile: DroneProfile = DroneProfile.MINI_3
    ): Boolean {
        if (MissionExecutor.status.value.phase != MissionExecutor.Phase.IDLE) {
            AppStatus.message.value = "Ya hay una salida en curso"
            return false
        }
        val sub = plan.slice(sortie.from, sortie.to)
        val speed = plan.speedMs ?: profile.maxMissionSpeedMs
        val options = Options(
            requiredBatteryPct = sortie.batteryRequiredPct,
            routeOutsideM = routeOutsideM,
            videoSecondsRequired = if (plan.captureMode == CaptureMode.VIDEO) sub.lengthM() / speed else 0.0,
            photosRequired = if (plan.captureMode == CaptureMode.PHOTO && plan.photoEveryM > 0) sub.lengthM() / plan.photoEveryM + 2 else 0.0
        )
        // Mismo criterio que la pantalla y que el ejecutor; evita crear historial de una salida que no va a despegar
        val blockers = Preflight.blockers(Preflight.evaluate(livePreflightInput(sub, options), profile))
        if (blockers.isNotEmpty()) {
            AppStatus.message.value = "Pre-vuelo no cumplido: " + blockers.joinToString(", ") { it.label }
            return false
        }

        val vueloId = vueloActivo?.vuelo?.id ?: repo.iniciarVuelo(mision, plan, desdeWaypoint = sortie.from)
        val vuelo = repo.getVuelo(vueloId) ?: return false
        val indice = vueloActivo?.salidas?.size ?: 0
        // Carpeta de la app visible por USB (Android/data/<app>/files) para poder copiar el registro al escritorio; sin ella, la privada
        val logsRoot = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val csv = File(logsRoot, "vuelos/${vuelo.uuid}/salida-${indice + 1}.csv")
        val log = FlightLogWriter(csv)
        val bateriaInicio = TelemetryManager.telemetry.value.batteryPercent
        val salidaId = repo.abrirSalida(vueloId, indice, sortie.from, sortie.to, bateriaInicio, csv.absolutePath)

        val ctx = RunContext(
            misionId = mision.mision.id,
            misionNombre = mision.mision.nombre,
            vueloId = vueloId,
            salidaId = salidaId,
            salidaIndice = indice,
            salidaNumero = (vueloActivo?.salidasVoladas() ?: 0) + 1,
            salidasTotal = (vueloActivo?.salidasVoladas() ?: 0) + maxOf(salidasEstimadas, 1),
            waypointOffset = sortie.from,
            totalWaypoints = plan.waypoints.size,
            distanciaPreviaM = plan.lengthUpToM(sortie.from),
            distanciaTotalM = plan.lengthM(),
            plan = plan
        )
        current.value = ctx

        val started = MissionExecutor.start(sub, profile, options.copy(log = log)) { outcome ->
            scope.launch { closeSortie(repo, ctx, sortie, plan, outcome) }
        }
        if (!started) {
            log.close()
            repo.cerrarSalida(
                salidaId,
                AgroRepository.CierreSalida(false, "No se pudo iniciar la salida", bateriaInicio, null, null, 0.0, sortie.from)
            )
            current.value = null
            return false
        }
        return true
    }

    private suspend fun closeSortie(repo: AgroRepository, ctx: RunContext, sortie: Sortie, plan: WaypointPlan, outcome: Outcome) {
        try {
            val next = if (outcome.completed) sortie.to + 1
            else SortiePlanner.resumeIndex(plan, sortie.from + outcome.waypointIndex)
            repo.cerrarSalida(
                ctx.salidaId,
                AgroRepository.CierreSalida(
                    completada = outcome.completed,
                    motivo = outcome.message.ifBlank { outcome.reason.name },
                    bateriaFin = TelemetryManager.telemetry.value.batteryPercent,
                    videoInicioMs = outcome.videoStartMs,
                    videoFinMs = outcome.videoEndMs,
                    distanciaM = outcome.distanceM,
                    siguienteWaypoint = next
                )
            )
        } catch (e: Exception) {
            // El vuelo ya terminó; si no se pudo guardar, al menos que el operador lo sepa
            Log.e(TAG, "No se pudo guardar el cierre de la salida", e)
            AppStatus.message.value = "No se pudo guardar el historial de la salida: ${e.message}"
        }
    }

    /** Cierra el panel de la salida terminada y deja la pantalla lista para el siguiente paso. */
    fun dismiss() {
        MissionExecutor.reset()
        if (!MissionExecutor.status.value.running) current.value = null
    }
}
