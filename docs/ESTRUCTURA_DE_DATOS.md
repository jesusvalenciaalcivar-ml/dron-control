# Estructura de datos de AgroScan

Documento de referencia de **todos** los datos de la app móvil: dónde se guardan, con qué forma y para qué se usan.
Las tablas se generaron a partir del esquema exportado por Room (`app/schemas/com.jesus.dronplataneras.data.AgroDatabase/2.json`),
así que coinciden con la base real. El diagrama está en [DIAGRAMA_BD.md](DIAGRAMA_BD.md).

## Índice
1. [Dónde se guarda cada dato](#1-dónde-se-guarda-cada-dato)
2. [Base de datos local (Room)](#2-base-de-datos-local-room)
3. [La ruta (JSON)](#3-la-ruta-json)
4. [Perímetro copiado en cada vuelo](#4-perímetro-copiado-en-cada-vuelo)
5. [Registro de vuelo (CSV y eventos)](#5-registro-de-vuelo-csv-y-eventos)
6. [Archivos de intercambio](#6-archivos-de-intercambio)
7. [Preferencias y mapas sin conexión](#7-preferencias-y-mapas-sin-conexión)
8. [Estado en memoria](#8-estado-en-memoria)
9. [Configuración de compilación](#9-configuración-de-compilación)
10. [Datos que quedan en el dron](#10-datos-que-quedan-en-el-dron)
11. [Quién escribe y quién lee cada dato](#11-quién-escribe-y-quién-lee-cada-dato)
12. [Reglas de integridad y ciclo de vida](#12-reglas-de-integridad-y-ciclo-de-vida)
13. [Sincronización con el escritorio](#13-sincronización-con-el-escritorio)
14. [Limitaciones conocidas](#14-limitaciones-conocidas)

## 1. Dónde se guarda cada dato

| Dato | Dónde | Persiste | Para qué |
|---|---|---|---|
| Lotes, vértices, misiones, parámetros, vuelos, salidas | Base SQLite `agroscan.db` (Room, versión 2) | sí | Historial completo y trabajo sin internet |
| Ruta de waypoints | Columna JSON `misiones.rutaWaypointsJson` y copia en `vuelos.rutaJson` | sí | Lo que vuela el ejecutor y lo que se exporta |
| Registro de vuelo | Archivos en `getExternalFilesDir/vuelos/<uuid del vuelo>/` (`Android/data/<app>/files/…`, visible por USB) | sí | Georreferenciar el video en el escritorio |
| Tipo de mapa elegido | SharedPreferences `agroscan_mapa` | sí | Recordar satélite/estándar |
| Mapas sin conexión | TileStore de Mapbox (regiones `agroscan-…`) | sí | Ver el mapa sin internet |
| Lote exportado / ruta importada | Archivos que elige el usuario | sí | Intercambio con el escritorio y otras herramientas |
| Telemetría, estado de la misión, vértices en captura | Memoria (objetos Kotlin) | **no** | Pantallas en vivo |
| Claves de DJI y Mapbox | `local.properties` → manifiesto | en compilación | Registro del SDK y mapa |
| Video / fotos | microSD del dron | sí (en el dron) | Material para el ortomosaico |
| Fotos / videos descargados | Teléfono: `Pictures/AgroScan` y `Movies/AgroScan` (MediaStore), solo si el operador los descarga desde la galería | sí | Copia en el teléfono / paso a la PC |

## 2. Base de datos local (Room)

Archivo `agroscan.db`, versión **2**. Es la fuente de verdad del historial y funciona sin internet.
Los cambios de esquema se hacen siempre con una migración ([Migrations.kt](../app/src/main/java/com/jesus/dronplataneras/data/Migrations.kt));
nunca se usa `fallbackToDestructiveMigration`, porque borraría lotes levantados en campo.

### Relaciones

```
lotes 1 ──< vertices                lotes 1 ──< misiones >── 1 parametros_vuelo
misiones 1 ──< vuelos 1 ──< salidas
```

- Un **lote** tiene un perímetro (vértices) y varias **misiones** (planes).
- Una **misión** tiene unos parámetros y una ruta, y se puede **volar varias veces** (cada ejecución es un **vuelo**).
- Un **vuelo** se parte en **salidas**: una por batería.
- Todas las claves foráneas son `ON DELETE CASCADE`: borrar un lote borra sus vértices, misiones, vuelos y salidas.

### `lotes`

Un lote (polígono) a mapear.

| Columna | Tipo | Nulo | Clave | Descripción |
|---|---|---|---|---|
| `id` | INTEGER | no | PK | Clave local autoincremental. |
| `nombre` | TEXT | no |  | Nombre que ve el operador. |
| `areaEstimadaHa` | REAL | no |  | Área en hectáreas, calculada al guardar el perímetro. |
| `fechaCreacion` | INTEGER | no |  | Epoch en milisegundos. |
| `uuid` | TEXT | no | UQ | Identificador global (UUID v4) para duplicar el historial en el escritorio sin colisiones. |

**Restricciones e índices:** índice único `index_lotes_uuid` sobre (uuid).

### `vertices`

Los vértices del perímetro de un lote.

| Columna | Tipo | Nulo | Clave | Descripción |
|---|---|---|---|---|
| `id` | INTEGER | no | PK | Clave local. |
| `loteId` | INTEGER | no | FK | Lote al que pertenece. |
| `ordenSecuencia` | INTEGER | no |  | Posición en el perímetro (desde 0). El orden es parte de la geometría; el perímetro se reconstruye siempre ordenando por este campo. |
| `latitud` | REAL | no |  | Grados decimales WGS84. |
| `longitud` | REAL | no |  | Grados decimales WGS84. |

**Restricciones e índices:** FK `loteId` → `lotes.id` (ON DELETE CASCADE); índice único `index_vertices_loteId_ordenSecuencia` sobre (loteId, ordenSecuencia).

### `parametros_vuelo`

Parámetros con los que se planifica la ruta de una misión.

| Columna | Tipo | Nulo | Clave | Descripción |
|---|---|---|---|---|
| `id` | INTEGER | no | PK | Clave local. |
| `alturaMetros` | REAL | no |  | Altura de vuelo sobre el punto de despegue (m). |
| `traslapePorcentaje` | REAL | no |  | Traslape **lateral** entre pasadas (%). Define la separación entre pasadas. |
| `margenInternoMetros` | REAL | no |  | Margen interno del polígono (m): las pasadas no llegan al borde del lote. |
| `alturaCopaMetros` | REAL | no |  | Altura del cultivo (m). El traslape se calcula en la copa, que está más cerca de la cámara que el suelo. |
| `traslapeFrontalPorcentaje` | REAL | no |  | Traslape entre cuadros consecutivos (%). En video solo define cada cuánto se extrae un cuadro; no cambia el vuelo. |
| `modoCaptura` | TEXT | no |  | `VIDEO` (por defecto) o `PHOTO`. |



### `misiones`

El **plan** de un lote: parámetros y ruta. Se puede volar varias veces.

| Columna | Tipo | Nulo | Clave | Descripción |
|---|---|---|---|---|
| `id` | INTEGER | no | PK | Clave local. |
| `nombre` | TEXT | no |  | Nombre de la ruta, p. ej. "Finca Sanchez - Sector 1". |
| `loteId` | INTEGER | no | FK | Lote que se mapea. |
| `parametrosId` | INTEGER | no | FK | Parámetros de la misión (relación 1 a 1 en la práctica). |
| `estado` | TEXT | no |  | `BORRADOR`, `SINCRONIZADO` o `COMPLETADO` (ver enumeración más abajo). |
| `duracionEstimadaMin` | REAL | no |  | Duración estimada del vuelo (min), para listas. |
| `fotosEstimadas` | INTEGER | no |  | Capturas previstas: fotos a disparar o, en video, cuadros a extraer. |
| `rutaWaypointsJson` | TEXT | sí |  | Ruta serializada (formato en la sección 3). `NULL` mientras es borrador. |
| `fechaCreacion` | INTEGER | no |  | Epoch en milisegundos. |
| `fechaActualizacion` | INTEGER | no |  | Epoch en milisegundos; ordena las listas. |
| `uuid` | TEXT | no | UQ | Identificador global para sincronizar con el escritorio. |

**Restricciones e índices:** FK `loteId` → `lotes.id` (ON DELETE CASCADE); FK `parametrosId` → `parametros_vuelo.id` (ON DELETE CASCADE); índice `index_misiones_loteId` sobre (loteId); índice `index_misiones_parametrosId` sobre (parametrosId); índice `index_misiones_estado` sobre (estado); índice único `index_misiones_uuid` sobre (uuid).

### `vuelos`

Una **ejecución** de una misión (puede necesitar varias baterías). Guarda copia del perímetro y la ruta volados.

| Columna | Tipo | Nulo | Clave | Descripción |
|---|---|---|---|---|
| `id` | INTEGER | no | PK | Clave local. |
| `uuid` | TEXT | no | UQ | Identificador global; también es el nombre de la carpeta de los registros de vuelo. |
| `misionId` | INTEGER | no | FK | Misión que se voló. |
| `estado` | TEXT | no |  | `EN_CURSO`, `COMPLETADO` o `INTERRUMPIDO`. |
| `fechaInicio` | INTEGER | no |  | Epoch en milisegundos. |
| `fechaFin` | INTEGER | sí |  | Epoch en milisegundos; `NULL` mientras está en curso. |
| `modoCaptura` | TEXT | no |  | Modo con el que se voló (copia del de la ruta). |
| `perimetroJson` | TEXT | no |  | **Copia** del perímetro en ese momento: `[[lat,lon],…]`. Editar el lote después no altera el historial. |
| `rutaJson` | TEXT | no |  | **Copia** de la ruta volada (mismo formato que `misiones.rutaWaypointsJson`). |
| `totalWaypoints` | INTEGER | no |  | Tamaño de la ruta. |
| `siguienteWaypoint` | INTEGER | no |  | Primer waypoint que falta por volar: punto de reanudación entre baterías. El vuelo se completa cuando llega al final. |

**Restricciones e índices:** FK `misionId` → `misiones.id` (ON DELETE CASCADE); índice `index_vuelos_misionId` sobre (misionId); índice único `index_vuelos_uuid` sobre (uuid).

### `salidas`

Un **despegue** con una batería: cubre un tramo de la ruta.

| Columna | Tipo | Nulo | Clave | Descripción |
|---|---|---|---|---|
| `id` | INTEGER | no | PK | Clave local. |
| `uuid` | TEXT | no | UQ | Identificador global. |
| `vueloId` | INTEGER | no | FK | Vuelo al que pertenece. |
| `indice` | INTEGER | no |  | Posición dentro del vuelo (desde 0). Cuenta también los intentos cancelados antes de despegar. |
| `desdeWaypoint` | INTEGER | no |  | Primer waypoint de la ruta completa que cubre esta salida. |
| `hastaWaypoint` | INTEGER | no |  | Último waypoint (incluido). |
| `estado` | TEXT | no |  | `EN_CURSO`, `COMPLETADA` o `INTERRUMPIDA`. |
| `motivo` | TEXT | sí |  | Por qué terminó, en texto para el operador. |
| `inicioMs` | INTEGER | no |  | Epoch en milisegundos. |
| `finMs` | INTEGER | sí |  | Epoch en milisegundos; `NULL` mientras vuela. |
| `bateriaInicio` | INTEGER | no |  | % de batería al empezar. |
| `bateriaFin` | INTEGER | sí |  | % de batería al terminar. |
| `videoInicioMs` | INTEGER | sí |  | Epoch UTC (ms) del inicio de la grabación, para emparejarla con el archivo de la microSD. |
| `videoFinMs` | INTEGER | sí |  | Epoch UTC (ms) del fin de la grabación. |
| `distanciaM` | REAL | no |  | Metros recorridos de ruta en esta salida. |
| `logPath` | TEXT | sí |  | Ruta absoluta del CSV del registro de vuelo en el almacenamiento de la app. |

**Restricciones e índices:** FK `vueloId` → `vuelos.id` (ON DELETE CASCADE); índice único `index_salidas_vueloId_indice` sobre (vueloId, indice); índice único `index_salidas_uuid` sobre (uuid).

### Enumeraciones (se guardan como texto)

| Enumeración | Valores | Significado |
|---|---|---|
| `EstadoMision` | `BORRADOR` | Parámetros guardados, todavía sin ruta |
| | `SINCRONIZADO` | Tiene ruta lista para volar sin internet |
| | `COMPLETADO` | Se voló completa al menos una vez (el detalle está en sus vuelos) |
| `EstadoVuelo` | `EN_CURSO` | Hay salidas por volar o se está volando una |
| | `COMPLETADO` | Se cubrió toda la ruta |
| | `INTERRUMPIDO` | El operador lo dio por terminado sin cubrir toda la ruta |
| `EstadoSalida` | `EN_CURSO` / `COMPLETADA` / `INTERRUMPIDA` | Estado de un despegue |
| `CaptureMode` | `VIDEO` / `PHOTO` | Modo de captura (en el JSON de la ruta: `"video"` / `"foto"`) |

### Consultas y modelos de lectura (no son tablas)

| Modelo | Contiene | Lo usan |
|---|---|---|
| `LoteConVertices` | Un lote y su lista de vértices | Listas, planificación, mapa |
| `MisionCompleta` | Misión + lote (con vértices) + parámetros | Inicio, misiones guardadas, verificación |
| `VueloCompleto` | Vuelo + sus salidas | Verificación e historial |

`LoteConVertices.perimetro()` reconstruye el perímetro **ordenando por `ordenSecuencia`** (Room no garantiza el orden de la relación).
`VueloCompleto.salidasVoladas()` cuenta solo las salidas que despegaron (las canceladas en tierra no gastaron batería).

## 3. La ruta (JSON)

Es el contrato entre el planificador y el ejecutor. Se guarda en `misiones.rutaWaypointsJson` y `vuelos.rutaJson`, y también se puede importar/exportar.
Versión actual: **2**. La genera `WaypointPlan.toJson()` y la lee `WaypointPlan.parse()` (acepta además un GeoJSON `LineString`).

```json
{
  "formato": "agroscan-waypoints",
  "version": 2,
  "mision": "Finca Sanchez - Sector 1",
  "altura_m": 60,
  "velocidad_ms": 5.0,
  "foto_cada_m": 11.8,
  "rumbo_deg": 90,
  "modo_captura": "video",
  "gimbal_pitch_deg": -90,
  "waypoints": [
    { "lat": -3.2601, "lon": -79.9602, "alt_m": 60, "foto": false, "barrido": false },
    { "lat": -3.2611, "lon": -79.9590, "alt_m": 60, "foto": false, "barrido": true }
  ]
}
```

| Campo | Unidad | Significado |
|---|---|---|
| `mision` | texto | Nombre (opcional) |
| `altura_m` | m | Altura sobre el punto de despegue por defecto (5–120) |
| `velocidad_ms` | m/s | Velocidad de las pasadas (0,5–15); si falta, el ejecutor usa el máximo del perfil |
| `foto_cada_m` | m | En foto: distancia entre disparos (0 = solo en waypoints con `foto`). En video: separación con la que el escritorio debe **extraer cuadros** |
| `rumbo_deg` | grados brújula | Rumbo fijo del dron durante la misión (0 = norte, 90 = este); si falta, mantiene el actual |
| `modo_captura` | `video`/`foto` | Si falta se asume `video` |
| `gimbal_pitch_deg` | grados | Inclinación del gimbal (−90 = nadir; rango −90–0) |
| `waypoints[].lat/lon` | grados WGS84 | Posición |
| `waypoints[].alt_m` | m | Altura de ese waypoint (por defecto `altura_m`) |
| `waypoints[].foto` | bool | Disparar al llegar (solo modo foto) |
| `waypoints[].barrido` | bool | `true` = el tramo que **llega** a este waypoint es de barrido; `false` = giro/tránsito. Marca dónde empieza cada pasada, que es donde se corta entre baterías o se reanuda. Sin el campo se asume `true` |

Validaciones al importar: 2–2000 waypoints, coordenadas en rango y distintas de (0,0), altura 5–120 m, velocidad 0,5–15 m/s.

## 4. Perímetro copiado en cada vuelo

`vuelos.perimetroJson` guarda el perímetro tal como estaba al volar: un arreglo `[[lat,lon],…]` en el orden del polígono.
Existe para que editar el lote después no cambie el historial (y poder comparar mapeos en el tiempo).

## 5. Registro de vuelo (CSV y eventos)

Carpeta `Android/data/com.jesus.dronplataneras/files/vuelos/<uuid del vuelo>/` (`getExternalFilesDir`; si no existe, `filesDir`), con dos archivos por salida. Los escribe `FlightLogWriter` mientras vuela el ejecutor.
`salidas.logPath` apunta al CSV. **Borrar un vuelo de la base no borra estos archivos.**

### `salida-N.csv` (una fila cada ~200 ms)

```
t_ms,lat,lon,alt_m,yaw_deg,gimbal_pitch_deg,speed_ms,wp,tramo,fase,grabando
```

| Columna | Unidad | Significado |
|---|---|---|
| `t_ms` | ms | Epoch UTC: la misma escala que la fecha del archivo de video de la microSD |
| `lat`, `lon` | grados | Posición del dron |
| `alt_m` | m | Altura relativa al despegue |
| `yaw_deg` | grados | Rumbo del dron |
| `gimbal_pitch_deg` | grados | Inclinación del gimbal |
| `speed_ms` | m/s | Velocidad horizontal |
| `wp` | índice | Waypoint objetivo, **relativo a la subruta de esa salida** (sumar `salidas.desdeWaypoint` para el global) |
| `tramo` | texto | `ascenso`, `transito`, `barrido` o `giro` |
| `fase` | texto | Fase del ejecutor (`FLYING`, `PAUSED`…) |
| `grabando` | 0/1 | Si la cámara estaba grabando |

### `salida-N.events.jsonl` (una línea JSON por evento)

Cada línea tiene `t_ms` y `evento`, más los campos propios del evento:

| Evento | Campos | Cuándo |
|---|---|---|
| `inicio` | `waypoints`, `modo`, `altura_m` | Empieza la salida |
| `rth_altura`, `rth_altura_error` | `altura_m`, `detalle` | Al empezar la salida se fija la altura de regreso a casa del dron (altura máxima de la ruta + 5 m, entre 20 y 500 m). `rth_altura_error` = el dron no la aceptó y conserva la que tenía (de fábrica, 100 m); la misión no se aborta |
| `rumbo_a_waypoint` | `wp`, `tramo` | Empieza un tramo |
| `waypoint_alcanzado` | `wp` | Llega a un waypoint |
| `pausa`, `reanuda` | – | El operador pausa o reanuda |
| `video_inicio`, `video_fin` | – | Marcas del video de esta salida |
| `marca` | `n`, `t_video_s`, `lat`, `lon`, `alt_m`, `velocidad_ms`, `wp`, `ruta_m` | Solo en modo video, grabando y en tramo de barrido: una al empezar cada pasada y luego una cada `foto_cada_m` metros recorridos (la separación entre cuadros del plan). `t_video_s` = segundos desde que la cámara empezó a grabar (puede diferir unas décimas del primer cuadro real); `ruta_m` = distancia acumulada de la ruta. Sirve para saber en qué punto del video está cada posición |
| `grabando`, `grabacion_detenida` | `tarjeta` | La cámara empezó o dejó de grabar (también si lo hizo sola). `tarjeta` es el estado de la microSD **en ese instante** (`NORMAL`, `WRITING_SLOWLY`, `SLOW`, `FULL`, `INVALID_FILE_SYSTEM`…); una tarjeta lenta suele aparecer aquí como `WRITING_SLOWLY` justo antes de que se corte la grabación |
| `foto_ok`, `foto_error` | `n` / `detalle` | Resultado de cada disparo. Un rechazo se reintenta a los 1,5 s; tras 5 rechazos seguidos se detiene la misión |
| `foto_de_prueba_ok`, `foto_de_prueba_fallo` | `detalle` | Foto de prueba antes de despegar (el "OK" solo dice que la cámara aceptó la orden) |
| `camara_error` | `paso`, `detalle` | Falló cambiar el modo, la resolución o el modo de disparo |
| `camara_modo` | `pedido`, `antes`, `actual`, `ok` | Resultado de preparar la cámara antes de despegar: modo pedido (`video`/`foto`), el que tenía antes, el que tiene ahora y si coincide. Con `ok` = `true` el operador ve "Cámara cambiada a modo …" (o "ya estaba en …"); con `false`, el motivo del SDK |
| `camara_modo_no_confirmado`, `camara_modo_sigue_incorrecto` | `modo_actual`, `error_app` | La cámara no pasó al modo pedido |
| `autoridad` | `motivo` | Cambió quién controla el dron |
| `fin` | `motivo`, `mensaje`, `wp` | Termina la salida (`motivo` es un `EndReason`) |

`EndReason`: `COMPLETED`, `PILOT_STOP`, `RTH_REQUESTED`, `LOW_BATTERY`, `LINK_LOST`, `AUTHORITY_LOST`, `NO_GPS`, `STALE_POSITION`,
`TAKEOFF_FAILED`, `VIRTUAL_STICK_FAILED`, `RECORDING_FAILED`, `GIMBAL_FAILED`, `CAMERA_FAILED`, `WRONG_DIRECTION`, `ERROR`.

Notas de dos motivos de `fin` que se confundían al diagnosticar:

- `TAKEOFF_FAILED`: el despegue automático no confirmó altura. Se considera terminado al superar **0,5 m** con el dron en el aire; si pasan 20 s sin lograrlo, el `mensaje` incluye la altura alcanzada ("El despegue no terminó a tiempo (altura 0.4 m)").
- `RECORDING_FAILED` con "La cámara dejó de grabar por sí sola": el `mensaje` da el estado de la tarjeta **al detenerse la grabación**, no el de después (que puede haberse recuperado a "Lista"). Debe coincidir con el campo `tarjeta` del evento `grabacion_detenida`.

## 6. Archivos de intercambio

Los elige el usuario con el selector de archivos del sistema.

- **Exportar lote** — GeoJSON `FeatureCollection` con un `Feature` de tipo `Polygon` (anillo exterior en sentido antihorario, cerrado).
  Propiedades: `lote_id`, `lote_uuid`, `name`, `crop` (`"platano"`), `created_at` (ISO 8601 UTC), `area_m2`, `perimeter_m` y,
  si hay misión, `altura_m`, `traslape_pct`, `margen_interno_m`. No incluye la altura del cultivo, el traslape frontal ni el modo de captura.
- **Importar ruta** — el JSON de la sección 3 o un GeoJSON con un `LineString` (coordenadas `[lon, lat, alt?]`).

## 7. Preferencias y mapas sin conexión

- **SharedPreferences `agroscan_mapa`** — clave `tipo` = `SATELITE` o `ESTANDAR`. Lo gestiona `MapConfig`.
- **Mapas sin conexión** — regiones del `TileStore` de Mapbox con id `agroscan-<epoch>-<ancho>x<alto>` (metros), zoom 10–18, estilos satélite y estándar.
  Las gestiona `OfflineMaps`; no están en la base de datos.

## 8. Estado en memoria

Se pierde al cerrar la app. Son objetos Kotlin con estado observable desde Compose.

| Objeto | Contiene | Lo escribe | Lo leen |
|---|---|---|---|
| `TelemetryData` (en `TelemetryManager`) | Batería, altitud, velocidad, posición, satélites, nivel GPS, home, modo de vuelo, calidad del enlace, estado del regreso a casa | Listeners del SDK | Todas las pantallas, pre-vuelo, ejecutor |
| `DroneInfo` | Modelo, inclinación del gimbal, motores, rumbo, tarjeta microSD, modo de cámara, segundos de video y fotos libres | Listeners del SDK | Pre-vuelo, ejecutor, mapa, marca "N" de la cámara (rumbo e inclinación del gimbal) |
| `PcLink` | Estado del enlace con la PC (`DISCONNECTED`, `CONNECTING`, `CONNECTED`), la red Wi-Fi a la que se unió la app, la IP de la PC y (solo en memoria) la contraseña de la última conexión para reconectar a mitad de una misión | `PcLink` (al conectar, desconectar o perder la red) | Pantalla de red local, `TelemetryStream` |
| `MediaGalleryManager.download` | Archivo que se está descargando de la microSD y su porcentaje (uno a la vez) | `MediaGalleryManager` | Galería del dron |
| `DJIConnectionManager` | Conectado, volando, confirmación de aterrizaje pendiente | Listeners del SDK | Pantallas, ejecutor, `LandingGuard` |
| `SurveySession` | Vértices en captura (lat, lon, altitud, satélites, nivel GPS, hora), lote en edición | Pantalla de cámara y editor de perímetro | Editor de perímetro |
| `MissionExecutor.Status` | Fase, waypoint, distancias, progreso, grabando, tiempo, fotos tomadas/rechazadas, mensaje | Ejecutor | Pantalla de misión |
| `MissionRunner.RunContext` | Misión, vuelo y salida en curso, desplazamiento de waypoints, distancias, número de salida | Coordinador | Pantalla de misión |
| `LandingGuard.holdingForDecision` | El dron espera a ~2 m que el operador decida aterrizar | `LandingGuard` | Barra de decisión |
| `AppStatus.message` | Mensaje flotante | Cualquiera | `StatusToast` |

Los datos de calidad de cada vértice capturado (altitud, satélites, nivel GPS, hora) **no se guardan**: a la base solo llegan latitud y longitud.

## 9. Configuración de compilación

`local.properties` (no se sube al repositorio) → marcadores del manifiesto:

| Clave | Uso |
|---|---|
| `DJI_API_KEY` | Obligatoria. Registro del SDK de DJI |
| `MAPBOX_ACCESS_TOKEN` | Token público `pk.…`; sin él el mapa no carga imágenes |
| `sdk.dir` | Ruta del SDK de Android |

También se aceptan como variables de entorno.

## 10. Datos que quedan en el dron

El **video** (o las fotos) se guarda en la microSD del dron. La app no lo descarga sola: el operador puede bajar archivos sueltos al teléfono desde la
galería (carpetas `Pictures/AgroScan` y `Movies/AgroScan`, solo con el dron en tierra). El escritorio lo empareja con cada salida mediante las
marcas de tiempo del CSV y `salidas.videoInicioMs` / `videoFinMs`, y con los eventos `marca` para ubicar posiciones dentro del video. El video 4K a 30 fps exige una tarjeta U3/V30
(FAT32 hasta 32 GB, exFAT por encima; si la cámara informa `INVALID_FILE_SYSTEM`, hay que formatearla en exFAT).

## 11. Quién escribe y quién lee cada dato

| Dato | Escribe | Lee |
|---|---|---|
| `lotes`, `vertices` | `AgroRepository.guardarLote` / `actualizarLote` (editor de perímetro y planificación) | Inicio, misiones guardadas, planificación, verificación, mapa |
| `parametros_vuelo`, `misiones` | `AgroRepository.guardarPlanificacion` / `guardarRuta` (planificación) | Inicio, misiones guardadas, verificación |
| `vuelos`, `salidas` | `AgroRepository.iniciarVuelo` / `abrirSalida` / `cerrarSalida` / `terminarVuelo` (desde `MissionRunner` y la verificación) | Verificación (plan de salidas, historial), pantalla de misión |
| CSV y eventos | `FlightLogWriter` (desde `MissionExecutor`) | Aplicación de escritorio |
| `agroscan_mapa` | `MapConfig.setMapType` | Todos los mapas |

## 12. Reglas de integridad y ciclo de vida

- **Perímetro válido:** al guardar un lote se exigen ≥ 3 vértices y que el polígono no se cruce (`AgroRepository`). El orden de los vértices es único por lote.
- **Misión:** `BORRADOR` → (se genera o importa una ruta) `SINCRONIZADO` → (un vuelo se completa) `COMPLETADO`.
  Si cambian los parámetros o el perímetro, la ruta se descarta y la misión vuelve a `BORRADOR`.
- **Vuelo:** solo puede haber uno `EN_CURSO` por misión; al empezar otro, el anterior pasa a `INTERRUMPIDO`.
  `siguienteWaypoint` avanza al cerrar cada salida; el vuelo pasa a `COMPLETADO` (y la misión también) cuando ya no queda ruta.
- **Salida interrumpida:** el punto de reanudación es el **inicio de la pasada interrumpida** (`SortiePlanner.resumeIndex`), para no dejar huecos sin imagen.
- **Salidas huérfanas:** si la app se cierra en pleno vuelo, la salida quedaba `EN_CURSO`; al abrir la app se marcan `INTERRUMPIDA`.
- **Intentos cancelados:** una salida que no llegó a despegar (cámara no lista, pre-vuelo) queda en la base como auditoría, pero no cuenta como salida (`salidasVoladas`).
- **Migraciones:** v1 → v2 añadió `uuid` (con relleno de lo existente), los parámetros de copa/traslape frontal/modo de captura y las tablas `vuelos` y `salidas`.
  La prueba instrumentada `AgroMigrationTest` la valida en un dispositivo (`./gradlew :app:connectedDebugAndroidTest`).

## 13. Sincronización con el escritorio

- Cada lote, misión, vuelo y salida tiene un **UUID**; los ids autoincrementales de Room no sirven entre dispositivos.
- El móvil es la **fuente de verdad** (trabaja sin internet en campo): el escritorio duplica el historial.
- Para georreferenciar el video: emparejar cada archivo de la microSD con una salida por `videoInicioMs`/`videoFinMs` y usar el CSV (`t_ms`) para la posición de cada cuadro.
- **Enlace físico (hecho):** la PC crea el Wi-Fi `AgroScan` (zona móvil de Windows, IP `192.168.137.1`) y la app se une a él con `PcLink`. El tráfico debe salir por `PcLink.network` (no hay internet en esa red).
- **Telemetría en vivo (hecho):** el teléfono manda por UDP (puerto 8765) posición, batería, velocidad, estado de la misión y la ruta; la pantalla de Telemetría de la app de escritorio los muestra. Formato en [PROTOCOLO_PC.md](PROTOCOLO_PC.md). Es un reflejo: no guarda nada y un paquete perdido no se recupera; el registro de vuelo (CSV y eventos) sigue siendo la fuente de verdad.
- **Registros de vuelo (hecho):** al cerrar cada salida (y con el botón *Enviar vuelos pendientes*) el teléfono manda por HTTP (puerto 8766) el CSV y los eventos con las marcas del video de cada salida que la PC no tenga, junto con `videoInicioMs`/`videoFinMs` para emparejar el video. Lo hace `network/FlightLogSync`; la lectura es `VueloDao.salidasParaEnviar`. Formato en [PROTOCOLO_PC.md](PROTOCOLO_PC.md). El video sigue saliendo de la microSD: la PC lo importa y lo deja en la carpeta de la salida.
- **Pendiente:** no hay protocolo para el polígono (botón *Enviar Polígono a PC* sin función) ni para el historial completo (lotes y misiones).

## 14. Limitaciones conocidas

- **Copias de seguridad:** el manifiesto tiene `allowBackup="true"` y las reglas de respaldo son las de plantilla (vacías); Android puede subir la base y los CSV a la copia en la nube del usuario.
- **Archivos huérfanos:** al borrar una misión, los CSV de sus vuelos se quedan en el almacenamiento.
- **Borrado de lotes:** hoy la app no tiene forma de borrar un lote. Si se añade, hay que borrar también los `parametros_vuelo` de sus misiones (la clave foránea va de la misión a los parámetros, así que el borrado en cascada no los alcanza) y los CSV de sus vuelos.
- **`fotosEstimadas`** guarda cuadros en video y fotos en foto; el nombre ya no es exacto.
- **Lotes cóncavos:** el planificador solo avisa; la ruta puede salirse del lote y la verificación la bloquea si se aleja más de 10 m.
- **Descarga al teléfono:** requiere Android 10 o más (MediaStore). Si la descarga queda incompleta se borra el archivo a medias; no hay reanudación.
- **Marcas del video:** `t_video_s` parte de la orden de grabar, no del primer cuadro real; puede diferir unas décimas de segundo. Las marcas son por distancia en línea recta entre la última marca y la posición actual, no por distancia recorrida sobre la ruta.
- **Registros privados:** el build `debug` no es depurable (`isDebuggable = false`), así que no se pueden leer los CSV con `adb run-as`.
