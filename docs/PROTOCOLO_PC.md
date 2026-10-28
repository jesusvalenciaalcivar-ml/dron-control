# Protocolo teléfono → PC (telemetría en vivo)

Contrato entre la app del dron (`network/TelemetryStream`, `network/TelemetryPacket`) y la app de escritorio
(`features/telemetry/TelemetryReceiver`, en el proyecto AgroScan-Desktop). Si se cambia un lado, se cambia aquí y en el otro.

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
(`features/telemetry/TelemetryFirewall` en AgroScan-Desktop). Si la regla ya existe no vuelve a preguntar. Si el usuario responde **No**,
la pantalla de Red Local avisa que el teléfono no podrá enviar datos; basta con apagar y encender la Red Local para volver a intentarlo.

La regla que crea es lo más estrecha posible: entrada **UDP 8765**, solo en la IP local `192.168.137.1`, solo desde `192.168.137.0/24`
y solo para el programa de AgroScan (`AgroScan.exe` instalado, o el `java.exe` con que corre en desarrollo). Se llama
`AgroScan telemetria xxxxxxxx`, donde `xxxxxxxx` es una huella de la ruta del programa: si AgroScan cambia de lugar, la regla se rehace.

- Para quitarla: en PowerShell como administrador, `Get-NetFirewallRule -DisplayName 'AgroScan telemetria*' | Remove-NetFirewallRule`.
- Si al abrir la app Windows muestra *"El Firewall de Windows bloqueó algunas características"*, se puede **cancelar**: la regla de arriba
  ya da el permiso justo que hace falta.
- Desactivar el ahorro de energía de la zona móvil (Configuración → Red → Zona con cobertura inalámbrica móvil): con poca actividad Windows la apaga.

**Qué tan expuesto queda el puerto.** Muy poco, por capas:
1. **El puerto solo existe en la red de la zona móvil.** La app lo abre en `192.168.137.1`, no en todas las redes de la PC: desde el Wi-Fi de
   la casa, la oficina o un café el puerto no existe. Con la Red Local apagada no hay ningún puerto abierto.
2. **La red tiene contraseña WPA2** que cambia cada vez que se abre la app de escritorio y solo se muestra en su pantalla: para entrar hay
   que estar viendo la PC.
3. **Firewall:** solo esa red, solo ese puerto, solo AgroScan (regla de arriba).
4. **La app descarta** cualquier paquete cuyo origen no sea `192.168.137.x` (`TelemetryReceiver.isTrustedSource`).
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

## Lo que NO hace
- No manda video ni fotos (el video sale de la microSD).
- No lleva órdenes de la PC al dron: el botón "ABORTAR (RTL)" de la PC no hace nada y cualquier orden tendría que diseñarse aparte (seguridad de vuelo).
- No garantiza entrega: lo que importa del vuelo está en el registro de vuelo del teléfono (`docs/ESTRUCTURA_DE_DATOS.md`).
