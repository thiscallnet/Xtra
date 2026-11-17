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

For startup/restart checks, `/control?token_delay=3` delays only playback-token
responses and `/control?token_fail=true` makes those responses fail with HTTP
503. Reset with `/control?token_delay=0&token_fail=false`. These controls allow
reproducing a refresh during an in-flight URL lookup and confirming that a failed
lookup is reported, then a later HUD restart can resolve and play normally.

For live-edge recovery checks, `/control?primary_date_offset=-30` makes the
primary playlist's program-date-time mapping appear 30 seconds behind wall
clock at the next segment while sequence numbers and media timestamps continue
unchanged. It intentionally omits a discontinuity marker at that boundary to
reproduce a reconnect with a stale HLS clock. Reset with
`/control?primary_date_offset=0`. To reproduce a stale clock from stream start,
start the fixture with `--primary-date-offset=-30`.
