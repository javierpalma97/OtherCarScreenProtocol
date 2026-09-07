<#
.SYNOPSIS
  Instala todo lo necesario para compilar y probar OtherCarScreenProtocol (OCSP) en Windows.
  Idempotente: lo ya instalado se omite. No requiere admin salvo HypervisorPlatform.
#>
$ErrorActionPreference = "Stop"
$AXSDK = "C:\AndroidSdk"
$AXTOOLS = "$env:LOCALAPPDATA\axlink"

function Have($cmd) { $null -ne (Get-Command $cmd -ErrorAction SilentlyContinue) }
function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# 1. Python + pytest -------------------------------------------------------
Step "Python"
if (-not (Have python)) {
    Write-Host "Instalando Python via winget..."
    winget install --id Python.Python.3.12 -e --silent --accept-source-agreements --accept-package-agreements
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("Path", "User")
}
python --version
python -m pip install --upgrade pip
python -m pip install -r "$PSScriptRoot\server-source\requirements.txt"

# 2. FFmpeg ---------------------------------------------------------------
Step "FFmpeg"
if (-not (Have ffmpeg)) {
    Write-Host "Instalando FFmpeg via winget..."
    winget install --id Gyan.FFmpeg -e --silent --accept-source-agreements --accept-package-agreements
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("Path", "User")
}
ffmpeg -version 2>&1 | Select-Object -First 1

# 3. JDK 17 (portable, sin admin) ------------------------------------------
Step "JDK 17"
$jdk = "$AXTOOLS\jdk17\jdk-17.0.11+9"
if (-not (Test-Path "$jdk\bin\java.exe")) {
    Write-Host "Descargando Temurin 17 portable..."
    New-Item -ItemType Directory -Path $AXTOOLS -Force | Out-Null
    $zip = "$env:TEMP\jdk17.zip"
    Invoke-WebRequest -Uri "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.11_9.zip" -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath "$AXTOOLS\jdk17" -Force
}
& "$jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1
$env:JAVA_HOME = $jdk

# 4. Android SDK ------------------------------------------------------------
Step "Android SDK en $AXSDK"
New-Item -ItemType Directory -Path "$AXSDK\cmdline-tools" -Force | Out-Null
if (-not (Test-Path "$AXSDK\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host "Descargando cmdline-tools..."
    $zip = "$env:TEMP\cmdtools.zip"
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath "$AXSDK\cmdline-tools\latest" -Force
    Move-Item -Path "$AXSDK\cmdline-tools\latest\cmdline-tools\*" -Destination "$AXSDK\cmdline-tools\latest\" -Force
    Remove-Item -LiteralPath "$AXSDK\cmdline-tools\latest\cmdline-tools" -Force
}
$env:ANDROID_HOME = $AXSDK
$env:ANDROID_SDK_ROOT = $AXSDK
$sdkm = "$AXSDK\cmdline-tools\latest\bin\sdkmanager.bat"
1..60 | ForEach-Object { "y" } | & $sdkm "platforms;android-34" "build-tools;34.0.0" "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64" | Select-Object -Last 2

# 5. AVD --------------------------------------------------------------------
Step "AVD ax_car"
$avd = "$env:USERPROFILE\.android\avd\ax_car.avd"
if (-not (Test-Path $avd)) {
    echo "no" | & "$AXSDK\cmdline-tools\latest\bin\avdmanager.bat" create avd -n ax_car -k "system-images;android-34;google_apis;x86_64" -d "pixel_7" --force
} else {
    Write-Host "AVD ax_car ya existe."
}

Write-Host ""
Write-Host "LISTO. Variables para esta sesion:" -ForegroundColor Green
Write-Host "  `$env:JAVA_HOME=`"$jdk`""
Write-Host "  `$env:ANDROID_HOME=`"$AXSDK`""
Write-Host ""
Write-Host "Siguientes pasos (ver README.md):" -ForegroundColor Green
Write-Host "  1. Servidor:  cd server-source; python server.py --mode mock --audio pcm"
Write-Host "  2. APK:       cd client-android; .\gradlew.bat assembleDebug"
Write-Host "  3. Emulador:  emulator -avd ax_car (o con -no-window)"
Write-Host "  4. Instalar:  adb install -r app\build\outputs\apk\debug\app-debug.apk"
Write-Host "  5. Redirigir UDP: adb -s emulator-XXXX emu redir add udp:50002:50002 (y 50004)"
Write-Host "  6. En la app: IP manual 10.0.2.2 -> Conectar"
Write-Host ""
Write-Host "NOTA: el emulador x86_64 exige aceleracion. Como admin una vez:" -ForegroundColor Yellow
Write-Host "  Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All (+ reinicio)"
