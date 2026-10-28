# Protocolo teléfono → PC

Contrato entre la app del dron y la app de escritorio (proyecto AgroScan-Desktop). Si se cambia un lado, se cambia aquí y en el otro.
Hay dos canales, los dos sobre el Wi-Fi `AgroScan` de la PC y sin internet:

| Canal | Qué lleva | Teléfono | PC |
|---|---|---|---|
| **Telemetría en vivo** — UDP 8765 | Posición, batería, estado y ruta, varias veces por segundo. Si se pierde un paquete no importa | `network/TelemetryStream`, `network/TelemetryPacket` | `features/telemetry/TelemetryReceiver` |
| **Registros de vuelo** — HTTP (TCP) 8766 | El registro de cada salida y sus eventos con las **marcas del video**. Cada envío se confirma | `network/FlightLogSync` | `features/flightlog/FlightLogServer` |

Las secciones que siguen hasta "Registros de vuelo" son de la telemetría en vivo.

## Transporte
- **UDP**, del teléfono a la PC, puerto **8765**. El teléfono envía; la PC escucha **solo en su IP de la zona móvil** (`192.168.137.1`) y solo mientras la Red Local está encendida.
- La PC es la zona móvil de Windows: SSID `AgroScan`, IP fija `192.168.137.1`. El teléfono se une a esa red con `PcLink`
  y ata el socket a ella (`Network.bindSocket`), porque ese Wi-Fi no es la red por defecto del teléfono (no tiene internet).
- Un datagrama = un objeto JSON (UTF-8). Los campos desconocidos se ignoran y un campo sin dato **no se envía** (el receptor lo toma como "sin dato").
- Sin conexión ni confirmación: no hay nada que se pueda colgar. Un paquete perdido lo reemplaza el siguiente.

## Cuándo se envía
| Paquete | Frecuencia | Para qué |
|---|---|---|
| `tel` | cada 0,5 s mientras el teléfono esté conectado a la PC | Datos vivos: posición, batería, estado de la misión |
| `plan` | cada 5 s, desde que el operador inicia una salida (antes de despegar) y mientras dura | La misión: nombre, número de salida, altura, modo, origen y ruta (se repite porque UDP puede perderlo) |
| `lote` | igual que `plan` | El contorno del lote |

Importante: `plan` y `lote` salen **al iniciar la salida** (cuando se pulsa iniciar tras el checklist), no mientras solo se planifica o verifica; antes de eso solo va `tel`.

El teléfono envía desde un hilo propio y nunca espera a la red: un Wi-Fi caído o lento no afecta al vuelo. Si el enlace se cae
**durante una misión**, la app lo vuelve a pedir cada 10 s con los mismos datos (Android puede volver a mostrar su aviso de confirmación).

## Paquete `tel`
```json
{"v":1,"tipo":"tel","seq":1042,"t_ms":1793022406491,"lat":-0.2994723,"lon":-79.4881712,"alt_m":25,"vel_ms":1.77,
 "rumbo":212,"bat":78,"sat":29,"enlace":92,"fase":"FLYING","volando":true,"wp":3,"wp_total":6,"avance":0.412,
 "dist_m":97.2,"grabando":true,"fotos":0,"t_vuelo_s":131}
```
| Campo | Unidad | Significado |
|---|---|---|
| `v` | – | Versión del protocolo (hoy 1) |
| `seq` | – | Contador del teléfono, crece de a 1 |
| `t_ms` | ms | Hora del teléfono (epoch UTC). El receptor descarta paquetes con `t_ms` menor o igual al último |
| `dron` | bool | El teléfono está conectado al dron. Con `false`, batería, altura, satélites… son valores por defecto: la PC debe mostrarlos como "sin dato". Ausente = `true` |
| `lat`, `lon` | grados | Posición del dron. **Ausentes** si no hay GPS |
| `alt_m` | m | Altura sobre el punto de despegue |
| `vel_ms` | m/s | Velocidad |
| `rumbo` | grados | Hacia dónde mira el dron (0 = norte, 90 = este). Ausente si no se conoce |
| `bat` | % | Batería |
| `sat` | – | Satélites GPS |
| `enlace` | 0–100 | Calidad del enlace de control |
| `fase` | texto | Fase de la salida: `IDLE`, `PREPARING`, `TAKING_OFF`, `CLIMBING`, `FLYING`, `PAUSED`, `HOLDING`, `RETURNING`, `FINISHED`… |
| `volando` | bool | El dron está en el aire |
| `wp`, `wp_total` | – | Waypoint objetivo y total de la ruta completa |
| `avance` | 0–1 | Avance de la ruta completa |
| `dist_m` | m | Metros de ruta recorridos |
| `grabando` | bool | La cámara está grabando video |
| `fotos` | – | Fotos tomadas en la salida (0 en misiones de video) |
| `t_vuelo_s` | s | Tiempo de la salida |

## Paquete `plan`
```json
{"v":1,"tipo":"plan","seq":1043,"t_ms":1793022406991,"mision":"Lote Norte - Barrido 1","salida":2,"salidas":3,
 "altura_m":25,"modo":"video","home":[-0.2990,-79.4890],"ruta":[-0.299472,-79.488171,-0.299536,-79.487808]}
```
| Campo | Significado |
|---|---|
| `mision` | Nombre de la misión |
| `salida`, `salidas` | Número de esta salida para el operador y total estimado ("salida 2 de 3") |
| `altura_m` | Altura de la ruta, en metros |
| `modo` | `video` o `foto` |
| `home` | Punto de origen `[lat, lon]`; ausente si aún no está fijado |
| `ruta` | Lista **plana** `[lat1, lon1, lat2, lon2, …]` con los waypoints de la **ruta completa** (6 decimales). Con más de 150 puntos se envía uno de cada k, siempre con el último |

## Paquete `lote`
```json
{"v":1,"tipo":"lote","seq":1044,"t_ms":1793022406991,"perimetro":[-0.2990,-79.4890,-0.2990,-79.4870,-0.3010,-79.4870,-0.3010,-79.4890]}
```
- `perimetro`: lista plana `[lat1, lon1, …]` con los vértices del lote en orden de captura (el último no repite el primero). Con más de 60 puntos se envía uno de cada k.

## Cómo decide la PC que hay señal
Hay señal si llegó un `tel` hace menos de **3 s**. Pasado ese tiempo muestra "Sin señal · último dato hace N s" y conserva la última posición en rojo. La misión en curso solo se muestra si el `plan` tiene menos de 15 s.
La PC arma la estela del dron con las posiciones recibidas y la vacía al empezar una salida nueva.

## Windows y seguridad
**El usuario no escribe ningún comando.** La primera vez que enciende la Red Local en la app de escritorio, Windows muestra una sola
ventana: *"¿Quieres permitir que esta aplicación haga cambios en el dispositivo?"* → **Sí**. Con eso la app crea la regla del Firewall
(`features/redlocal/LocalFirewall` en AgroScan-Desktop). Si la regla ya existe no vuelve a preguntar. Si el usuario responde **No**,
la pantalla de Red Local avisa que el teléfono no podrá enviar datos; basta con apagar y encender la Red Local para volver a intentarlo.

Crea dos reglas con esa única confirmación, lo más estrechas posible: entrada **UDP 8765** (`AgroScan telemetria xxxxxxxx`) y
**TCP 8766** (`AgroScan registros xxxxxxxx`), cada una solo en la IP local `192.168.137.1`, solo desde `192.168.137.0/24` y solo para el
programa de AgroScan (`AgroScan.exe` instalado, o el `java.exe` con que corre en desarrollo). `xxxxxxxx` es una huella de la ruta del
programa: si AgroScan cambia de lugar, las reglas se rehacen.

- Para quitarlas: en PowerShell como administrador, `Get-NetFirewallRule -DisplayName 'AgroScan *' | Remove-NetFirewallRule`.
- Si al abrir la app Windows muestra *"El Firewall de Windows bloqueó algunas características"*, se puede **cancelar**: la regla de arriba
  ya da el permiso justo que hace falta.
- Desactivar el ahorro de energía de la zona móvil (Configuración → Red → Zona con cobertura inalámbrica móvil): con poca actividad Windows la apaga.

**Qué tan expuesto queda el puerto.** Muy poco, por capas:
1. **El puerto solo existe en la red de la zona móvil.** La app lo abre en `192.168.137.1`, no en todas las redes de la PC: desde el Wi-Fi de
   la casa, la oficina o un café el puerto no existe. Con la Red Local apagada no hay ningún puerto abierto.
2. **La red tiene contraseña WPA2** que cambia cada vez que se abre la app de escritorio y solo se muestra en su pantalla: para entrar hay
   que estar viendo la PC.
3. **Firewall:** solo esa red, solo esos puertos, solo AgroScan (reglas de arriba).
4. **La app descarta** cualquier paquete cuyo origen no sea `192.168.137.x` (`LocalHotspot.isLocalNetwork`).
5. **El protocolo no lleva órdenes.** La PC solo lee el texto y lo muestra: no ejecuta nada ni abre archivos. Lo peor que logra un paquete
   falso es mostrar datos falsos o basura (que se descarta al leerla: JSON inválido, demasiado anidado o con tipos equivocados). Un paquete
   que provocara un error inesperado se descarta sin apagar el receptor.
6. La estela está limitada a 20.000 puntos, así que un flujo de paquetes no llena la memoria.

Lo que **no** hay: autenticación por paquete. Quien ya esté dentro de la red con la contraseña podría enviar telemetría falsa. Si algún día se
necesita, se añade un código compartido firmado (HMAC) en cada paquete; no hace falta mientras el protocolo no lleve órdenes.

## Probar sin el teléfono
Con la app de escritorio abierta y la **Red Local encendida** (el puerto solo existe en `192.168.137.1`), desde PowerShell en la misma PC:
```powershell
$u = New-Object System.Net.Sockets.UdpClient
$b = [Text.Encoding]::UTF8.GetBytes('{"v":1,"tipo":"tel","t_ms":1,"lat":-0.2995,"lon":-79.4881,"alt_m":25,"vel_ms":1.8,"bat":80,"sat":20,"fase":"FLYING","volando":true,"t_vuelo_s":60}')
$u.Send($b, $b.Length, "192.168.137.1", 8765) | Out-Null
```
(Enviar a `127.0.0.1` ya no llega: la app no escucha ahí.)

## Registros de vuelo (HTTP, puerto 8766)
El registro de cada salida (`salida-N.csv`, posición cada ~200 ms) y sus eventos (`salida-N.events.jsonl`, con las **marcas** que dicen
qué segundo del video corresponde a cada posición) pasan a la PC, que los junta con el video de la microSD para extraer los cuadros.

**Cuándo se envía**
- **Automático** al cerrar cada salida, si el teléfono está conectado a la PC (`MissionRunner` → `FlightLogSync.sendInBackground`). En
  segundo plano: nunca afecta al vuelo; si falla, queda pendiente.
- **Botón "Enviar vuelos pendientes"** en Red Local → PC, para lo que se voló sin la PC cerca.
- En los dos casos el teléfono primero pregunta qué salidas tiene la PC y envía **solo las que faltan**. No se marca nada en el teléfono:
  un corte a mitad, otra PC o una carpeta borrada en la PC se resuelven solos al volver a enviar.

**Peticiones** (por el Wi-Fi de la PC, `PcLink.network`; Android permite HTTP sin cifrar solo hacia `192.168.137.1`,
ver `res/xml/network_security_config.xml`):

`GET /salidas` → las salidas que la PC ya tiene:
```json
{"salidas":["7c9e6679-7425-40de-944b-e07fc1f90ae7"]}
```

`POST /salidas/<uuid de la salida>` → una salida con sus dos archivos (texto tal cual):
```json
{"v":1,
 "salida":{"uuid":"7c9e6679-…","indice":1,"estado":"COMPLETADA","inicio_ms":1793022406491,"fin_ms":1793023306491,
           "video_inicio_ms":1793022460000,"video_fin_ms":1793023250000},
 "vuelo":{"uuid":"0f8fad5b-…","inicio_ms":1793022000000,"modo":"video"},
 "mision":"Sector 1","lote":"Finca Sánchez",
 "registro_csv":"t_ms,lat,lon,…\n…","eventos_jsonl":"{\"t_ms\":…,\"evento\":\"marca\",…}\n…"}
```
| Campo | Significado |
|---|---|
| `salida.uuid` | UUID de la salida; debe coincidir con el de la dirección |
| `salida.indice` | Orden de la salida en el vuelo, desde 0 (la PC la guarda como `salida-<indice+1>`) |
| `salida.video_inicio_ms`, `video_fin_ms` | Cuándo empezó y terminó de grabar (epoch UTC). La PC los usa para saber a qué salida pertenece cada video de la microSD. `null` en modo foto |
| `vuelo.uuid`, `vuelo.inicio_ms`, `vuelo.modo` | Vuelo al que pertenece (todas sus salidas van a la misma carpeta) |
| `mision`, `lote` | Nombres, para que la carpeta sea legible |
| `registro_csv`, `eventos_jsonl` | Los dos archivos de la salida, sin cambios (formato en `docs/ESTRUCTURA_DE_DATOS.md`, sección 5) |

Respuestas: `200 {"ok":true,"nuevo":true}` (guardada), `200 {"ok":true,"nuevo":false}` (ya estaba: la PC no la toca), o un error
`4xx` con `{"error":"motivo"}`.

**Dónde queda en la PC:** `Documentos\AgroScan\vuelos\<fecha>_<lote>_<misión>_<uuid8>\salida-N\` (la carpeta base se puede cambiar
en Procesamiento). Ahí mismo el importador de la microSD copia los videos de esa salida (`video\`). Ver el README de AgroScan-Desktop.

**Seguridad:** las mismas capas que la telemetría (puerto solo en la Red Local, Firewall estrecho, solo equipos `192.168.137.x`) y
además: lo que llega solo se guarda como texto (nunca se ejecuta), los UUID se validan antes de usarlos en nombres de carpeta, cada
salida se escribe **una sola vez** (un envío falso no pisa uno real) y el cuerpo tiene un máximo de 64 MB.

## Lo que NO hace
- No manda video ni fotos por Wi-Fi: el video sale de la microSD y la PC lo importa (Procesamiento → Importar desde la microSD).
- No lleva órdenes de la PC al dron: el botón "ABORTAR (RTL)" de la PC no hace nada y cualquier orden tendría que diseñarse aparte (seguridad de vuelo).
- La telemetría en vivo no garantiza entrega; lo que importa del vuelo es el registro de vuelo, que sí se confirma (sección anterior).
