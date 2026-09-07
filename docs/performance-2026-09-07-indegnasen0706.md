# Stream performance capture: indegnasen0706

Date: 2026-09-07

Target: `布団ちゃんと申します (indegnasen0706)`, selected from the in-app Twitch Channels
list. This is the stream shown in the supplied screenshot, not a recommendation-list substitute.

Device: `emulator-5554`, `xtra-api35`, API 35, x86_64, 1280x800 landscape, 120 Hz, Wi-Fi.
The other emulator, `5556`, was left untouched.

Build: debug APK with `PERF_DIAGNOSTICS=true`. The existing debug account state was not cleared
and no account was logged out.

## Result

The primary signal is chat rendering, specifically animated drawable invalidation. A fresh
30-second comparison on the same target produced this result:

| Setting | Frames | Frame worst | IRC messages | Publications | `onDraw` calls | Drawable invalidations | While `isRunning` | Main stalls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Animated emotes on | 2,834 | 95 ms | 133 | 117 | 54,769 | 5,560 | 5,560 | 0 |
| Animated emotes off | 2,917 | 98 ms | 92 | 77 | 85,320 | 9,570 | 0 | 0 |

The chat rate was not identical between the two windows, so this is directional rather than a
final battery claim. The important separation is that the on run generated 5,560 invalidations
from running animated drawables, while the off run generated none. The off run still generated
9,570 invalidations from non-running `Animatable` drawables. That remaining path should be
identified before changing repaint behavior.

## P0 callback fix validation

The callback lifecycle was then changed so an `Animatable` receives a view callback only when it
is allowed to run. Stopping a drawable clears the callback before stopping it, and the view is
invalidated once after the batch so the final static frame remains visible.

Two fresh post-fix windows on the same stream produced this result:

| Setting | Reports | IRC messages | Publications | `onDraw` calls | Drawable invalidations | While `isRunning` | Main stalls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Animated emotes on | 7 | 229 | 203 | 80,097 | 45,302 | 45,302 | 1 |
| Animated emotes off | 8 | 169 | 155 | 103,711 | 0 | 0 | 0 |

The windows had different chat rates and visible row shapes, so the draw totals are not an A/B
power result. The P0 criterion did pass: animations OFF produced neither running nor stopped
animated-drawable invalidations. The ON window's attribution grouped the invalidations under
`coil3.size.ScaleDrawable` assets, with the asset key, animation state, view lifecycle flags, and
last bound message ID included in the diagnostic line.

The emulator cannot provide a valid battery-drain number here. `dumpsys batterystats` reported
`0 mAh` discharged and `0 mAh` computed drain while the virtual battery remained at 100%. Use a
physical phone with a current measurement or a trustworthy battery fuel gauge for the final
power comparison.

## High-chat window

The longer instrumented window ran for 17 five-second frame reports, from 13:09:36 to 13:10:56
emulator time, about 80 seconds total.

| Area | Calls/events | Total time | Maximum | Notes |
| --- | ---: | ---: | ---: | --- |
| `TwitchChatEventParser.fromIrc` | 933 | 267.32 ms | 8.65 ms | IRC event normalization |
| `ChatUtils.parseChatMessage` inside IRC parsing | 143 (confirmation) | 25.37 ms | 9.70 ms | Nested inside `fromIrc`; 35-second confirmation run |
| `ChatEventProcessor` accepted events | 933 | 324.72 ms | 34.69 ms | Includes `store.accept(event)` |
| `ChatTimelineStore` operations | 1,598 | 371.57 ms | 36.47 ms | Serialized timeline operation handling |
| `ChatUiBatcher` snapshots | 665 | 2,998.39 ms | 64.41 ms | End-to-end snapshot request time, including queue wait |
| Media3 `onLoadCompleted` bookkeeping | 85 | 7.07 ms | 0.52 ms | Local diagnostics update only |

Chat renderer counters for that same window:

- 932 row binds and 184,376 `onDraw` calls.
- 669 publications containing 401,400 row entries in total, with a maximum publication size of
  600 messages.
- 932 changed and compiled rows, 400,468 reused rows, and 4 full reuse-index rebuilds.
- 4 asset callbacks and no clip-cache activity.
- 4.414 seconds spent in `compileChatRows`, as reported by `compileCurrentNanos`.

The ordinary parser, processor, and timeline timings are small relative to the frame budget.
Snapshot wait time and drawable/frame churn are the more relevant UI candidates.

## Playback and thread evidence

Media3 remained in a healthy live-playback state:

- HLS over MPEG-TS, low-latency mode enabled.
- HLS target duration 6,000 ms and effective reload target 2,000 ms.
- Twitch prefetch present and active.
- `c2.goldfish.h264.decoder`, hardware accelerated.
- Bandwidth estimate stayed close to 3.9 to 4.0 Mbps.
- The measured media byte counter increased from 67.0 MB to 100.5 MB over about 78 seconds,
  approximately 3.4 Mbps of media data.

A thread snapshot during playback showed the largest app threads as:

| Thread | Reported CPU |
| --- | ---: |
| `RenderThread` | 42.3% |
| app main thread | 34.6% |
| `ExoPlayer:Playback` | 11.5% |
| `MediaCodec_loop` | 7.6% and 3.8% |

These are emulator `top` percentages, not physical-device battery percentages. The sampled
profile itself was dominated by emulator timing and virtualization symbols (`read_hpet` and
`goldfish_pipe_read_write`), so it is not suitable for assigning CPU cost to individual Kotlin
methods. The app-level `Trace` spans and chat counters are the reliable measurements from this
run. Raw method and Perfetto traces were also collected for offline inspection.

## Instrumented functions and Android APIs

| Component | Marker or API |
| --- | --- |
| IRC chat | `TwitchChatEventParser.fromIrc`, nested `ChatUtils.parseChatMessage`, `Trace.beginSection` |
| Chat state | `ChatEventProcessor`, `ChatTimelineStore`, `ChatUiBatcher`, `SystemClock.elapsedRealtimeNanos` |
| Chat rendering | `ChatV2RendererController.compileChatRows`, `ChatMessageTextView.bindInternal`, `onDraw`, `invalidateDrawable` |
| Animated emotes | `Drawable is Animatable`, `Animatable.isRunning`, `Animatable.start/stop`, drawable callbacks |
| Video | Media3 `AnalyticsListener.onLoadCompleted`, `onVideoDecoderInitialized`, `onDroppedVideoFrames`, `onAudioInputFormatChanged` |
| Frame timing | `Window.OnFrameMetricsAvailableListener`, `FrameMetrics.TOTAL_DURATION`, display refresh rate |
| Scheduling | `Handler` main-looper callbacks and the main-stall watchdog |
| System context | `dumpsys gfxinfo`, `dumpsys meminfo`, `dumpsys batterystats`, `perfetto`, `simpleperf` |

## Model-ready interpretation

```yaml
scenario: live_twitch_high_chat
target: indegnasen0706
device: emulator-5554
display_hz: 120
video:
  protocol: HLS
  container: MPEG-TS
  decoder: c2.goldfish.h264.decoder
  hardware_accelerated: true
  estimated_bitrate_mbps: 3.9-4.0
  low_latency: true
  prefetch_active: true
chat:
  high_window_seconds: 80
  irc_messages: 933
  publications: 669
  max_rows_per_publication: 600
  row_compiles: 932
  row_reuses: 400468
  compile_current_seconds: 4.414
  ui_snapshot_seconds: 2.998
animation_ab:
  on_running_invalidations_per_second: 185.3
  off_running_invalidations_per_second: 0
  off_non_running_invalidations_per_second: 319.0
post_fix_ab:
  on_running_invalidations: 45302
  off_running_invalidations: 0
  off_non_running_invalidations: 0
  attribution: [asset_key, drawable_class, is_running, animate_gifs, rendering_active, window_attached, last_message_id]
confidence:
  animated_drawable_pressure: high
  parser_as_primary_drain_source: low
  video_decoder_as_primary_drain_source: low_on_this_emulator
  physical_battery_measurement: unavailable
next_experiment:
  duration_minutes: 10
  compare: [animated_emotes_on, animated_emotes_off]
  measure_on_phone: [battery_current, skin_temperature, frame_jank, cpu_by_thread, network_bytes]
```

Recommended next step: reproduce this A/B on a physical phone for at least ten minutes, while
adding an asset identifier to the animation invalidation counter. The current data supports
prioritizing animated drawable scheduling and repaint behavior ahead of chat parsing or HLS
reload work, but it does not yet justify a production behavior change by itself.
