# Team page audit follow-up

This pass continues the [original UI audit](ui-performance-audit.md) after [PR #540](https://github.com/thiscallnet/Xtra/pull/540). It starts from current master and keeps the follow-up fixes in one focused PR.

## Scope

Review pages with limited earlier runtime coverage. This batch fixes the Team page. Channel About, panels and schedule code were also inspected, and the real channel About-to-Team path was exercised. This is not a runtime verdict on every channel, panel, schedule, player or multiview state.

Preserve settings/defaults, accounts, paging, member ordering, tag/category routing, live/offline row semantics and media/chat behavior. No schema/version-code changes, authenticated mutations, new product features, unit tests or delegated agents. Use the normal debug app, preserve emulator state and inspect recordings.

## Findings and changes

| Finding | Root cause and fix | Behavior protected |
| --- | --- | --- |
| Team details fail silently | Team metadata and paged members use separate requests. A metadata exception becomes null with no visible state; refreshing members cannot recover it. Publish loading/ready/failed state and show a small details-specific error with native Retry. Retry calls only the existing details loader. | Successful members remain available. Successful team data remains cached for the ViewModel lifetime. Optional fields and the existing GraphQL request stay unchanged. |
| Member names and stream titles are clipped | Team members inflate the compact stream template while leaving its thumbnail gone. Text is vertically constrained to that zero-height thumbnail; viewer/uptime overlays also target it. Use a dedicated Team member card with natural-height text, a 48dp avatar action and readable live metadata. | Live rows still open the stream; offline rows and avatars still open the channel. Title/name ellipsizing, tag filtering, category navigation and current display preferences remain. |
| Collapsed navigation overlaps the status bar | The collapsing child owns the top inset and can scroll it away with the toolbar. Keep navigation outside the scrolling header, with its own stable top inset. This also avoids collapsing scrims obscuring controls that still have valid layout bounds. The details status row stays beneath navigation. | Back, settings, overflow and Retry remain reachable on compact screens. The header can still collapse and the member list can scroll. |
| Refreshed member metadata stays stale | The diff ignores stream identity, login/name/avatar, category IDs, tags and stream start time. Compare the fields actually rendered or used by callbacks, excluding unused preview generations and profile-fetch timestamps. | Unchanged snapshots still avoid a rebind. Changed metadata reaches both the visible row and its navigation callback. |

The Team-specific row has 11 base XML views instead of 16. It omits five unused thumbnail/overlay controls. This is a structural reduction, not a measured CPU, memory or frame-time percentage.

## Verification

The original failure was reproduced with a temporary exception at the metadata-query boundary while the real member list succeeded. The original row screenshot showed clipped names/titles. Temporary hooks were used only in explicit diagnostic builds, with source diffs and matching installed APK hashes retained locally.

- Native Search to channel profile, About and Team exercised the actual destination. Repeated metadata failure kept real members visible. Keyboard Tab/Enter on Retry then loaded the real team details; returning from background retained them.
- Native offline-row tap and long press opened the channel. Tag selection opened the filtered feed and Back returned to Team.
- Real long stream titles and live/offline members were inspected in portrait, small portrait, landscape and 1.3 text. The last member remained reachable by scrolling. Compact failure/Retry was also exercised.
- A temporary feed used the production Paging adapter with unchanged member IDs, viewer count, game and title. Changed name/avatar/tags/start time appeared in the row. A native offline click passed the current name to channel navigation. Returning restored the real feed, then owned fixture images were removed.

No live-stream row was activated, so these checks did not send chat, collect points, claim rewards, follow channels or perform other authenticated writes. Live playback dispatch has source coverage; its listener is preserved. TalkBack speech/gestures, TV traversal, successful empty-team data and production CPU measurements remain unverified.

Clean-build artifact records and inspected screenshot/recording evidence are kept outside the repository. Diagnostic hooks must be absent from the submitted source. The new error string is translated in every supported locale; resource XML and keys are checked before submission.

## Behavior risk

Risk is 2/5 overall, where 1 is low and 5 is high. Recovery and diff fixes restore expected behavior. The layout changes need the most attention: the toolbar reserves space in small landscape, avatars have a 48dp target, and metadata now contributes its real height. The list remains scrollable. Existing settings and defaults are preserved.

## Further audit candidates

About/schedule refresh lifetime and repeated Team-header image/Markdown work on background return need focused evidence before changing them. Other low-coverage screen families from the original inventory remain follow-up work. No speedup is claimed from source inspection alone.
