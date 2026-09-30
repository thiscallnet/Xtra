# VAFT playlist capture

Capture is opt-in and available only in the debug app. It records the original
playlist text before Media3 adaptation, plus VAFT probe bodies, request URI,
HTTP status for probes, wall time, and elapsed time. It survives process restarts.
The private cache retains at most 512 records and 32 MiB. File writing runs on a
bounded background queue; playback never waits for disk writes.

Enable or disable using an explicitly selected device:

```text
adb -s SERIAL shell am broadcast -n com.github.andreyasadchy.xtra.debug/com.github.andreyasadchy.xtra.debug.VaftPlaylistCaptureReceiver --ez enabled true
adb -s SERIAL shell am broadcast -n com.github.andreyasadchy.xtra.debug/com.github.andreyasadchy.xtra.debug.VaftPlaylistCaptureReceiver --ez enabled false
python tools/capture-vaft-playlists.py --serial SERIAL --output PRIVATE_DIRECTORY
```

The records contain exact playback URLs and their tokens. Keep exports private;
publish summaries with URLs removed. The export tool prints only record counts.

To replay a captured VAFT range with local decoded media, supply one captured
JSON record to `vaft-fixture-server.py --vaft-range-template RECORD`. The fixture
retains the range attributes, shifts its dates into the local live window, and
uses local media. Trigger it with `/control?primary_vaft=true`; return to the
primary with `/control?primary_vaft=false`. The debug fixture receiver affects
only the reserved fixture channel. Disable that receiver when verification ends.
