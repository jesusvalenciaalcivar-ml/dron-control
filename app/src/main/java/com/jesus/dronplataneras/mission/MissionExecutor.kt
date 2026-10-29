package com.jesus.dronplataneras.mission

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.jesus.dronplataneras.camera.CameraControl
import com.jesus.dronplataneras.flight.FlightActions
import com.jesus.dronplataneras.planning.DroneProfile
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.sdk.detail
import com.jesus.dronplataneras.sdk.readable
import com.jesus.dronplataneras.sdk.runOnMain
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.LocalFrame
import com.jesus.dronplataneras.survey.XY
import com.jesus.dronplataneras.survey.distanceTo
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.jesus.dronplataneras.telemetry.TelemetryManager
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Ejecuta una [WaypointPlan] (la subruta de una salida) con Virtual Stick.
 *
 * El DJI Mini 3 NO soporta misiones de waypoints nativas en MSDK v5 (no se puede subir un KMZ al
 * controlador de vuelo). La única forma de automatizar el vuelo es que la app mande consignas de
 * velocidad 10 veces por segundo. Consecuencias que el operador debe conocer:
 *  - El teléfono y el RC-N1 deben mantenerse conectados todo el vuelo. Si se pierde el enlace, el
 *    dron aplica su failsafe (RTH) y la misión se interrumpe.
 *  - El Mini 3 no tiene sensores de obstáculos: volar siempre por encima de árboles y cables.
 *  - Mover las palancas o pulsar Pausa/RTH en el control devuelve el mando al piloto.
 *
 * En modo VIDEO graba de corrido desde el primer waypoint hasta el último y escribe un registro de
 * vuelo con marcas de tiempo ([FlightLogWriter]) para georreferenciar los cuadros en el escritorio.
 * Una misión grande no cabe en una batería: [MissionRunner] la parte en salidas con [SortiePlanner]
 * y cada una se vuela con una llamada a [start].
 *
 * Probar primero a baja altura en campo abierto con una ruta corta.
 */
object MissionExecutor {

    private const val TAG = "MissionExecutor"

    /**
     * En coordenadas GROUND + modo VELOCITY, la documentación de DJI (MSDK v4, y reportes de v5)
     * indica pitch = Este y roll = Norte. Si la verificación de dirección aborta la misión en la
     * primera prueba, cambiar a false.
     */
    var groundPitchIsEast = true

    private const val LOOP_MS = 100L                 // 10 Hz (DJI recomienda 5–25 Hz)
    private const val ARRIVAL_RADIUS_M = 1.5
    private const val ALTITUDE_TOLERANCE_M = 1.5
    private const val TAKEOFF_DONE_ALT_M = 0.5           // el despegue automático del Mini 3 se estabiliza a ~0.9-1.0 m
    private const val MAX_VERTICAL_SPEED_MS = 2.0
    private const val DIRECTION_CHECK_MAX_DEG = 60.0
    /** Tras cambiar de waypoint el dron aún frena/gira: no se juzga la dirección hasta que se asiente. */
    private const val DIRECTION_SETTLE_MS = 3000L
    private const val STALE_POSITION_MS = 3000L
    private const val RECORDING_START_TIMEOUT_MS = 6000L
    /** Separación de las marcas del video si el plan no trae una (m). */
    private const val DEFAULT_MARK_SPACING_M = 1.0
    private const val GIMBAL_TOLERANCE_DEG = 5.0
    /** Fotos rechazadas seguidas tras las cuales no tiene sentido seguir volando la ruta. */
    private const val MAX_SHOT_FAILURES = 5
    /** Tiempo que se tolera que la cámara deje de grabar antes de abortar. */
    private const val RECORDING_DROP_MS = 1500L
    /** Espera antes de repetir una foto que la cámara rechazó durante el vuelo. */
    private const val SHOT_RETRY_MS = 1500L
    /** Tiempo que se espera, en tierra, a que el operador ponga el selector foto/video del control en el modo pedido. */
    private const val GUIDED_MODE_WAIT_MS = 45_000L

    enum class Phase { IDLE, PREPARING, TAKING_OFF, CLIMBING, FLYING, PAUSED, HOLDING, RETURNING, FINISHED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val waypointIndex: Int = 0,
        val waypointCount: Int = 0,
        val distanceDoneM: Double = 0.0,
        val distanceTotalM: Double = 0.0,
        val photos: Int = 0,
        val elapsedS: Long = 0,
        val message: String = "",
        val recording: Boolean = false,
        val recordingS: Int = 0,
        /** Disparos de foto que la cámara rechazó, y el último motivo (para mostrarlo al operador). */
        val photoErrors: Int = 0,
        val cameraError: String = ""
    ) {
        val running get() = phase in setOf(Phase.PREPARING, Phase.TAKING_OFF, Phase.CLIMBING, Phase.FLYING, Phase.PAUSED)
        val progress get() = if (distanceTotalM > 0) (distanceDoneM / distanceTotalM).coerceIn(0.0, 1.0) else 0.0
    }

    val status = mutableStateOf(Status())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val lock = Any()

    // Telemetría propia del ejecutor (actualizada por listeners del SDK)
    @Volatile private var lat = Double.NaN
    @Volatile private var lon = Double.NaN
    @Volatile private var alt = 0.0
    @Volatile private var velNorth = 0.0
    @Volatile private var velEast = 0.0
    @Volatile private var yawDeg = 0.0
    @Volatile private var lastPositionAt = 0L
    @Volatile private var paused = false
    @Volatile private var authorityLost = false
    @Volatile private var recording = false
    @Volatile private var shotFailures = 0
    /** Hora a partir de la cual se repite una foto que la cámara rechazó; 0 si no hay ninguna pendiente. */
    @Volatile private var retryShotAtMs = 0L
    /** Último error del SDK al intentar cambiar el modo de la cámara (vacío si la orden fue aceptada). */
    @Volatile private var modeSetError = ""
    /** Estado de la microSD en el instante en que la grabación se detuvo (después puede recuperarse y ocultar la causa). */
    @Volatile private var sdStateAtStop = ""

    private var onFinished: ((Outcome) -> Unit)? = null
    private var captureMode = CaptureMode.VIDEO
    private var log: FlightLogWriter? = null
    private var recStartMs: Long? = null
    private var recEndMs: Long? = null
    @Volatile private var legType = "transito"
    @Volatile private var targetIndex = 0

    /**
     * Inicia una salida. Devuelve false (sin tocar nada) si el pre-vuelo en vivo no se cumple o ya
     * hay una misión en curso; en ese caso avisa por [AppStatus]. [onFinished] se llama una sola
     * vez, desde cualquier hilo, cuando la salida termina por la razón que sea.
     */
    fun start(
        plan: WaypointPlan,
        profile: DroneProfile = DroneProfile.MINI_3,
        options: Options = Options(),
        onFinished: (Outcome) -> Unit
    ): Boolean {
        if (status.value.running) return false
        val blockers = Preflight.blockers(Preflight.evaluate(livePreflightInput(plan, options), profile))
        if (blockers.isNotEmpty()) {
            AppStatus.message.value = "Pre-vuelo no cumplido: " + blockers.joinToString(", ") { it.label }
            return false
        }
        this.onFinished = onFinished
        this.log = options.log
        this.captureMode = plan.captureMode
        paused = false
        authorityLost = false
        recStartMs = null
        recEndMs = null
        shotFailures = 0
        retryShotAtMs = 0L
        modeSetError = ""
        sdStateAtStop = ""
        legType = "transito"
        targetIndex = 0
        val total = plan.lengthM()
        setStatus(Status(Phase.PREPARING, 0, plan.waypoints.size, 0.0, total, 0, 0, "Preparando misión"))
        job = scope.launch {
            try {
                run(plan, profile)
            } catch (e: CancellationException) {
                // detenida por el usuario (stopAndHover / returnHome): el estado ya está actualizado
            } catch (e: Exception) {
                Log.e(TAG, "Misión interrumpida", e)
                hold(EndReason.ERROR, "Misión interrumpida: ${e.message}")
            } finally {
                stopListening()
            }
        }
        return true
    }

    /**
     * Pone el dron en hover sin cerrar la salida. Solo mientras vuela la ruta.
     */
    fun pause() {
        if (status.value.phase == Phase.FLYING) {
            paused = true
            event("pausa")
            update { copy(phase = Phase.PAUSED, message = "En pausa (hover)") }
        }
    }

    /**
     * Reanuda la ruta tras [pause].
     */
    fun resume() {
        if (status.value.phase == Phase.PAUSED) {
            paused = false
            event("reanuda")
            update { copy(phase = Phase.FLYING, message = "Reanudando") }
        }
    }

    /** Detiene la misión y deja el dron en hover con el mando para el piloto. */
    fun stopAndHover() {
        job?.cancel()
        releaseVirtualStick()
        update { copy(phase = Phase.HOLDING, message = "Misión detenida. Dron en hover, control manual") }
        finish(EndReason.PILOT_STOP)
    }

    /**
     * Cancela la ruta y manda el dron a casa con el RTH del propio dron (la salida termina como RTH_REQUESTED).
     */
    fun returnHome() {
        job?.cancel()
        releaseVirtualStick()
        update { copy(phase = Phase.RETURNING, message = "Regresando a casa") }
        FlightActions.returnToHome { s -> update { copy(message = s) } }
        finish(EndReason.RTH_REQUESTED)
    }

    /**
     * Limpia el estado de una salida ya terminada para poder iniciar otra. No hace nada si todavía vuela.
     */
    fun reset() {
        if (!status.value.running) setStatus(Status())
    }

    // ------------------------------------------------------------------ bucle principal

    private suspend fun run(plan: WaypointPlan, profile: DroneProfile) {
        startListening()
        val startedAt = System.currentTimeMillis()
        val wps = plan.waypoints
        val videoMode = plan.captureMode == CaptureMode.VIDEO
        event("inicio", mapOf("waypoints" to wps.size, "modo" to plan.captureMode.json, "altura_m" to plan.altitudeM))

        // El regreso a casa del dron sube a 100 m de fábrica (aves, batería, tiempo): se baja a la altura de la ruta + margen.
        // Vale para todos los regresos (fin de misión, botón RTH, batería baja y protecciones del dron). Si falla no se aborta:
        // queda la altura que tuviera el dron y se avisa.
        val rthM = FlightActions.returnHomeHeightFor(wps.maxOf { it.altitudeM })
        FlightActions.setReturnHomeHeight(rthM) { error ->
            event(if (error == null) "rth_altura" else "rth_altura_error", mapOf("altura_m" to rthM, "detalle" to error))
            if (error != null) update { copy(message = "No se pudo bajar la altura de regreso a casa a $rthM m (queda la del dron): $error") }
        }

        // 0. Cámara y gimbal listos antes de despegar (modo, resolución, gimbal a nadir)
        val modeBefore = DroneInfo.cameraMode.value
        prepareCamera(plan)
        update { copy(message = "Preparando la cámara") }
        if (!awaitCameraMode(plan.captureMode)) {
            // La app no pudo cambiar el modo (el selector foto/video del control remoto también lo fija):
            // se le da tiempo al operador para cambiarlo y, pase lo que pase, decide la prueba real, no este aviso
            event("camara_modo_no_confirmado", mapOf("modo_actual" to DroneInfo.cameraMode.value, "error_app" to modeSetError))
            if (!awaitCameraModeGuided(plan.captureMode)) {
                event("camara_modo_sigue_incorrecto", mapOf("modo_actual" to DroneInfo.cameraMode.value))
            }
        }
        announceCameraMode(plan.captureMode, modeBefore)
        if (!videoMode) {
            // Una foto de prueba antes de gastar batería: si la cámara no puede disparar, se sabe aquí y con el motivo
            update { copy(message = "Probando la cámara (foto de prueba)") }
            photoSelfTest()?.let { error ->
                return failBeforeFlight(
                    EndReason.CAMERA_FAILED,
                    "La cámara no pudo tomar la foto de prueba: $error. " +
                        wrongModeNote(videoMode = false) +
                        "Tarjeta: ${SdCard.read(DroneInfo.sdCardState.value, false).text}. " +
                        if (DroneInfo.shootPhotoNotAllowed.value) "La cámara indica que no permite fotos ahora." else ""
                )
            }
        }

        // 1. Despegue si está en tierra
        if (!DJIConnectionManager.isFlying.value) {
            update { copy(phase = Phase.TAKING_OFF, message = "Despegando") }
            val ok = suspendCancellableCoroutine { cont ->
                FlightActions.takeOffToTargetHeight(onStatus = { s -> update { copy(message = s) } }) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
            if (!ok) return hold(EndReason.TAKEOFF_FAILED, "No se pudo despegar")
            // esperar a que el despegue automático termine; el Mini 3 puede quedarse estable en ~0.9-1.0 m, por eso el umbral es bajo
            val t0 = System.currentTimeMillis()
            while (!(DJIConnectionManager.isFlying.value && alt > TAKEOFF_DONE_ALT_M)) {
                if (System.currentTimeMillis() - t0 > 20_000) {
                    return hold(EndReason.TAKEOFF_FAILED, "El despegue no terminó a tiempo (altura ${"%.1f".format(alt)} m)")
                }
                delay(200)
            }
            delay(1500)
        }

        // 2. Virtual Stick
        update { copy(phase = Phase.PREPARING, message = "Activando Virtual Stick") }
        enableVirtualStick()?.let { return hold(EndReason.VIRTUAL_STICK_FAILED, "No se pudo activar Virtual Stick: ${it.readable()}") }
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
        val heading = plan.headingDeg ?: yawDeg
        val speed = (plan.speedMs ?: profile.maxMissionSpeedMs).coerceIn(0.5, profile.maxMissionSpeedMs)

        // El gimbal debe estar a nadir antes de capturar: con otro ángulo el mapa sale deformado
        if (!awaitGimbal(plan.gimbalPitchDeg)) {
            return hold(EndReason.GIMBAL_FAILED, "El gimbal no llegó a ${plan.gimbalPitchDeg.toInt()}°. Revísalo antes de volar")
        }

        // 3. Subir en vertical a la altura de misión (por encima de plantas, árboles y cables)
        update { copy(phase = Phase.CLIMBING, message = "Subiendo a ${wps.first().altitudeM.toInt()} m") }
        legType = "ascenso"
        while (abs(wps.first().altitudeM - alt) > ALTITUDE_TOLERANCE_M) {
            if (shouldAbort(profile)) return
            send(0.0, 0.0, verticalSpeed(wps.first().altitudeM), heading)
            delay(LOOP_MS)
        }

        // 4. Recorrer waypoints
        update { copy(phase = Phase.FLYING, message = "En ruta al waypoint 1") }
        var lastPhotoPos: LatLon? = null
        var lastPhotoAt = 0L
        var surveyStarted = false
        // Marcas internas del video (no se muestran al operador): una cada `photoEveryM` m de barrido, que es la separación entre cuadros del plan
        val marker = DistanceMarker(plan.photoEveryM.takeIf { it > 0 } ?: DEFAULT_MARK_SPACING_M)
        var markN = 0
        var directionBadSince = 0L
        var recordingDroppedSince = 0L
        var doneBefore = 0.0

        for (i in wps.indices) {
            val target = wps[i]
            targetIndex = i
            legType = when {
                i == 0 -> "transito"
                target.surveyLeg -> "barrido"
                else -> "giro"
            }
            val legStartedAt = System.currentTimeMillis()
            marker.reset()
            event("rumbo_a_waypoint", mapOf("wp" to i, "tramo" to legType))
            update { copy(waypointIndex = i, message = "Waypoint ${i + 1} de ${wps.size}") }
            while (true) {
                if (shouldAbort(profile)) return

                // Si la cámara dejó de grabar por su cuenta (p. ej. microSD lenta) no sirve seguir: se vuela sin captar nada
                if (videoMode && recStartMs != null && recEndMs == null && !recording) {
                    if (recordingDroppedSince == 0L) recordingDroppedSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - recordingDroppedSince > RECORDING_DROP_MS) {
                        return hold(
                            EndReason.RECORDING_FAILED,
                            "La cámara dejó de grabar por sí sola. Tarjeta: ${SdCard.read(sdStateAtStop.ifBlank { DroneInfo.sdCardState.value }, true).text}. " +
                                "En 4K hace falta una microSD U3/V30"
                        )
                    }
                } else recordingDroppedSince = 0L
                if (!videoMode && shotFailures >= MAX_SHOT_FAILURES) {
                    return hold(
                        EndReason.CAMERA_FAILED,
                        "La cámara rechazó $shotFailures intentos de foto seguidos (${status.value.cameraError}). Revisa la microSD"
                    )
                }

                if (paused) {
                    send(0.0, 0.0, 0.0, heading)
                    delay(LOOP_MS)
                    continue
                }
                val here = LatLon(lat, lon)
                val v = LocalFrame(here).toXY(target)              // vector hasta el objetivo (m)
                val dist = v.length()
                val altErr = target.altitudeM - alt
                if (dist < ARRIVAL_RADIUS_M && abs(altErr) < ALTITUDE_TOLERANCE_M) break

                // Velocidad: crucero, frenando al acercarse
                val sp = minOf(speed, maxOf(0.5, dist * 0.4))
                val cmd = if (dist > 0.01) v * (sp / dist) else XY(0.0, 0.0)
                send(cmd.y, cmd.x, verticalSpeed(target.altitudeM), heading)

                // Verificación de ejes: si el dron se mueve en otra dirección que la ordenada, detener.
                // No se juzga justo después de cambiar de waypoint: el dron todavía frena y gira.
                val measured = XY(velEast, velNorth)
                val settled = System.currentTimeMillis() - legStartedAt > DIRECTION_SETTLE_MS
                if (settled && sp > 1.0 && measured.length() > 1.0 && angleDeg(cmd, measured) > DIRECTION_CHECK_MAX_DEG) {
                    if (directionBadSince == 0L) directionBadSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - directionBadSince > 1500) {
                        return hold(EndReason.WRONG_DIRECTION, "El dron no va hacia donde se le ordena. Revisa MissionExecutor.groundPitchIsEast")
                    }
                } else directionBadSince = 0L

                // Disparo por distancia durante el barrido (solo en modo FOTO; en VIDEO se graba de corrido)
                if (!videoMode && surveyStarted && plan.photoEveryM > 0 && target.surveyLeg) {
                    val now = System.currentTimeMillis()
                    val moved = lastPhotoPos?.distanceTo(here) ?: Double.MAX_VALUE
                    val interval = now - lastPhotoAt
                    val retryDue = retryShotAtMs in 1..now
                    if (retryDue || (moved >= plan.photoEveryM && interval >= profile.minPhotoIntervalS * 1000)) {
                        retryShotAtMs = 0L
                        shoot()
                        if (!retryDue) lastPhotoPos = here
                        lastPhotoAt = now
                    }
                }

                val legDone = if (i > 0) (wps[i - 1].distanceTo(target) - dist).coerceAtLeast(0.0) else 0.0
                if (videoMode && recording && target.surveyLeg && marker.due(here)) {
                    event(
                        "marca",
                        mapOf(
                            "n" to ++markN,
                            "t_video_s" to recStartMs?.let { (System.currentTimeMillis() - it) / 1000.0 },
                            "lat" to lat, "lon" to lon, "alt_m" to alt,
                            "velocidad_ms" to hypot(velNorth, velEast),
                            "wp" to i,
                            "ruta_m" to doneBefore + legDone
                        )
                    )
                }
                update {
                    copy(distanceDoneM = doneBefore + legDone, elapsedS = (System.currentTimeMillis() - startedAt) / 1000)
                }
                delay(LOOP_MS)
            }
            event("waypoint_alcanzado", mapOf("wp" to i))
            if (i > 0) doneBefore += wps[i - 1].distanceTo(target)
            if (!videoMode && target.takePhoto) {
                // frenar y disparar en el punto exacto
                send(0.0, 0.0, 0.0, heading)
                delay(800)
                shoot()
                lastPhotoAt = System.currentTimeMillis()
            }
            if (i == 0) {
                surveyStarted = true
                if (videoMode) {
                    // Empieza a grabar al llegar al primer waypoint (en hover), antes de arrancar la primera pasada
                    update { copy(message = "Iniciando grabación") }
                    send(0.0, 0.0, 0.0, heading)
                    if (!startRecording(heading)) {
                        return hold(
                            EndReason.RECORDING_FAILED,
                            "No se pudo iniciar la grabación de video. ${wrongModeNote(videoMode = true)}" +
                                "Tarjeta: ${SdCard.read(DroneInfo.sdCardState.value, true).text}"
                        )
                    }
                } else if (plan.photoEveryM > 0) {
                    shoot()
                    lastPhotoPos = LatLon(lat, lon)
                    lastPhotoAt = System.currentTimeMillis()
                }
            }
        }

        // 5. Fin: parar la grabación, soltar el mando y volver a casa con el RTH del propio dron
        send(0.0, 0.0, 0.0, heading)
        stopRecording()
        releaseVirtualStick()
        update {
            copy(
                phase = Phase.FINISHED, distanceDoneM = distanceTotalM,
                elapsedS = (System.currentTimeMillis() - startedAt) / 1000,
                message = "Salida completa. Regresando a casa"
            )
        }
        FlightActions.returnToHome { s -> update { copy(message = s) } }
        finish(EndReason.COMPLETED)
    }

    /**
     * true si hay que abortar. En ese caso ya dejó el dron en hover/RTH y cerró la salida: quien
     * llama solo debe salir del bucle.
     */
    private fun shouldAbort(profile: DroneProfile): Boolean {
        if (!DJIConnectionManager.isConnected.value) {
            update { copy(phase = Phase.HOLDING, message = "Enlace perdido: el dron aplicará su failsafe (RTH)") }
            finish(EndReason.LINK_LOST)
            return true
        }
        if (authorityLost) {
            hold(EndReason.AUTHORITY_LOST, "El piloto tomó el control (palancas / pausa en el RC). Misión detenida")
            return true
        }
        val battery = TelemetryManager.telemetry.value.batteryPercent
        if (battery in 1 until profile.batteryReservePct) {
            releaseVirtualStick()
            update { copy(phase = Phase.RETURNING, message = "Batería $battery%: regreso a casa") }
            FlightActions.returnToHome { s -> update { copy(message = s) } }
            finish(EndReason.LOW_BATTERY, "Batería $battery%: se interrumpe la salida")
            return true
        }
        if (lat.isNaN() || lon.isNaN()) {
            hold(EndReason.NO_GPS, "Sin posición GPS del dron")
            return true
        }
        if (System.currentTimeMillis() - lastPositionAt > STALE_POSITION_MS) {
            hold(EndReason.STALE_POSITION, "La posición del dron dejó de actualizarse")
            return true
        }
        return false
    }

    private fun verticalSpeed(targetAlt: Double) =
        ((targetAlt - alt) * 0.8).coerceIn(-MAX_VERTICAL_SPEED_MS, MAX_VERTICAL_SPEED_MS)

    /** Envía velocidades en marco tierra (m/s) y rumbo absoluto (grados brújula). */
    private fun send(north: Double, east: Double, up: Double, headingDeg: Double) {
        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGLE
            if (groundPitchIsEast) {
                pitch = east
                roll = north
            } else {
                pitch = north
                roll = east
            }
            verticalThrottle = up
            yaw = normalizeYaw(headingDeg)
        }
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
        sampleLog()
    }

    private fun sampleLog() {
        val l = log ?: return
        if (lat.isNaN() || lon.isNaN()) return
        l.sample(
            tMs = System.currentTimeMillis(), lat = lat, lon = lon, altM = alt, yawDeg = yawDeg,
            gimbalPitchDeg = DroneInfo.gimbalPitch.value, speedMs = hypot(velNorth, velEast),
            waypoint = targetIndex, leg = legType, phase = status.value.phase.name, recording = recording
        )
    }

    private fun event(name: String, detail: Map<String, Any?> = emptyMap()) {
        log?.event(System.currentTimeMillis(), name, detail)
    }

    private fun normalizeYaw(deg: Double): Double {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    private fun angleDeg(a: XY, b: XY): Double {
        val c = (a.x * b.x + a.y * b.y) / (a.length() * b.length())
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    private fun hold(reason: EndReason, msg: String) {
        send(0.0, 0.0, 0.0, yawDeg)
        releaseVirtualStick()
        update { copy(phase = Phase.HOLDING, message = msg) }
        finish(reason, msg)
    }

    /** Si la cámara no está en el modo que la captura necesita, dice cómo arreglarlo (vacío si el modo es el correcto o se desconoce). */
    private fun wrongModeNote(videoMode: Boolean): String {
        val current = DroneInfo.cameraMode.value
        val expected = if (videoMode) CameraMode.VIDEO_NORMAL.name else CameraMode.PHOTO_NORMAL.name
        if (current.isEmpty() || current == CameraMode.UNKNOWN.name || current == expected) return ""
        return "La cámara está en modo $current: pon el selector foto/video del control remoto en ${if (videoMode) "VIDEO" else "FOTO"}. " +
            (if (modeSetError.isNotEmpty()) "Al intentar cambiarlo desde la app: $modeSetError. " else "")
    }

    /** Termina la salida antes de volar (la cámara no está lista): no hay Virtual Stick que soltar. */
    private fun failBeforeFlight(reason: EndReason, msg: String) {
        update { copy(phase = Phase.HOLDING, message = msg) }
        finish(reason, msg)
    }

    /** Punto único de salida: cierra grabación y registro, y avisa una sola vez con el resultado. */
    private fun finish(reason: EndReason, message: String = status.value.message) {
        val cb = onFinished
        onFinished = null
        stopRecording()
        val s = status.value
        event("fin", mapOf("motivo" to reason.name, "mensaje" to message, "wp" to targetIndex))
        val l = log
        log = null
        l?.close()
        val outcome = Outcome(
            reason = reason,
            waypointIndex = if (reason == EndReason.COMPLETED) s.waypointCount - 1 else targetIndex,
            message = message,
            distanceM = s.distanceDoneM,
            videoStartMs = recStartMs,
            videoEndMs = recEndMs
        )
        cb?.invoke(outcome)
    }

    /** Escribe el estado sin perder actualizaciones simultáneas del bucle y de los callbacks del SDK. */
    private fun update(block: Status.() -> Status) {
        synchronized(lock) { status.value = status.value.block() }
    }

    private fun setStatus(s: Status) {
        synchronized(lock) { status.value = s }
    }

    // ------------------------------------------------------------------ SDK

    private suspend fun enableVirtualStick(): IDJIError? = suspendCancellableCoroutine { cont ->
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(null)
            }

            override fun onFailure(error: IDJIError) {
                if (cont.isActive) cont.resume(error)
            }
        })
    }

    private fun releaseVirtualStick() {
        try {
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {}
                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "disableVirtualStick: ${error.readable()}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "releaseVirtualStick", e)
        }
    }

    /** Modo de cámara, resolución del video y gimbal a nadir. Cada paso es de mejor esfuerzo; el modo y el gimbal se verifican aparte. */
    private fun prepareCamera(plan: WaypointPlan) {
        applyCameraMode(plan.captureMode)
        rotateGimbal(plan.gimbalPitchDeg)
    }

    /** Pide el modo de la cámara y, ya cambiado, sus ajustes (resolución en video; disparo simple en foto). */
    private fun applyCameraMode(captureMode: CaptureMode) {
        CameraControl.setMode(
            video = captureMode == CaptureMode.VIDEO,
            onSettingError = { step, detail ->
                event("camara_error", mapOf("paso" to step, "detalle" to detail))
                if (step == "modo") modeSetError = detail
            },
            onModeAccepted = { modeSetError = "" }
        )
    }

    /**
     * Avisa con un mensaje breve en pantalla (y en el registro) en qué modo quedó la cámara: si la app lo cambió, si ya estaba
     * en el pedido o si no se pudo cambiar (con el motivo del SDK). [before] es el modo que tenía antes de pedir el cambio.
     */
    private fun announceCameraMode(captureMode: CaptureMode, before: String) {
        val video = captureMode == CaptureMode.VIDEO
        val label = if (video) "VIDEO" else "FOTO"
        val current = DroneInfo.cameraMode.value
        val ok = current == (if (video) CameraMode.VIDEO_NORMAL else CameraMode.PHOTO_NORMAL).name
        event("camara_modo", mapOf("pedido" to label.lowercase(), "antes" to before, "actual" to current, "ok" to ok))
        val message = when {
            !ok -> "No se pudo poner la cámara en modo $label (está en ${current.ifEmpty { "modo desconocido" }})" +
                if (modeSetError.isNotEmpty()) ": $modeSetError" else ""
            before == current -> "Cámara ya estaba en modo $label"
            else -> "Cámara cambiada a modo $label"
        }
        runOnMain { AppStatus.message.value = message }
    }

    /**
     * Espera a que la cámara informe el modo pedido; reintenta la orden cada ~2 s. Si el SDK no
     * informa el modo no se puede verificar y se sigue (la foto de prueba lo comprueba en la práctica).
     */
    private suspend fun awaitCameraMode(captureMode: CaptureMode): Boolean {
        val expected = if (captureMode == CaptureMode.VIDEO) CameraMode.VIDEO_NORMAL.name else CameraMode.PHOTO_NORMAL.name
        val t0 = System.currentTimeMillis()
        var retries = 0
        while (System.currentTimeMillis() - t0 < 6000) {
            val current = DroneInfo.cameraMode.value
            val unknown = current.isEmpty() || current == CameraMode.UNKNOWN.name
            if (current == expected) return true
            if (unknown && System.currentTimeMillis() - t0 > 3000) return true
            if (retries < 3 && System.currentTimeMillis() - t0 > 2000L * (retries + 1)) {
                applyCameraMode(captureMode)
                retries++
            }
            delay(200)
        }
        val current = DroneInfo.cameraMode.value
        return current.isEmpty() || current == CameraMode.UNKNOWN.name
    }

    private fun rotateGimbal(pitchDeg: Double) = CameraControl.rotateGimbal(pitchDeg)

    /** Espera a que el gimbal llegue a [pitchDeg]; reintenta la orden a mitad de la espera. */
    private suspend fun awaitGimbal(pitchDeg: Double): Boolean {
        val t0 = System.currentTimeMillis()
        var retried = false
        while (System.currentTimeMillis() - t0 < 6000) {
            val p = DroneInfo.gimbalPitch.value
            if (!p.isNaN() && abs(p - pitchDeg) <= GIMBAL_TOLERANCE_DEG) return true
            if (!retried && System.currentTimeMillis() - t0 > 3000) {
                rotateGimbal(pitchDeg)
                retried = true
            }
            delay(200)
        }
        return false
    }

    /**
     * En tierra y con la cámara en el modo equivocado: muestra qué hacer y espera a que el operador ponga el
     * selector foto/video del control remoto, reintentando además el cambio desde la app. false si se agota el tiempo.
     */
    private suspend fun awaitCameraModeGuided(captureMode: CaptureMode): Boolean {
        val video = captureMode == CaptureMode.VIDEO
        val expected = if (video) CameraMode.VIDEO_NORMAL.name else CameraMode.PHOTO_NORMAL.name
        val label = if (video) "VIDEO" else "FOTO"
        val t0 = System.currentTimeMillis()
        var lastApply = t0
        while (System.currentTimeMillis() - t0 < GUIDED_MODE_WAIT_MS) {
            if (DroneInfo.cameraMode.value == expected) {
                update { copy(message = "Cámara en modo ${label.lowercase()}") }
                return true
            }
            val left = (GUIDED_MODE_WAIT_MS - (System.currentTimeMillis() - t0)) / 1000
            update {
                copy(
                    message = "La cámara está en ${DroneInfo.cameraMode.value.ifEmpty { "modo desconocido" }}. " +
                        "Pon el selector foto/video del control remoto en $label (${left} s)." +
                        if (modeSetError.isNotEmpty()) " La app no pudo cambiarlo: $modeSetError" else ""
                )
            }
            if (System.currentTimeMillis() - lastApply > 5000) {
                applyCameraMode(captureMode)
                lastApply = System.currentTimeMillis()
            }
            delay(500)
        }
        return false
    }

    /**
     * Pide una foto. [onResult] recibe null si la cámara la tomó o el motivo del rechazo. Cuenta
     * las fotos tomadas y los fallos seguidos (si son muchos, la misión se detiene).
     */
    private fun shoot(count: Boolean = true, onResult: ((String?) -> Unit)? = null) {
        CameraControl.shootPhoto { error ->
            if (count) {
                if (error == null) {
                    shotFailures = 0
                    update { copy(photos = photos + 1, cameraError = "") }
                    event("foto_ok", mapOf("n" to status.value.photos))
                } else {
                    shotFailures++
                    retryShotAtMs = System.currentTimeMillis() + SHOT_RETRY_MS
                    update { copy(photoErrors = photoErrors + 1, cameraError = error) }
                    event("foto_error", mapOf("detalle" to error))
                }
            }
            onResult?.invoke(error)
        }
    }

    private class ShotResult(val error: String?)

    /** Pide una foto y espera su resultado (null = tomada; texto = motivo del fallo o de la falta de respuesta). */
    private suspend fun shootAndWait(count: Boolean, timeoutMs: Long = 8000): String? {
        val result = CompletableDeferred<ShotResult>()
        shoot(count) { error -> result.complete(ShotResult(error)) }
        val r = withTimeoutOrNull(timeoutMs) { result.await() }
        return if (r == null) "la cámara no respondió a tiempo" else r.error
    }

    /** Foto de prueba antes de despegar (no cuenta como foto de la misión). Reintenta una vez. null = funciona. */
    private suspend fun photoSelfTest(): String? {
        var error = shootAndWait(count = false)
        if (error != null) {
            event("camara_error", mapOf("paso" to "foto_de_prueba", "detalle" to error))
            delay(2000)
            error = shootAndWait(count = false)
        }
        event(if (error == null) "foto_de_prueba_ok" else "foto_de_prueba_fallo", mapOf("detalle" to error))
        return error
    }

    /** Inicia el video y espera a que la cámara confirme que graba. false si no lo logra. */
    private suspend fun startRecording(heading: Double): Boolean {
        if (recording) return true
        CameraKey.KeyStartRecord.create(ComponentIndexType.LEFT_OR_MAIN).action(
            onSuccess = {},
            onFailure = { e -> Log.w(TAG, "Iniciar video: ${e.readable()}") }
        )
        val t0 = System.currentTimeMillis()
        while (!recording) {
            if (System.currentTimeMillis() - t0 > RECORDING_START_TIMEOUT_MS) return false
            send(0.0, 0.0, 0.0, heading)   // seguir mandando consigna: hover mientras la cámara arranca
            delay(LOOP_MS)
        }
        if (recStartMs == null) recStartMs = System.currentTimeMillis()
        event("video_inicio")
        return true
    }

    /** Para el video si se está grabando. Idempotente y de mejor esfuerzo (con el enlace caído no llega). */
    private fun stopRecording() {
        if (recStartMs == null || recEndMs != null) return
        recEndMs = System.currentTimeMillis()
        event("video_fin")
        CameraKey.KeyStopRecord.create(ComponentIndexType.LEFT_OR_MAIN).action(
            onSuccess = {},
            onFailure = { e -> Log.w(TAG, "Parar video: ${e.readable()}") }
        )
    }

    private fun startListening() {
        // Partir de lo último que se sabe del dron: el primer aviso del SDK puede tardar y no debe leerse como "sin GPS"
        val t = TelemetryManager.telemetry.value
        val known = !t.latitude.isNaN() && !t.longitude.isNaN() && (t.latitude != 0.0 || t.longitude != 0.0)
        lat = if (known) t.latitude else Double.NaN
        lon = if (known) t.longitude else Double.NaN
        alt = t.altitude
        velNorth = 0.0
        velEast = 0.0
        lastPositionAt = System.currentTimeMillis()
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this) { _, v ->
            v?.let {
                lat = it.latitude; lon = it.longitude; alt = it.altitude
                lastPositionAt = System.currentTimeMillis()
            }
        }
        // Velocidad en marco NED: x = norte, y = este
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this) { _, v ->
            v?.let { velNorth = it.x; velEast = it.y }
        }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this) { _, v ->
            v?.let { yawDeg = it.yaw }
        }
        km.listen(KeyTools.createKey(CameraKey.KeyIsRecording, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            val isRec = v ?: false
            if (!isRec && recording) sdStateAtStop = DroneInfo.sdCardState.value
            if (isRec != recording) event(if (isRec) "grabando" else "grabacion_detenida", mapOf("tarjeta" to DroneInfo.sdCardState.value))
            recording = isRec
            update { copy(recording = isRec) }
        }
        km.listen(KeyTools.createKey(CameraKey.KeyRecordingTime, ComponentIndexType.LEFT_OR_MAIN), this) { _, v ->
            update { copy(recordingS = v ?: 0) }
        }
        VirtualStickManager.getInstance().setVirtualStickStateListener(object : VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                // Solo cuenta como pérdida si ya estábamos volando la ruta
                if (!stickState.isVirtualStickEnable && status.value.phase in setOf(Phase.CLIMBING, Phase.FLYING, Phase.PAUSED)) {
                    authorityLost = true
                }
            }

            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                Log.i(TAG, "Cambio de autoridad: $reason")
                event("autoridad", mapOf("motivo" to reason.toString()))
            }
        })
    }

    private fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
        VirtualStickManager.getInstance().clearAllVirtualStickStateListener()
    }
}
