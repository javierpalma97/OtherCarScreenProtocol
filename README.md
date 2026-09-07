# OtherCarScreenProtocol (OCSP)

Visor tipo CarPlay / Android Auto sobre WiFi. Arquitectura cliente-servidor:

- **Servidor** (`server-source/`, Python + FFmpeg): genera contenido (mock
  o archivo) y lo emite por RTP. Recibe toques y teclas por TCP.
- **Cliente** (`client-android/`, Kotlin): APK que muestra el vídeo,
  reproduce el audio y devuelve eventos táctiles.

Protocolo: [PROTOCOL.md](PROTOCOL.md) (AXLink v1).

## Estructura

```
carplay-view / OCSP
├── server-source/      # server.py + axproto/ + tests/
├── client-android/     # app Android (ax.viewer) + módulo axproto
├── .github/workflows/  # CI: pytest + APK debug como artefacto
├── setup.ps1 / setup.sh
└── PROTOCOL.md
```

## Instalación

Windows: `.\setup.ps1` · Linux/macOS: `./setup.sh`. Instalan Python+pytest,
FFmpeg, JDK 17, Android SDK (plataforma 34, build-tools, emulator) y crean el
AVD `ax_car`. El emulador x86_64 exige aceleración (como admin una vez):
`Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All`.

## Prueba end-to-end

1. Servidor: `cd server-source && python server.py --mode mock --audio pcm`
   (mock 1280x720 + tono; `--audio opus` también soportado).
2. APK: `cd client-android && ./gradlew assembleDebug`
   (o descárgala del artefacto `AXViewer-debug-APK` del workflow).
3. Emulador: `emulator -avd ax_car` (con `-no-window` si es headless).
4. Instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
5. Redirigir UDP del host al invitado (¡imprescindible, si no, negro!):
   `adb -s emulator-XXXX emu redir add udp:50002:50002` (y `50004`).
6. En la app: si no hay beacons, IP manual `10.0.2.2` → Conectar.
7. Verás las barras de test + overlay `1280x720@30 · Nms`. Toca la pantalla:
   el servidor lo registra y lo pinta en el vídeo.

Con móvil físico: misma WiFi, IP manual del PC, sin redir.

## Puertos

50000/UDP beacons · 50001/TCP control · 50002/UDP vídeo (+50003 RTCP) ·
50004/UDP audio (+50005 RTCP). Ábrelos en el firewall de Windows si el
móvil/emulador no conecta.

## Problemas conocidos

- **Vídeo negro con sesión OK**: falta el `redir` UDP (punto 5).
- **Decodificador sin salida**: el servidor antepone SPS/PPS a cada IDR;
  si cambias el encoder, mantén esa invariante.
- **Opus mudo en algunos emuladores**: usa `--audio pcm` (L16 directo).
- **Teclado que no se cierra** en el campo IP: tecla ESC (`adb shell input keyevent 111`).

## Estado

Testing (sesiones 1–6 verificadas: protocolo, servidor mock, cliente,
integración en emulador con vídeo+audio+toques). Sin cifrado (reservado v2).
