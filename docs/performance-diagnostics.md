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
$appPid = (& $adb shell pidof $pkg).Trim()
& $adb shell kill -3 $appPid
Start-Sleep -Seconds 1
& $adb shell kill -3 $appPid
& $adb logcat -d -v threadtime | Out-File "xtra-freeze-threads-$stamp.txt"
& $adb shell top -H -p $appPid -n 1 | Out-File "xtra-top-$stamp.txt"
```

For a stream battery investigation, keep the target stream and device state fixed, then capture
the app-level spans, sampled stacks, system scheduling, and Android power counters together:

```powershell
$device = "emulator-5554"
$appPid = (& $adb -s $device shell pidof $pkg).Trim()
$tracePath = "/data/misc/perfetto-traces/xtra-$stamp.perfetto-trace"

& $adb -s $device logcat -c
& $adb -s $device shell dumpsys gfxinfo $pkg reset
& $adb -s $device shell dumpsys batterystats --reset
& $adb -s $device shell am profile start --sampling 10000 --streaming $appPid `
    "/data/local/tmp/xtra-$stamp.method.trace"
& $adb -s $device shell perfetto -o $tracePath -t 30s `
    sched freq idle am wm gfx view binder_driver input dalvik memory
& $adb -s $device shell am profile stop $appPid

& $adb -s $device pull $tracePath ".\xtra-$stamp.perfetto-trace"
& $adb -s $device pull "/data/local/tmp/xtra-$stamp.method.trace" ".\xtra-$stamp.method.trace"
& $adb -s $device logcat -d -v threadtime | Out-File "xtra-logcat-$stamp.txt"
& $adb -s $device shell dumpsys gfxinfo $pkg framestats | Out-File "xtra-gfxinfo-$stamp.txt"
& $adb -s $device shell dumpsys meminfo $pkg | Out-File "xtra-meminfo-$stamp.txt"
& $adb -s $device shell dumpsys batterystats --charged | Out-File "xtra-batterystats-$stamp.txt"
& $adb -s $device shell top -H -p $appPid -n 1 | Out-File "xtra-top-$stamp.txt"
```

The debug and perf variants export `XtraFrameMetrics`, `XtraMainStall`, `XtraPlaybackPerf`,
`XtraFrameMetrics: chatRender`, and `XtraFrameMetrics: namedSpans` log lines. The named spans
cover the chat parser, event processor, timeline operations, UI snapshots, and Media3 load
callbacks. `chatRender` also groups animated-drawable invalidations by asset key, drawable class,
animation state, view lifecycle state, and last bound message ID. `dumpsys gfxinfo` supplies
Android frame deadlines; the method and Perfetto traces provide full call stacks and scheduler
context for later inspection.

If Android reports an actual ANR, collect the bug report before restarting the app:

```powershell
& $adb bugreport ".\xtra-bugreport-$stamp.zip"
```

The perf diagnostic APK is built with `assemblePerf`, uses the application ID suffix `.perf`, enables shell profiling, StrictMode logging, main-stall thresholds, and frame metrics. It is intended for a separate diagnostic install; do not install it over the normal logged-in package.
