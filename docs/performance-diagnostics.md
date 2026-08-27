# Xtra performance diagnostics

These commands collect diagnostics without clearing app data or changing the logged-in account. Replace the package with the build being tested.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.github.andreyasadchy.xtra"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
```

Start logcat and reset frame statistics before reproducing the issue:

```powershell
& $adb logcat -c
& $adb shell dumpsys gfxinfo $pkg reset
& $adb logcat -v threadtime | Tee-Object "xtra-logcat-$stamp.txt"
```

In another terminal, record a system trace, then reproduce the same navigation, fling, refresh, and chat actions while it runs:

```powershell
& $adb shell perfetto `
  -o /data/misc/perfetto-traces/xtra.perfetto-trace `
  -t 40s `
  sched freq idle am wm gfx view binder_driver input dalvik res memory
```

After the trace completes:

```powershell
& $adb pull /data/misc/perfetto-traces/xtra.perfetto-trace ".\xtra-$stamp.perfetto-trace"
& $adb shell dumpsys gfxinfo $pkg framestats | Out-File "xtra-gfxinfo-$stamp.txt"
& $adb shell dumpsys meminfo $pkg | Out-File "xtra-meminfo-$stamp.txt"
& $adb shell dumpsys activity | Out-File "xtra-activity-$stamp.txt"
& $adb shell dumpsys input | Out-File "xtra-input-$stamp.txt"
```

During a live freeze, capture the process ID and Java stacks without killing the app:

```powershell
$pid = (& $adb shell pidof $pkg).Trim()
& $adb shell kill -3 $pid
Start-Sleep -Seconds 1
& $adb shell kill -3 $pid
& $adb logcat -d -v threadtime | Out-File "xtra-freeze-threads-$stamp.txt"
& $adb shell top -H -p $pid -n 1 | Out-File "xtra-top-$stamp.txt"
```

If Android reports an actual ANR, collect the bug report before restarting the app:

```powershell
& $adb bugreport ".\xtra-bugreport-$stamp.zip"
```

The perf diagnostic APK is built with `assemblePerf`, uses the application ID suffix `.perf`, enables shell profiling, StrictMode logging, main-stall thresholds, and frame metrics. It is intended for a separate diagnostic install; do not install it over the normal logged-in package.
