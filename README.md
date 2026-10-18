# AgroScan Pro (dron-control)

Aplicación Android que **mapea lotes de cultivo con un DJI Mini 3**: captura el perímetro del lote con el dron, genera la ruta de barrido,
la vuela de forma automática (repartida en las baterías que haga falta), registra todo para georreferenciar el video y guarda el historial
de lotes, misiones y vuelos. Está pensada para trabajar **sin internet**, en campo.

Es una de las dos partes de un sistema mayor:

| Parte | Qué hace |
|---|---|
| **App móvil (este repositorio)** | Habla con el dron, levanta el perímetro, planifica con *coverage path planning*, vuela la ruta, guarda el historial |
| **App de escritorio** (otro repositorio) | Duplica el historial, hará de estación LAN/espejo durante la misión, extrae los cuadros del video, arma el ortomosaico y diagnostica el cultivo de plátano con IA |

## Qué hace la app

1. **Conectar** con el dron (SDK de DJI v5, control remoto RC-N1).
2. **Levantar el lote**: volar a cada esquina y fijar el vértice (con validación de GPS y de que el polígono no se cruce), o escribir las coordenadas.
3. **Planificar**: altura, traslape lateral y frontal, margen interno, altura del cultivo y modo de captura (video o fotos). Calcula la ruta en zigzag,
   la resolución (GSD), el tiempo, la batería y **cuántas baterías** hacen falta.
4. **Verificar**: checklist previo al despegue (batería, GPS, enlace, tarjeta microSD, ruta dentro del lote, punto de origen…) y plan de salidas. Cada punto que falla explica qué significa; si falta el punto de origen, indica cómo se registra y permite fijarlo en la posición del dron ("Fijar aquí").
5. **Volar** cada salida con Virtual Stick: despega, sube, recorre la ruta grabando, vuelve a casa y se detiene a ~2 m para que el operador decida si es seguro aterrizar.
6. **Guardar el historial**: lote → misiones → vuelos → salidas, con un registro de vuelo (CSV) por salida.

## Arquitectura

Aplicación de una sola `Activity` con Jetpack Compose. La lógica de dominio es pura Kotlin (sin SDK ni Android) y está probada; el SDK de DJI queda en
`sdk/`, `telemetry/`, `flight/` y `mission/MissionExecutor`.

| Paquete | Responsabilidad | Archivos principales |
|---|---|---|
| `survey/` | Geometría del lote y levantamiento del perímetro | `GeoCoord` (`LocalFrame`), `PolygonGeometry`, `SurveySession`, `SurveyVertex` |
| `planning/` | Fotogrametría y planificación de la ruta | `CoveragePlanner`, `Photogrammetry`, `DroneProfile`, `OverlapPreset`, `RouteChecks` |
| `mission/` | La ruta, su reparto en baterías y su ejecución | `WaypointPlan`, `SortiePlanner`, `Preflight`, `MissionRunner`, `MissionExecutor`, `FlightLog` |
| `data/` | Persistencia local (Room) | `AgroDatabase`, `Entities`, `Daos`, `AgroRepository`, `Migrations` |
| `map/` | Mapa satelital (Mapbox) y mapas sin conexión | `AgroMap`, `MapConfig`, `OfflineMaps`, `NetworkMonitor` |
| `sdk/` | Conexión con el dron y utilidades del SDK | `DJIConnectionManager`, `DJIUtils`, `AppStatus` |
| `telemetry/` | Telemetría y estado del dron | `TelemetryManager`, `TelemetryData`, `DroneInfo`, `GpsQuality` |
| `flight/` | Órdenes de vuelo y aterrizaje | `FlightActions`, `LandingGuard` |
| `camera/` | Órdenes a la cámara (modo y disparo) | `CameraControl` |
| `ui/` | Pantallas y componentes | `MainActivity` (navegación), `*Screen.kt`, `AgroUi`, `AgroTheme`, `Formatters` |

Reparto del equipo: el módulo SIG y de planificación (`survey/`, `planning/`, `mission/WaypointPlan`, `data/`, `map/`) y la telemetría/conexión
(`sdk/`, `telemetry/`, `flight/`, `MissionExecutor`) son responsabilidades distintas; el contrato entre ambos es la ruta ([`WaypointPlan`](app/src/main/java/com/jesus/dronplataneras/mission/WaypointPlan.kt)).

### Flujo de una misión

```
Inicio ─► Editor de perímetro ─► Planificación ─► Verificación ─► Misión en vuelo
            (SurveySession)       (CoveragePlanner)   (Preflight +      (MissionRunner ─► MissionExecutor)
                                  (Photogrammetry)     SortiePlanner)            │
                                                                                 ▼
                                              Room: vuelos / salidas  +  registro de vuelo (CSV)
```

- `CoveragePlanner` genera el zigzag; `Photogrammetry` convierte altura y traslape en separación entre pasadas, velocidad, tiempo y batería.
- `SortiePlanner` parte la ruta en **salidas** (una por batería), siempre entre pasadas, y calcula desde dónde reanudar tras una interrupción.
- `MissionRunner` valida el pre-vuelo, abre el vuelo y la salida en la base y lanza `MissionExecutor`; al terminar, cierra la salida y avanza el punto de reanudación.
- `MissionExecutor` vuela con Virtual Stick a 10 Hz, graba el video o dispara las fotos y escribe el registro de vuelo.

### Seguridad del vuelo

- El pre-vuelo es el mismo en la pantalla, en el coordinador y en el ejecutor (`Preflight`).
- El ejecutor aborta por enlace perdido, mando recuperado por el piloto, batería baja, GPS ausente o desactualizado, dirección incorrecta o cámara que deja de captar.
- Antes de despegar prueba la cámara (foto de prueba) y verifica el gimbal a nadir.
- El regreso a casa no aterriza solo: `LandingGuard` lo detiene a ~2 m y la barra de decisión pregunta al operador.
- Mientras el dron está en el aire no se puede salir de la pantalla de misión; hay un botón de control manual.

## Datos

Toda la estructura de datos (base de datos, JSON de la ruta, registro de vuelo, archivos de intercambio, estado en memoria) está en
[docs/ESTRUCTURA_DE_DATOS.md](docs/ESTRUCTURA_DE_DATOS.md). El diagrama de la base de datos está en [docs/DIAGRAMA_BD.md](docs/DIAGRAMA_BD.md).

## Requisitos y compilación

- Android Studio reciente, JDK 21 (lo descarga Gradle según `gradle/gradle-daemon-jvm.properties`) y el SDK de Android (`compileSdk` 37). Dispositivo `arm64-v8a` (el SDK de DJI solo lo admite), `minSdk` 24, orientación horizontal.
- Un `local.properties` en la raíz (no se sube al repositorio):

  ```properties
  sdk.dir=C\:\\Users\\...\\Android\\Sdk
  DJI_API_KEY=<clave de la app registrada en DJI Developer>
  MAPBOX_ACCESS_TOKEN=pk.<token público de Mapbox>
  ```

  `DJI_API_KEY` es obligatoria (también vale como variable de entorno); sin `MAPBOX_ACCESS_TOKEN` el mapa no carga imágenes.

```bash
./gradlew :app:assembleDebug          # compilar el APK
./gradlew :app:testDebugUnitTest      # pruebas unitarias (JVM)
./gradlew :app:connectedDebugAndroidTest   # prueba de migración de la base (requiere dispositivo o emulador)
```

El build `debug` tiene `isDebuggable = false`: no se puede depurar ni leer el almacenamiento privado con `adb run-as`. Los registros de vuelo
solo se leen desde la app o con un build depurable.

## Pruebas

Las pruebas unitarias cubren la geometría, el planificador, la fotogrametría, el reparto en salidas, el pre-vuelo, el registro de vuelo, la lectura de la ruta
y la guardia de aterrizaje. **No hay pruebas del ejecutor con el dron**: antes de volar de verdad, probarlo en el simulador de DJI y a baja altura.

## Limitaciones conocidas y pendientes

- **Lotes cóncavos:** el planificador solo avisa; la ruta puede salirse del lote (la verificación la bloquea si se aleja más de 10 m).
- **Servicio en primer plano:** no hay; Android podría pausar el proceso en una misión larga. La pantalla se mantiene encendida.
- **Ejes de Virtual Stick:** `MissionExecutor.groundPitchIsEast` se ajusta a mano; falta una calibración automática.
- **Espejo en vivo y sincronización con el escritorio:** los datos están preparados (UUID, registro de vuelo) pero no se transmiten.
- **NTRIP:** la pantalla es un formulario provisional que aún no abre ninguna conexión.
- **Copias de seguridad:** `allowBackup="true"` con reglas de plantilla; ver [docs/ESTRUCTURA_DE_DATOS.md](docs/ESTRUCTURA_DE_DATOS.md#14-limitaciones-conocidas).
