package com.jesus.dronplataneras.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.jesus.dronplataneras.sdk.AppStatus
import com.jesus.dronplataneras.sdk.DJIConnectionManager
import com.jesus.dronplataneras.telemetry.TelemetryManager
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

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
        requestNeededPermissions()

        setContent {
            MaterialTheme {
                Surface {
                    MainScreen()
                }
            }
        }
        registerApp()
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
                DJIConnectionManager.startLandingConfirmationWatcher()
                DJIConnectionManager.startGoHomeConfirmationWatcher { status -> AppStatus.message.value = status }
                DJIConnectionManager.startGoHomeStatusWatcher { state ->
                    TelemetryManager.telemetry.value = TelemetryManager.telemetry.value.copy(goHomeStatus = state)
                }
                DJIConnectionManager.startHealthWatcher { warning -> AppStatus.message.value = warning }
                TelemetryManager.startListening()
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