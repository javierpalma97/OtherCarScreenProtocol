#!/usr/bin/env bash
# Instala lo necesario para OCSP en Debian/Ubuntu o macOS (brew). Idempotente.
set -euo pipefail
AXSDK="$HOME/AndroidSdk"

have() { command -v "$1" >/dev/null 2>&1; }

echo "=== Python ==="
have python3 || { echo "Instala Python 3.10+ y reejecuta."; exit 1; }
python3 -m pip install -r "$(dirname "$0")/server-source/requirements.txt"

echo "=== FFmpeg ==="
have ffmpeg || { echo "Instala FFmpeg (apt install ffmpeg / brew install ffmpeg) y reejecuta."; exit 1; }

echo "=== JDK 17 ==="
have java || { echo "Instala JDK 17+ y reejecuta."; exit 1; }

echo "=== Android SDK en $AXSDK ==="
export ANDROID_HOME="$AXSDK" ANDROID_SDK_ROOT="$AXSDK"
if [ ! -x "$AXSDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$AXSDK/cmdline-tools"
  cd "$AXSDK/cmdline-tools"
  curl -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o cmdtools.zip -d latest
  mv latest/cmdline-tools/* latest/ && rmdir latest/cmdline-tools
  cd - >/dev/null
fi
yes | "$AXSDK/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools" \
  "emulator" "system-images;android-34;google_apis;x86_64" | tail -2

echo "=== AVD ax_car ==="
if [ ! -d "$HOME/.android/avd/ax_car.avd" ]; then
  echo no | "$AXSDK/cmdline-tools/latest/bin/avdmanager" create avd -n ax_car \
    -k "system-images;android-34;google_apis;x86_64" -d "pixel_7" --force
fi

cat <<'EOF'

LISTO. Siguientes pasos (ver README.md):
  1. Servidor:  cd server-source && python3 server.py --mode mock --audio pcm
  2. APK:       cd client-android && ./gradlew assembleDebug
  3. Emulador:  emulator -avd ax_car
  4. Instalar:  adb install -r app/build/outputs/apk/debug/app-debug.apk
  5. Redirigir UDP: adb -s emulator-XXXX emu redir add udp:50002:50002 (y 50004)
  6. En la app: IP manual 10.0.2.2 -> Conectar
EOF
