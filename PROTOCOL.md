# AXLink v1 — Protocolo de visor tipo CarPlay sobre WiFi

> Estado: testing. Sin cifrado ni autenticación (reservado para v2).
> Todo el texto de control viaja en UTF-8. Números en JSON son decimales.

## 1. Puertos

| Canal    | Transporte | Puerto(s)      | Notas                              |
|----------|------------|----------------|------------------------------------|
| Descubrimiento | UDP broadcast | 50000      | Beacons del servidor cada 1 s      |
| Control  | TCP        | 50001          | JSON por líneas (`\n`), bidireccional |
| Vídeo    | RTP/UDP    | 50002 + RTCP 50003 | H.264, payload type 96         |
| Audio    | RTP/UDP    | 50004 + RTCP 50005 | Opus 48 kHz stereo, payload type 97 |

## 2. Descubrimiento (UDP broadcast, puerto 50000)

El servidor emite 1 beacon/segundo (una línea JSON por datagrama):

```json
{"type":"hello","name":"AX-Source","control":50001,"video":50002,"audio":50004,"ver":1}
```

El cliente que no reciba beacons en 3 s debe ofrecer entrada manual de IP.
Campos obligatorios: `type`, `control`, `video`, `audio`, `ver`.
Si `ver > 1`, el cliente v1 debe ignorar el beacon (compatibilidad futura).

## 3. Sesión de control (TCP 50001, JSON por líneas)

Cada mensaje = 1 objeto JSON + `\n`. Máximo 64 KiB por línea.
El TCP debe usar `TCP_NODELAY` (baja latencia de toques).

### 3.1 Cliente → servidor: `hello`

```json
{"type":"hello","client":"AX-Viewer","codecs":["h264","opus"],"resolutions":[[800,480],[1280,720]]}
```

### 3.2 Servidor → cliente: `session`

```json
{"type":"session","id":"abc123","video":{"codec":"h264","w":1280,"h":720,"fps":30,"pt":96},"audio":{"codec":"opus","rate":48000,"pt":97}}
```

Tras `session`, el servidor empieza a emitir RTP a la IP de origen del TCP.

### 3.3 Eventos táctiles (cliente → servidor)

Coordenadas **normalizadas 0–1** (independientes de la resolución):

```json
{"type":"touch","action":"tap","x":0.42,"y":0.61,"t":123456}
{"type":"touch","action":"swipe","x0":0.1,"y0":0.5,"x1":0.8,"y1":0.5,"t":123500}
{"type":"touch","action":"long_press","x":0.5,"y":0.5,"ms":600,"t":123600}
```

`action` ∈ `tap|swipe|long_press`. `t` = ms de reloj monótono del cliente.
Campos fuera de rango (x/y ∉ [0,1]) → el servidor responde `error` y descarta.

### 3.4 Teclas (cliente → servidor)

```json
{"type":"key","key":"volume_up","t":123700}
```

`key` ∈ `volume_up|volume_down|home|back`.

### 3.5 Latencia (ambas direcciones)

```json
{"type":"ping","t":123800}
{"type":"pong","t":123800}
```

El `pong` devuelve el mismo `t`. RTT = ahora − t.

### 3.6 Errores (servidor → cliente)

```json
{"type":"error","code":"bad_coords","detail":"x out of range"}
```

`code` ∈ `bad_json|unknown_type|bad_coords|bad_key|too_big`.

### 3.7 Cierre

Cualquiera puede cerrar el TCP en cualquier momento. El cliente debe
reintentar: beacons → `hello` → `session` (reconexión automática).

## 4. RTP (vídeo y audio)

Cabecera RTP estándar de 12 bytes (RFC 3550):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       sequence number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           synchronization source (SSRC) identifier            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- `V=2`, `P=0`, `X=0`, `CC=0`. `M` (marker) = 1 en el último paquete de cada
  fotograma de vídeo; en audio = 1 en cada paquete (unidades Opus completas).
- Vídeo: `PT=96`, reloj 90 kHz. NAL units H.264; si una NAL > MTU se
  fragmenta en FU-A (RFC 6184). El cliente reordena por `sequence number`
  (ventana de 64) y descarta duplicados.
- Audio: `PT=97`, reloj 48 kHz, 1 paquete = 20 ms (960 muestras stereo).
- Audio PCM (modo testing): `PT=98`, L16 s16be stereo 48 kHz (payload = PCM
  directo, sin decodificador). El `session` indica `codec: "pcm"`.
- SSRC fijo por sesión y canal (elegido al azar al iniciar `session`).
- RTCP Sender Reports opcionales en v1 (requeridos solo para medir deriva
  A/V en fase de integración).

## 5. Diagrama de secuencia

```
Cliente                  Servidor (UDP 50000)
   |  <--- hello (beacon 1/s) ---  |
   |                               |
   |  ===== TCP 50001 =====        |
   | --- hello (caps) ----------> |
   | <-------- session ---------- |
   |                               |
   |  <<<< RTP vídeo 50002 <<<<   |
   |  <<<< RTP audio 50004 <<<<   |
   | --- touch/key/ping -------> |
   | <------- pong -------------- |
   |                               |
   X  (corte)                      |
   |  <--- hello (beacon) -------  |  (reconexión: vuelta a empezar)
```

## 6. Reserva v2 (no implementar)

- `{"type":"auth",...}` en el handshake + TLS en el TCP de control.
- SRTP (RFC 3711) con claves negociadas en `session`.
