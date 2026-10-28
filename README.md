# AgroScan Pro (dron-control)

Aplicación Android que **mapea lotes de cultivo con un DJI Mini 3**: captura el perímetro del lote con el dron, genera la ruta de barrido,
la vuela de forma automática (repartida en las baterías que haga falta), registra todo para georreferenciar el video y guarda el historial
de lotes, misiones y vuelos. Está pensada para trabajar **sin internet**, en campo.

En el teléfono la app se llama **AgroScan-Pilot** (recurso `launcher_label`; el SDK de DJI sobrescribe `app_name`, por eso no se usa ese nombre).

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
7. **Red local**: unirse desde la propia app al Wi-Fi `AgroScan` de la PC de escritorio (ver [Red local y galería](#red-local-y-galería-del-dron)).
8. **Galería del dron**: ver las fotos y videos de la microSD y descargarlos al teléfono (`Pictures/AgroScan`, `Movies/AgroScan`).

## Arquitectura

Aplicación de una sola `Activity` con Jetpack Compose. La lógica de dominio es pura Kotlin (sin SDK ni Android) y está probada; el SDK de DJI queda en
`sdk/`, `telemetry/`, `flight/` y `mission/MissionExecutor`.

| Paquete | Responsabilidad | Archivos principales |
|---|---|---|
| `survey/` | Geometría del lote y levantamiento del perímetro | `GeoCoord` (`LocalFrame`), `PolygonGeometry`, `SurveySession`, `SurveyVertex` |
| `planning/` | Fotogrametría y planificación de la ruta | `CoveragePlanner`, `Photogrammetry`, `DroneProfile`, `OverlapPreset`, `RouteChecks` |
| `mission/` | La ruta, su reparto en baterías y su ejecución | `WaypointPlan`, `SortiePlanner`, `Preflight`, `MissionRunner`, `MissionExecutor`, `FlightLog`, `SdCard`, `DistanceMarker` |
| `data/` | Persistencia local (Room) | `AgroDatabase`, `Entities`, `Daos`, `AgroRepository`, `Migrations` |
| `map/` | Mapa satelital (Mapbox) y mapas sin conexión | `AgroMap`, `MapConfig`, `OfflineMaps`, `NetworkMonitor` |
| `sdk/` | Conexión con el dron y utilidades del SDK | `DJIConnectionManager`, `DJIUtils`, `AppStatus` |
| `telemetry/` | Telemetría y estado del dron | `TelemetryManager`, `TelemetryData`, `DroneInfo`, `GpsQuality` |
| `flight/` | Órdenes de vuelo y aterrizaje | `FlightActions`, `LandingGuard` |
| `camera/` | Órdenes a la cámara (modo y disparo) y archivos de la microSD | `CameraControl`, `MediaGalleryManager` (listar y descargar), `ChunkTracker` (verifica que la descarga llegó completa) |
| `network/` | Enlace Wi-Fi con la PC de escritorio y envío de telemetría en vivo | `PcLink`, `TelemetryStream` (UDP), `TelemetryPacket` (arma el JSON) |
| `ui/` | Pantallas y componentes | `MainActivity` (navegación), `*Screen.kt`, `LocalNetworkScreen`, `GallerySection`, `NorthIndicator`, `AgroUi`, `AgroTheme`, `Formatters` |

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
- En modo video, mientras graba y recorre una pasada, el ejecutor escribe un evento `marca` en el registro cada `foto_cada_m` metros (`DistanceMarker`).
  Las marcas **no se ven en el video**: relacionan el segundo del video con la posición, para que el escritorio limpie y ordene los cuadros
  (formato en [docs/ESTRUCTURA_DE_DATOS.md](docs/ESTRUCTURA_DE_DATOS.md#salida-neventsjsonl-una-línea-json-por-evento)).
- Durante la misión, la ventana pequeña de la cámara muestra una **"N"** roja sobre su borde que apunta al norte según el rumbo del dron.
  Solo se dibuja con el gimbal a nadir (-90° ± 5°, `isNadir`); con la cámara inclinada o sin dato se oculta, porque el norte ya no caería en el borde.

### Seguridad del vuelo

- El pre-vuelo es el mismo en la pantalla, en el coordinador y en el ejecutor (`Preflight`).
- El ejecutor aborta por enlace perdido, mando recuperado por el piloto, batería baja, GPS ausente o desactualizado, dirección incorrecta o cámara que deja de captar.
- Antes de despegar prueba la cámara (foto de prueba) y verifica el gimbal a nadir.
- El cambio de la cámara entre video y foto se pide por el "modo plano" del SDK (`KeyCameraFlatMode`): el Mini 3 rechazaba el cambio clásico (`KeyCameraMode`) siempre con el error -15 (`NEED_REQUEST_AGAIN`), y por eso había que cambiarlo desde DJI Fly. Al preparar la cámara la app avisa "Cámara cambiada a modo …" (o el motivo si no pudo) y, si no lo logra, pide poner el selector foto/video del control remoto.
- El despegue se da por terminado al pasar de **0,5 m** con el dron en el aire (`TAKEOFF_DONE_ALT_M`): el Mini 3 se estabiliza a ~0,9–1,0 m, y con un umbral de 1,0 m las misiones abortaban con `TAKEOFF_FAILED`. Si no lo logra en 20 s, el aviso incluye la altura alcanzada.
- Si la cámara deja de grabar sola, el aborto (`RECORDING_FAILED`) informa el estado de la microSD **en el instante en que se detuvo** (`sdStateAtStop`); leerlo después mostraba "Lista" porque la tarjeta ya se había recuperado y ocultaba la causa real (p. ej. "Está escribiendo lento").
- Al empezar cada salida se baja la **altura de regreso a casa** del dron de los 100 m de fábrica a la de la ruta + 5 m (`FlightActions.returnHomeHeightFor`, mínimo 20 m). Esa altura la guarda el dron y la usan todos los regresos: el final de la misión, el botón RTH, la batería baja y las protecciones propias (enlace perdido). **El Mini 3 no ve obstáculos al frente**: la altura de regreso debe librar árboles, postes y cables entre el lote y el punto de origen. No se restaura al terminar: si vuelas con DJI Fly, revisa ese ajuste.
- El regreso a casa no aterriza solo: `LandingGuard` lo detiene a ~2 m y la barra de decisión pregunta al operador.
- Mientras el dron está en el aire no se puede salir de la pantalla de misión; hay un botón de control manual.

## Red local y galería del dron

Se entra desde el botón **Red local** de la pantalla de inicio (`LocalNetworkScreen`, dos pestañas).

**Conectar a PC** (`network/PcLink`)
- La PC de escritorio crea la zona Wi-Fi de Windows `AgroScan`; su IP siempre es `192.168.137.1` (editable en la pantalla).
- La app se une sola a esa red con la contraseña que muestra la PC (`WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork`, tiempo máximo 30 s).
  Android pide confirmar **una vez** con su propio aviso. La red es de uso exclusivo de la app y no tiene internet: el tráfico hacia la PC debe salir por `PcLink.network`, no por un socket normal.
- Si el teléfono ya está en la subred de la PC (unido desde Ajustes) basta con eso y no pide contraseña.
- La contraseña se valida antes de pedir la red: **8 a 63 caracteres ASCII** (`PcLink.isValidPassword`); con otra cosa Android lanzaría una excepción. Cualquier excepción al armar o enviar la petición se muestra como aviso y el estado vuelve a "PC desconectada" (nunca se queda en "Conectando…").
- Requiere Android 10 (API 29) o más; en versiones anteriores hay que unirse al Wi-Fi desde Ajustes. Permiso añadido: `CHANGE_NETWORK_STATE`.
- **Telemetría en vivo (`network/TelemetryStream`):** con el teléfono conectado a la PC, cada 0,5 s manda por UDP (puerto 8765) la posición, batería, velocidad y estado de la misión, y cada 5 s, desde que se inicia una salida, la misión (nombre, número de salida, altura, modo, origen y ruta) y el contorno del lote. La app de escritorio los muestra en su pantalla de Telemetría (mapa con ruta y estela, tarjetas, batería del inicio). Va en un hilo aparte y sin conexión: una red caída o lenta nunca afecta al vuelo. Si el Wi-Fi se cae durante una misión, la app lo vuelve a pedir cada 10 s. Formato y reglas en [docs/PROTOCOLO_PC.md](docs/PROTOCOLO_PC.md).
- **Pendiente:** el botón *Enviar Polígono a PC* sigue deshabilitado a propósito: no hay protocolo definido para polígonos ni historial (solo para la telemetría en vivo).

**Galería del dron** (`ui/GallerySection`, `camera/MediaGalleryManager`)
- Lista las fotos y videos de la microSD con su miniatura y permite descargarlos al teléfono: `Pictures/AgroScan` (fotos) y `Movies/AgroScan` (videos), vía MediaStore (Android 10+). Un archivo a la vez, con barra de progreso y botón para cancelar.
- Con el gestor de archivos del SDK activo la cámara no puede grabar ni volar, por eso solo se activa mientras la galería se usa y se libera al salir. **Está bloqueada con el dron en el aire, en misión o desconectado.**
- El SDK entrega el archivo en trozos con su posición (`offset`), a veces desordenados o repetidos, y avisa "terminó" incluso con errores. Por eso cada trozo se escribe en su posición exacta y la descarga solo se da por buena si llegaron **todos** los bytes (`ChunkTracker`); si faltan, espera hasta 5 s a los trozos tardíos y, si no llegan, borra el archivo a medias y avisa. Antes se guardaban videos corruptos por no hacer esto.
- El avance (porcentaje y MB/s, como en DJI Fly) se calcula con los **bytes ya guardados** sobre el tamaño del archivo y se refresca cada 0,5 s. No se usa el `onProgress` del SDK: sus dos números llegaban en otro orden y marcaba 100 % desde el primer instante aunque la descarga ni hubiera empezado.
- La descarga no es automática: el video no se pasa solo a la PC (no hay servidor ni protocolo todavía).

## Tarjeta microSD

Para grabar 4K el Mini 3 escribe ~100 Mbps (≈12,5 MB/s sostenidos); la tarjeta debe ser **U3/V30** o mejor, de marca (la velocidad de lectura es irrelevante para grabar). Hasta 32 GB se formatea FAT32 y por encima exFAT.
`mission/SdCard` traduce el estado que informa la cámara a un mensaje y a un nivel (info/aviso/error); `WRITING_SLOWLY` es error en video y aviso en fotos. Una tarjeta lenta provoca la pérdida de grabación a los pocos segundos (visto con una ENV 64 GB): el ejecutor aborta con `RECORDING_FAILED`.
Los eventos `grabando` / `grabacion_detenida` del registro llevan el estado de la tarjeta en ese momento.

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
y la guardia de aterrizaje; y, de lo añadido después, `DistanceMarker` (marcas por distancia), `ChunkTracker` (descarga completa), `PcLink` (IP, subred y contraseña Wi-Fi) y `NorthIndicator` (posición de la "N" y gimbal a nadir).
**No hay pruebas del ejecutor con el dron**: antes de volar de verdad, probarlo en el simulador de DJI y a baja altura.
Tampoco se probaron sin dron la unión al Wi-Fi (`requestNetwork`) ni la escritura de la descarga (`MediaGalleryManager.Job`): solo su lógica pura.

## Limitaciones conocidas y pendientes

- **Lotes cóncavos:** el planificador solo avisa; la ruta puede salirse del lote (la verificación la bloquea si se aleja más de 10 m).
- **Servicio en primer plano:** no hay; Android podría pausar el proceso en una misión larga. La pantalla se mantiene encendida.
- **Ejes de Virtual Stick:** `MissionExecutor.groundPitchIsEast` se ajusta a mano; falta una calibración automática.
- **Espejo en vivo y sincronización con el escritorio:** los datos están preparados (UUID, registro de vuelo) pero no se transmiten. La app ya se une al Wi-Fi de la PC (`PcLink`) y le manda la telemetría en vivo; falta definir cómo viajan el polígono, el historial y el video (hoy el video sale por la microSD).
- **Regreso a casa (RTH):** en las pruebas iniciales nunca se vio moverse al dron; la misión termina con RTH, así que hay que confirmarlo a baja altura antes de una misión real.
- **Tarjeta microSD:** la ENV 64 GB probada es demasiado lenta para 4K; falta confirmar la Kingston Canvas Go! Plus (primero 4K ≥ 2 min en DJI Fly, luego una misión corta o en modo fotos).
- **Copias de seguridad:** `allowBackup="true"` con reglas de plantilla; ver [docs/ESTRUCTURA_DE_DATOS.md](docs/ESTRUCTURA_DE_DATOS.md#14-limitaciones-conocidas).
