package com.jesus.dronplataneras.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import com.jesus.dronplataneras.data.AgroRepository
import com.jesus.dronplataneras.flight.LandingGuard
import com.jesus.dronplataneras.network.CameraFeed
import com.jesus.dronplataneras.network.LinkLog
import com.jesus.dronplataneras.network.TelemetryStream
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.map.MapConfig
import com.jesus.dronplataneras.mission.MissionExecutor
import com.jesus.dronplataneras.survey.SurveySession
import com.jesus.dronplataneras.telemetry.DroneInfo
import com.jesus.dronplataneras.telemetry.TelemetryManager
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Pantallas de la app. Las que llevan datos (ids de Room) son data class. */
private sealed interface Route {
    data object Home : Route
    /** [autoStart]: empezar a buscar al entrar (reintento desde "Dron no detectado"). */
    data class Connect(val autoStart: Boolean = false) : Route
    data object NotDetected : Route
    data object LocalNetwork : Route
    /** Vista de cámara a pantalla completa (vuelo manual + marcar vértices). */
    data object Camera : Route
    data object PolygonEditor : Route
    data class Planning(val loteId: Long, val misionId: Long? = null) : Route
    data class Verification(val misionId: Long) : Route
    data object SavedMissions : Route
    data object OfflineMaps : Route
}

class MainActivity : AppCompatActivity() {

    private val requiredPermissions: Array<String> by lazy {
        val base = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.add(Manifest.permission.BLUETOOTH_CONNECT)
            base.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        base.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w("MyApp", "Permisos denegados: $denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Es una app de vuelo: si la pantalla se apaga, el teléfono puede dormir y dejar de mandar consignas al dron
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNeededPermissions()
        closeOrphanSorties()

        setContent {
            MaterialTheme {
                Surface {
                    // Pila de navegación simple: el último elemento es la pantalla visible
                    val stack = remember { mutableStateListOf<Route>(Route.Home) }
                    val screen = stack.last()
                    // La PC muestra "Generando la ruta…" mientras el operador prepara la misión (la ruta le llega al iniciar la salida).
                    // La vista de cámara no cuenta: ahí también se vuela a mano.
                    SideEffect {
                        TelemetryStream.etapa = when (screen) {
                            Route.PolygonEditor -> "perimetro"
                            is Route.Planning -> "planificacion"
                            is Route.Verification -> "verificacion"
                            else -> null
                        }
                    }
                    fun go(r: Route) { stack.add(r) }
                    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                    fun home() { stack.clear(); stack.add(Route.Home) }
                    /** Reemplaza la pantalla actual (no se vuelve a ella con "atrás"). */
                    fun replace(r: Route) { stack[stack.lastIndex] = r }
                    /** Nueva misión: si quedó a medias la edición de un lote guardado, se descarta. */
                    fun newMission() {
                        if (SurveySession.editingLoteId.value != null) SurveySession.clear()
                        go(Route.PolygonEditor)
                    }

                    // Con el dron en el aire (volando la ruta o regresando de ella) no se sale de la pantalla de misión:
                    // salir dejaba al operador sin mapa ni cámara mientras el dron seguía volando
                    val mission = MissionExecutor.status.value
                    val missionBusy = mission.running ||
                        (mission.phase != MissionExecutor.Phase.IDLE && DJIConnectionManager.isFlying.value)
                    BackHandler(enabled = stack.size > 1 && !missionBusy) { back() }
                    BackHandler(enabled = missionBusy) {
                        AppStatus.message.value = "El dron sigue en el aire: espera a que aterrice para salir de esta pantalla"
                    }

                    when (screen) {
                        Route.Home -> HomeScreen(
                            onEnterFlightScreen = { newMission() },
                            onConnect = { go(Route.Connect()) },
                            onOpenLocalNetwork = { go(Route.LocalNetwork) },
                            onOpenMaps = { go(Route.OfflineMaps) },
                            onSeeAllMissions = { go(Route.SavedMissions) },
                            onOpenMission = { id -> go(Route.Verification(id)) }
                        )
                        is Route.Connect -> ConnectScreen(
                            autoStart = screen.autoStart,
                            onConnected = { home() },
                            onNotDetected = { replace(Route.NotDetected) }
                        )
                        Route.NotDetected -> NotDetectedScreen(
                            onRetry = { replace(Route.Connect(autoStart = true)) },
                            onHome = { home() }
                        )
                        Route.LocalNetwork -> LocalNetworkScreen(onBack = { back() })
                        Route.Camera -> MainScreen(
                            onBack = { back() },
                            // Intercambio estilo DJI Fly: cámara <-> mapa en el mismo lugar de la pila
                            onOpenEditor = { replace(Route.PolygonEditor) }
                        )
                        Route.PolygonEditor -> PolygonEditorScreen(
                            onBack = { back() },
                            onOpenCamera = { replace(Route.Camera) },
                            onConfirmed = { loteId ->
                                // Si se vino a editar el perímetro desde la planificación, volver a ella
                                val prev = stack.getOrNull(stack.lastIndex - 1)
                                if (prev is Route.Planning && prev.loteId == loteId) back() else replace(Route.Planning(loteId))
                            }
                        )
                        is Route.Planning -> FlightPlanningScreen(
                            loteId = screen.loteId,
                            misionId = screen.misionId,
                            onBack = { back() },
                            onEditPerimeterWithDrone = { go(Route.PolygonEditor) },
                            onContinue = { id -> go(Route.Verification(id)) }
                        )
                        is Route.Verification -> FlightVerificationScreen(
                            misionId = screen.misionId,
                            onModifyParameters = { loteId, misionId ->
                                if (stack.getOrNull(stack.lastIndex - 1) is Route.Planning) back()
                                else replace(Route.Planning(loteId, misionId))
                            },
                            onSaveForLater = { home(); go(Route.SavedMissions) },
                            onBack = { back() }
                        )
                        Route.SavedMissions -> SavedMissionsScreen(
                            onBack = { back() },
                            onNewMission = { newMission() },
                            onEdit = { loteId, misionId -> go(Route.Planning(loteId, misionId)) },
                            onLoad = { id -> go(Route.Verification(id)) },
                            onOfflineMaps = { go(Route.OfflineMaps) }
                        )
                        Route.OfflineMaps -> OfflineMapDownloadScreen(onBack = { back() })
                    }

                    // Aterrizar es decisión del piloto, no de la app: tanto cuando el dron duda del terreno como cuando
                    // se corta el aterrizaje automático del regreso a casa a ~2 m. Es una barra, no un diálogo, para ver la cámara
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        LandingDecisionBar()
                    }
                }
            }
        }
        registerApp()
        // Envío de telemetría a la PC: queda esperando a que el Wi-Fi de la PC esté conectado; un fallo aquí no debe afectar al dron
        LinkLog.init(applicationContext)
        LinkLog.log("app", "App abierta")
        runCatching { TelemetryStream.start(this) }.onFailure { Log.e("MyApp", "TelemetryStream falló", it) }
        runCatching { CameraFeed.start() }.onFailure { Log.e("MyApp", "CameraFeed falló", it) }
        // Después del registro DJI y protegido: un fallo de mapas nunca debe impedir la conexión al dron
        runCatching { MapConfig.init(this) }.onFailure { Log.e("MyApp", "MapConfig.init falló", it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Si la app se cerró en pleno vuelo, la salida quedó "en curso" para siempre en el historial.
     * No se toca si hay una misión viva (p. ej. la Activity se recreó con el dron en el aire).
     */
    private fun closeOrphanSorties() {
        if (MissionExecutor.status.value.phase != MissionExecutor.Phase.IDLE) return
        val repo = AgroRepository.get(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repo.cerrarSalidasHuerfanas() }.onFailure { Log.e("MyApp", "No se pudieron cerrar salidas huérfanas", it) }
        }
    }

    private fun requestNeededPermissions() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun registerApp() {
        SDKManager.getInstance().init(this, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                Log.i("MyApp", "SDK registrado correctamente")
                DJIConnectionManager.startListening()
                LandingGuard.start()
                DJIConnectionManager.startLandingConfirmationWatcher()
                DJIConnectionManager.startGoHomeConfirmationWatcher { status -> AppStatus.message.value = status }
                DJIConnectionManager.startGoHomeStatusWatcher { state ->
                    TelemetryManager.mutate { copy(goHomeStatus = state) }
                }
                DJIConnectionManager.startHealthWatcher { warning -> AppStatus.message.value = warning }
                TelemetryManager.startListening()
                runCatching { DroneInfo.startListening() }.onFailure { Log.e("MyApp", "DroneInfo falló", it) }
            }
            override fun onRegisterFailure(error: IDJIError) {
                Log.e("MyApp", "Fallo de registro: $error")
            }
            override fun onProductDisconnect(productId: Int) {}
            override fun onProductConnect(productId: Int) {}
            override fun onProductChanged(productId: Int) {}
            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                Log.i("MyApp", "Progreso de inicialización: $event, $totalProcess")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.i("MyApp", "Descargando base de datos: $current / $total")
            }
        })
    }
}