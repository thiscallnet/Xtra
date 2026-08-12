# Android emulator smoke testing

Use the shared lightweight AVD `xtra-api35` for manual Android checks. It is an
API 35 Google APIs x86_64 device configured as a 1280x800 tablet in landscape,
with 4 GB RAM, 6 virtual CPU cores, and host GPU acceleration. The host has
WHPX enabled.

The Android SDK is normally at `$env:LOCALAPPDATA\Android\Sdk`. Start the AVD
and wait for Android to finish booting:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
Start-Process "$sdk\emulator\emulator.exe" -ArgumentList @(
    '-avd', 'xtra-api35', '-no-snapshot', '-no-boot-anim', '-gpu', 'host',
    '-netdelay', 'none', '-netspeed', 'full'
)
& $adb wait-for-device
do { Start-Sleep -Seconds 2; $booted = (& $adb shell getprop sys.boot_completed).Trim() } while ($booted -ne '1')
```

Build, install, and launch the debug app:

```powershell
.\gradlew.bat :app:assembleDebug
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb shell monkey -p com.github.andreyasadchy.xtra.debug 1
```

Stop it with `& $adb emu kill`. If host GPU rendering is unavailable, replace
`'-gpu', 'host'` with `'-gpu', 'swiftshader_indirect'`.
