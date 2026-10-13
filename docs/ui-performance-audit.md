# UI and performance audit

Source review dated 2026-10-13, based on master after [PR #539](https://github.com/thiscallnet/Xtra/pull/539).

The best first fixes are duplicate Saved-screen subscriptions, repeated rebuilding of reward and update views, incomplete settings search, and the hidden player uptime timer. These have concrete source paths. Their CPU cost has not been measured.

## Scope and evidence

Requested goal: review all discoverable app pages for usability, bugs, performance costs, and useful additions, then fix findings with evidence and implement the four approved additions. All changes belong to one PR. The target runtime is the normal Android debug app.

Preserve existing settings and defaults, authenticated data, playback and chat semantics, picker groups, and migration behavior. Do not change the version code or perform authenticated mutations while verifying UI fixes.

Acceptance checks for this audit:

- Inventory both navigation graphs, pager children, activities, player/chat screens, preference pages, and dialogs.
- Trace high-priority findings through their callers and shared code. Include source locations, a focused fix direction, and a check for each.
- Separate source-confirmed behavior from unmeasured performance effects and product ideas.
- Check document links and the final diff against the audit scope.

The initial inventory was a source audit. T3 device access was disabled, so subsequent verification uses a visible, exclusively claimed emulator through pinned adb commands. Findings remain source observations unless the progress table records exercised behavior. Evidence from an individual fix does not establish verification of a later combined APK. Wrap-up scope is consolidation and review of the existing changes; further audit work is deferred.

## Implementation progress

| Finding | Change and exercised behavior | Remaining limits |
| --- | --- | --- |
| F01 | Saved initialization is once per view. Ten background/return cycles produced one initialization per page, versus eleven before. Compact layouts and offline playback return exercised. | Authenticated bookmark refresh and active download progress still need checks. |
| F02 | Search traverses visible nested categories. Caption engine and chat background results opened their actual settings; background change was restored. | Platform-specific controls need their respective devices. |
| F03 | Live uptime work stops with hidden controls. Same-device hidden interval: fifteen updates before, zero after. Show/re-enable, minimized, PiP and replay paths exercised. | Counts establish removed work, not a CPU percentage. |
| F04 | Unchanged reward catalogs, streak metadata and points icons retain their views and image requests across countdown emissions. A temporary local host using the production dialog compared child identities before/after, then exercised balance, reward metadata, availability, icon and streak changes, empty/unavailable states, keyboard focus, compact layouts, 1.3 text and reopening. The fixture was removed before the clean APK exercised the real chat-to-rewards path, scrolling, offline retention and reopening. Original automatic collection was temporarily disabled and restored after leaving chat. | No authenticated redeem, vote, bet, share or chat action was performed. Active poll/countdown state coverage used the local host; the real channel had no active poll or prediction. Poll choices and prediction outcomes remain a separate timer-work observation. Counts establish retained views, not CPU percentages. |
| F05 | Update notes are rebound only when their content changes. Real download progress completed with no repeat notes binds; history expansion and rotation exercised. | No downloaded update was installed. |
| F06 | Hidden reward strips create no children or image requests. Same campaign collapse built one hidden child before, zero after; expansion, image viewer, filtering and offline retention exercised. | Available campaigns had only one benefit; multiple-benefit rows lack runtime coverage. |
| F07 | Four adapters use background diffs and lightweight state updates. Existing visible full rebinds fell from eleven to zero for notification unread and whisper selection changes, twelve to zero for people selection, and three to zero for Drop progress. Images, Markdown and reward children stay intact; bound timestamps still refresh. Local production-adapter checks exercised metadata, removal/reordering, empty/restore, campaign loading, claim-button state, keyboard focus, compact layouts and a real minute boundary in combined search. Ten clicks before diff commit used current models or ignored removed/stale/ineligible items. The fixture was removed before clean APK checks of cached notifications/avatar navigation, Drops tabs/filter/images/scroll/offline retention, and real whisper inbox/people search. | Counts concern existing visible rows; newly prefetched rows may bind normally. These are work counts, not CPU percentages. Claims, item read/dismiss callbacks and thread selection used local callbacks only. Real notification checks use offline cache because online inbox entry automatically requests a viewed receipt. No real whisper conversation or search result was opened. |
| F08 / viewer filter | One bounded RecyclerView replaces four lists inside a scroll container. The local filter covers every returned username and known display names, with an explicit returned-name count. The same 1,401-name production-dialog fixture reduced attached rows from 301 to 11 and scroll-observer registrations from eleven to one after ten returns. Filtering the last username, profile batching, optional profile failure, loading/error/empty/no-match, Retry/Clear, rotation, compact portrait/landscape and 1.3 text were exercised. The fixture was removed before clean Media3 checks of menu entry, real filtering, scrolling, offline retention, profile navigation and player minimization. | Attached-row/observer counts show avoided work, not CPU percentages. Twitch may return fewer usernames than the reported chat count; the filter does not claim full audience coverage. Controlled state failures used a local provider. |
| F09 | All search and Discover show loading, failed or stale status beside each affected section, with Retry. Valid results remain visible; successful empty responses stay empty. A temporary provider exercised partial/all failure, target-only Retry, retained results, empty success, cancellation and current-item clicks through the production Search pipeline. Unchanged visible neighbors had zero full binds during target Retry. A local production category-coordinator check preserved joined success and rate-limit outcomes with one shared operation. Clean APK checks exercised real Search and all four Discover shelves through offline refresh, Retry and reconnect, including natural cache expiry. Small portrait/landscape, 1.3 text, 48dp targets, scrolling, rotation, hardware Tab/Enter and native accessibility Retry were inspected. | Controlled failures and coordinator counts used temporary local providers removed before the clean APK. TalkBack speech/gestures, TV traversal and device CPU percentages remain unverified. |
| F10 | Speed editing remains a draft until the list's OK. Child Cancel/Back, parent Cancel, invalid/duplicate/new speeds and final persistence exercised. | F11 subsequently exercised touch dragging and explicit moves through the same draft. |
| F11 | Reorder handles open Move up/down; rows expose native accessibility move actions. Speed and navigation dialogs exercised touch dragging, short taps, long presses, keyboard moves, boundaries, Cancel, Save/reopen and inverse restoration. Native accessibility Down/Up ran against the production navigation rows. The navigation dialog now scrolls around its unchanged preview so small portrait/landscape can reach the list. | TV remote traversal and TalkBack speech/gestures remain unverified. Small viewports initially show the preview and require scrolling to the rows. |
| F12 | Left/right and confirmation keys use the chart's existing bucket-selection callback. Real seven-bucket and one-bucket views exercised selection, both boundaries, focus retention, vertical exit, details and touch. Native accessibility forward/back actions changed and restored the selected bucket. Compact portrait/landscape, rotation and 1.3 text were inspected. | TalkBack speech/gestures and RTL input were not exercised. Empty-bucket dispatch has source coverage; an empty day with one bucket was exercised. |
| F13 | Channel avatars are named accessibility actions with 48dp targets; decorative avatars have no click listener. Real channel avatars opened their profile by keyboard and native accessibility click; body text anchors stayed fixed. Scrolling, recycled rows, small portrait/landscape and large text were inspected. | TalkBack gesture injection was inconsistent, so its traversal remains unverified. Rows were already marked read and had no dismiss controls; item read/dismiss actions were not used. Online inbox entry also invokes a separate viewed-receipt request, discovered during F07 checks; subsequent checks use offline cache. |
| F14 | Bookmark sorting runs on a worker. Date/expiration keys are calculated once per row with one snapshot time. All six orders matched an independent 5,000-row fixture, including missing/malformed dates, expired archives and mixed retention roles. Expiration key evaluations fell from 51,568/51,740 to 5,000; creation keys from 85,764/84,834 to 5,000. The clean APK exercised real sorting, scrolling, compact layouts, 1.3 text, overflow cancellation, offline content and restored empty state. Fixture rows were removed and the original bookmark digest stayed unchanged. | These are debug workload counts, not production CPU or jank percentages. Saved-date sorting retains direct ID comparisons without allocating date-key rows. |
| F15 | Cached search metadata is separate from current switch/slider values; row diffing includes displayed contents. Timestamp toggles refresh on return, and changing opacity from 88 to 50 makes its row match a search for “50.” Values and temporary settings were restored. | Developer visibility invalidation has source coverage; device checks remain. |
| F16 | Invalid input stays editable with a localized inline range error. Badge blank, overflow, range boundaries, saving and cancellation were exercised. Batch overflow was rejected, zero accepted, and original values restored. Short landscape exercised fullscreen keyboard editing and returning to the dialog. | Watch-streak input was disabled without Android notification permission; its range has source coverage only. Older Android keyboard handling still needs device checks. |
| Compact Drops | Short landscape pins title/tabs and exposes the existing filter through a local action. The combined debug APK exercised filtering/clearing, campaign expansion, image viewing/return, empty results, rotation, 1.3× text and offline retention. Initial card visibility grew from about 22 to 150 pixels at 640×360 dp. | Phone-only behavior; TV is excluded. This is a viewport measurement, not a CPU result. |

| Whisper Jump | The production thread renderer and merge path were exercised with local incoming/history fixtures. Scroll-away shows Jump; incoming messages retain the same message and offset while showing New messages. Jump returns to latest, newest incoming follows there, and older pagination keeps the anchor and draft. Native keyboard and accessibility actions, rotation, 360x640 and 640x360 dp at 1.3 text, actual expanded layout at 1200x640 dp, and German labels passed. Short landscape reuses the same header, toolbar and Jump in a compact composition. | Fixtures prevented read receipts and real sends. Real authenticated thread entry remains unverified because it automatically marks the conversation read. The temporary diagnostic hooks were removed before the clean build. |
| Whisper avatars | Theme-colored fallback icons stay visible for missing and failed images. Production inbox and people adapters exercised null, failed local-file and colored-image fixtures. Photo colors and circle cropping remain intact. Recycled rows cancel their previous avatar request. | Thread fallback was exercised through the local conversation. No real conversation was opened. |
| Inbox toolbar crash | View recreation exposed a weak-map conversion crash when a toolbar was collected. Direct iteration removes the unsafe size-based list conversion and allocation. Thirty-two temporary toolbars with repeated garbage collection and expanded-pane recreation passed. | This is crash reproduction and removed allocation work, not a CPU percentage. |
| Bulk Saved selection | Bookmarks, completed/failed downloads and Clips support explicit selection, named checkable cards, Clear, Select loaded, Back/Cancel and confirmed subset removal. Native touch, accessibility and D-pad checks exercised the production adapters. Selection survives activity and locale recreation. Loading the second download page retained the original 30 selected items. Keep files preserved the chosen file byte-for-byte; unchecked removal deleted only the chosen file. Checkbox toggles did no full row rebinds in the measured steady-state intervals. | Test media and records were owned local fixtures. Active downloads are excluded from batch selection; their existing individual cancellation path is preserved. The spinner host is a legacy navigation destination and has no current root shortcut; its real destination, native dropdown switching and restored selection were exercised. |
| Saved deletion failures | File/document failure keeps the record and restores its status/progress for retry. Shared media, chat, playlist segments and initialization segments survive until the last reference is removed. Local playlist checks covered relative/file URIs, different segment durations, unrelated sibling files, missing files, Keep files and two ViewModels deleting shared references. A 1,000-segment removal made three progress writes. Busy state is checked after acquiring the shared deletion lock. | No user media was deleted. Document-provider failure used a local provider through the real Android DocumentsContract. The write count does not establish a CPU percentage. |
| Compact Clips | When fixed preview controls would consume the clip viewport, the same header becomes a RecyclerView header. Rows remain reachable, scrolling returns to the original controls, and an offscreen preview pauses. Normal portrait restores the fixed header. Narrow rows use a smaller icon area and named 48dp share/delete actions. German and Arabic selection and recreation were exercised at 320dp width and 1.3 text. | Long filenames deliberately ellipsize in rows; their full name remains in the card's accessibility description and preview heading. |
| Clip refresh failures | A null MediaStore cursor is a failed read, rather than an empty library. Valid clips remain visible with the shared stale-state label and Retry. | Failure injection is limited to the local query boundary; no MediaStore catalog was cleared. |

The first six completed fixes above were exercised individually before consolidation. Later fixes were exercised in subsequent combined APKs. All sixteen findings and all four approved additions have implementation and manual evidence. The additions are Whisper Jump to latest, bulk Saved selection, the viewer filter and section failure/retry labels. The idle update page now uses a neutral Updates heading, exercised through Settings search in portrait and large-text landscape. The legacy Saved dropdown targets the visible page after switching and recreation; Filters exposes no selection action. Compact Drops header starvation is addressed above. Screenshots, recordings and exact artifact/device records are retained as local verification evidence, rather than committed machine-specific files. The twenty-six new UI strings are translated in all sixteen supported locales; resource hygiene checks pass.

## Coverage

The inventory found **23 main navigation destinations and 43 settings destinations**. These are routes, not 66 distinct implementations. Pager children, activities, and dialogs add screens outside those graphs. The UI directory contains 73 files ending in `Fragment.kt` or `Activity.kt`, including shared base classes.

Every navigation destination and screen family below was included in source triage. Review covered routes, lifecycle/collection hooks, event handlers, layouts, and shared state/rendering code. Detailed tracing concentrated on the findings below; this is not a line-by-line correctness review of every playback, network, or account operation.

| Screen family | Pages and states inspected in code | Result / follow-up |
| --- | --- | --- |
| App shell | Main activity, root navigation, retained destinations, mini player, TV focus | Keep retained-page semantics; inspect the Saved lifecycle issue F01. |
| Browse | Category grid, top streams, tabs, sort/tag/language dialogs, grid sizing | Shared Paging and viewport controllers already bound much work to visibility. Device checks still needed for compact grids and long tags. |
| Discover | Featured, recommended, trending, categories, loading and failed states | Section failures become generic empty shelves when other sections succeed. F09 now retains cached shelves and shows per-section status/Retry. |
| Following overview | Live, recommendations, continue watching, upcoming, recently offline, expected soon | Cached recommendation labeling and scoped collectors already exist. Preserve these. |
| Following lists | Categories, live streams, videos, channels, pager and spinner variants | Child data collectors generally use `RESUMED`; sort/control bindings also need repeat-entry checks. |
| Category detail | Streams, videos, clips, category follow/header, pager and spinner variants | Uses shared Paging. Check sort/filter sheets, long metadata, empty and failed results on device. |
| Channel detail | Live/header, suggested content, videos, clips, chat, About, panels, schedule | Inspect shared state behavior and panel interactions on device; no confirmed geometry finding in this review. |
| Team | Banner, description expansion, owner, member grid, share | Needs short landscape and large-font checks; no confirmed layout defect from source alone. |
| Search | Landing/history, All, channels, categories, streams, videos, direct user lookup, Drops filters | F09 partial failures. Existing debounce, cancellation, and history Undo should remain. |
| Saved | Bookmarks, downloads, saved filters, local clips, pager/spinner variants, import and deletion dialogs | F01 and F14. Clips already has loading/error/retry and deletion confirmation. |
| Statistics | Overview, range selectors, chart, channel/category lists and detail | F12 keyboard selection; chart draw allocations are a lower-priority profiling target. |
| Notifications | Cached/empty/error lists, read/dismiss controls, native navigation | F07 and F13. Bulk read has a confirmation and request guard. |
| Whispers | Thread list, local/remote people search, compact/expanded detail, history pagination, composer | F07. Load failures are shown as composer errors even when loading history failed; a dedicated Retry/reconnect state would be clearer. |
| Drops | Inventory, campaigns, social badge, search, progress, tracking, campaign expansion, filters, image viewer | F06 and F07. Keep stale progress visible with stale claim actions disabled. |
| Player | Live, rewind, replay/offline, HUD, lock/unlock, captions, mini player, PiP, gestures, seek | F03. Preserve HUD geometry and the fixed timeline scrub target. No runtime verdict on buffering, clipping, captions, or PiP. |
| Player dialogs | Quality, speed, settings, volume, sleep timer, viewer list, games, video information | F08. Existing video-info refresh is lifecycle scoped. |
| Clip creation | Local preview, live/VOD ranges, presets, file name, storage/size, export/share | Inspected loading, failure, cancellation, and player release paths. No export run or timing measurement. |
| Chat | Legacy/v2 renderer, message/reply/user actions, badges, translation, reward/poll/prediction dialogs, typing/autocomplete | F04. V2 already compiles rows off the main thread, supports append updates, and gates animation by visibility. |
| Emote picker | Twitch, third party, emoji, favorites, recents, compact groups, favorite reordering | Preserve catalog failure/stale-data handling and all picker groups. No authenticated send or favorite mutation performed. |
| Multiview | Stream slots, selection/manual lookup, quality/audio policy, combined chat/filter chips | Existing combined-chat submission coalescing and playback recovery deserve preservation. Device checks needed for slot focus and layout. |
| Account and login | Profile/settings forms, category lookup, authenticated browser, TV pointer | Inspected state collection, debounce, browser teardown, validation, and action wiring. No account mutation or login performed. |
| Settings home and preferences | All graph destinations and all preference XML files | F02, F10, F11, F15, F16. Shared icon/card drawing already reuses drawing objects. |
| Settings previews and editor | Theme/background, chat appearance/moderation, swipe preview, navigation previews, production HUD editor | HUD editor uses the production layout and local Media3 player; pauses/releases it. Preserve that implementation. |
| Updates and diagnostics | Update page/detail sheet, progress, release history, install permissions, diagnostics filters/copy/share | F05. Diagnostics is already a diffed list with scoped collection and redaction. |
| Retired bubble entry | `ChatBubbleActivity` | Compatibility entry closes and finishes; not an active user page. |

Settings destinations include home, general, app, account, notifications, prediction and Drops live updates, updates, theme, browsing, playback, live captions, system media controls, player controls, HUD editor, clip capture, downloads, debug, diagnostics, search, language, backup, About, display compatibility, browsing information/tabs/history, seek, gestures, swipe controls, player information, chat appearance/usernames/emotes/7TV/features/history/translation/visibility, live downloads, proxy, and developer options. Some destinations share one fragment.

Dialog/sheet triage also covered sorting for streams/videos/categories/bookmarks/followed channels, tag and language selection, downloads/storage, message/reply/image actions, points/rewards/polls/predictions, Drops filters/stream rewards/images, multiview selection, color and numeric preferences, tab/speed customization, and updater details. Dialogs created inside activities/fragments were included at their call sites.

## Ranked findings

Priority reflects impact and confidence in the source path, not a measured speedup. P1 should be fixed first. P2 deserves a focused follow-up. F07 and F14 are workload-dependent costs; confirm their importance with a trace.

### F01 · P1 · Saved pages accumulate subscriptions on resume

[BaseNetworkFragment.kt:111](../app/src/main/java/com/github/andreyasadchy/xtra/ui/common/BaseNetworkFragment.kt#L111) calls `initialize()` on every resume when network checking is disabled. Downloads, Filters, and Bookmarks disable it. Their initializers launch new view-lifetime collectors; previous registrations survive stop/start until the view is destroyed. Downloads and Filters also call `initializeAdapter()` again, adding load-state collectors and layout listeners.

For Downloads/Filters, five resumes of the same view register five data collectors and five load-state collectors. Lifecycle cancellation suspends this accumulation while stopped; it does not remove the outer registrations. Repeated submissions, state work, and layout callbacks can therefore grow with ordinary use.

Fix: initialize subscriptions/listeners once per view for both online and offline pages. Keep refresh-on-return behavior separate, and reset the guard on every view destruction. Do not globally remove `initialize()` calls without checking the offline-player startup path.

Check: open Saved Downloads and Filters, background/return ten times without recreating the view, then switch retained tabs. Instrument collector/listener registrations and require one active registration of each type. Verify downloads still update and Bookmarks still refresh.

### F02 · P2 · Settings search omits nested controls

[SettingsSearchFragment.kt:133](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/SettingsSearchFragment.kt#L133) iterates immediate children and discards categories. AndroidX `PreferenceGroup.forEach` does not recurse. An XML inventory found **45 keyed descendants inside categories in indexed resources**: 8 chat appearance, 4 chat features, 13 chat layout, 15 captions, and 5 app background. These include phone/TV-specific entries, so 45 is not the number visible on one device.

Some unlisted resources initially looked like additional missing pages, but their controls are already present in indexed resources: 7TV, chat history, live downloads, and usernames. Do not add duplicate entries for these. The retired chat-bubble resource should not be exposed just to fill the inventory.

Fix: use one screen registry and recursive preference traversal with platform/developer visibility rules. Skip preview-only/decorative rows, deduplicate shared keys, and preserve the result's navigation/highlight target.

Check: search actual localized titles for caption engine, chat background, phone/TV overlay dimensions, and mention highlights. Every applicable control should appear and open the correct page/row. Keep existing 7TV, recent-chat, and live-download results working.

### F03 · P2 · The live uptime ticker runs behind hidden controls

[Media3PlayerFragment.kt:3776](../app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt#L3776) updates uptime every second while started. [HUD visibility handling](../app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt#L3330) stops the rewind ticker but leaves uptime running. Disabling uptime makes `updateStreamUptime()` hide the control and return without stopping its ticker. The hidden HUD still receives formatting/text/accessibility updates when uptime is enabled.

Fix: run this UI ticker only while the HUD and uptime control are eligible for display. Refresh immediately on show and re-enable. Preserve stream-state/recovery work separately from display ticking.

Check: use live playback with rewind unavailable or disabled; hide/show the HUD, disable/re-enable uptime, minimize, enter PiP, and return. Count uptime updates while hidden and disabled, then confirm the visible time catches up immediately. CPU savings remain unmeasured.

### F04 · P2 · Poll/prediction countdowns rebuild the reward catalog

[ChannelPointsDialog.kt:213](../app/src/main/java/com/github/andreyasadchy/xtra/ui/chat/ChannelPointsDialog.kt#L213) combines countdown flows into the whole dialog state. Every render calls [renderRewards](../app/src/main/java/com/github/andreyasadchy/xtra/ui/chat/ChannelPointsDialog.kt#L446), which removes and recreates every reward card and starts its image request again. Streak reward rows are also recreated. Poll timing publishes once a second.

Fix: update countdown text independently. Rebind catalog/streak views only when their inputs change, retaining reward identity, availability, balance-dependent state, draft input, and focus. Cached images still incur view/request work, so a cache hit is not sufficient mitigation.

Check: keep the dialog open during an active poll/prediction, scroll to rewards, and inspect allocations, focus, and image requests across countdown ticks. Inspect without voting, betting, sharing a streak, or redeeming anything.

### F05 · P2 · Update progress rebuilds unchanged release notes

[UpdateSettingsFragment.kt:257](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/UpdateSettingsFragment.kt#L257) renders notes/history for each update-state emission. [UpdateNotesBinder.kt:17](../app/src/main/java/com/github/andreyasadchy/xtra/ui/update/UpdateNotesBinder.kt#L17) always removes all child views and creates new text rows. The download monitor samples every 400 ms, so changing download progress can repeatedly rebuild unchanged history. Expanded history increases the work.

Fix: bind progress separately; update note containers only when release/history/expanded-count inputs change. Apply the same check to the details sheet. Consider a recycled history list if long expanded history is measurably expensive.

Check: with several history pages expanded, observe changing download progress using a controlled source/fixture. Require unchanged note-view identities, stable scroll/focus, and responsive Cancel. Do not install an update as part of this check.

### F06 · P2 · Drops creates reward views and requests while hidden

[DropsAdapter.kt:311](../app/src/main/java/com/github/andreyasadchy/xtra/ui/drops/DropsAdapter.kt#L311) removes and reinflates every reward thumbnail before applying visibility. Collapsed campaigns and single-benefit inventory rows pass `visible = false`, but still build children and call `loadDropImage()`.

Fix: skip hidden reward construction and reuse/diff visible children. Dispose obsolete image requests as holders change. Retain expansion state, image click behavior, progress, and claim eligibility.

Check: scroll a campaign catalog with all cards collapsed and inspect child counts/image requests. Expand/collapse cards repeatedly and change one drop's progress. Hidden strips should do no reward-thumbnail work.

### F07 · P2 · Small list changes rebind all visible cards

[Notifications](../app/src/main/java/com/github/andreyasadchy/xtra/ui/notifications/TwitchNotificationsAdapter.kt#L38), [whisper threads](../app/src/main/java/com/github/andreyasadchy/xtra/ui/whispers/WhisperThreadsAdapter.kt#L26), [people results](../app/src/main/java/com/github/andreyasadchy/xtra/ui/whispers/TwitchUsersAdapter.kt#L24), and [Drops](../app/src/main/java/com/github/andreyasadchy/xtra/ui/drops/DropsAdapter.kt#L91) use `notifyDataSetChanged()` when list contents change. Equality checks already suppress identical submissions. A single changed row still rebinds visible siblings, including image requests and notification Markdown.

Fix: diff stable row IDs and use payloads for unread, selection, progress, and action state. Whisper selection changes should update the old/new selection only. Preserve scroll anchoring, Drops expansion, and all row actions.

Check: change one row in each list with an in-memory fixture and count binds. Require unrelated visible rows to retain their image/text views and keyboard/TalkBack focus. Profile large lists before claiming meaningful savings.

### F08 · P2 · Viewer-list growth weakens recycling

[fragment_viewer_list.xml:1](../app/src/main/res/layout/fragment_viewer_list.xml#L1) places four `wrap_content` RecyclerViews inside one NestedScrollView with child nested scrolling disabled. [PlayerViewerListDialog.kt:199](../app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerViewerListDialog.kt#L199) appends/enriches 100 people at a time. Content-height measurement can require laying out many loaded rows rather than just the visible viewport. A scroll observer is registered inside collection, so a lifecycle restart can also add another observer to the surviving view.

Fix: use one vertically scrolling RecyclerView with section headers, preserve lazy profile enrichment, and register one scroll listener per view. Keep the broadcaster/moderator/VIP/viewer grouping.

Check: use hundreds/thousands of local viewer rows, scroll through several batches, stop/resume the dialog, and compare attached holders, allocations, and enrichment requests. Also confirm short/empty/failed lists and profile navigation.

### F09 · P2 · All search hides partial failures

[AllSearchViewModel.kt:82](../app/src/main/java/com/github/andreyasadchy/xtra/ui/search/all/AllSearchViewModel.kt#L82) marks failure only when all four providers fail. Failed categories become empty lists. Even a total remote failure has no error/retry UI if local history/following rows exist, because [AllSearchFragment.kt:190](../app/src/main/java/com/github/andreyasadchy/xtra/ui/search/all/AllSearchFragment.kt#L190) requires `!hasEntries`.

Fix: retain per-category loading/success-empty/failure state, keep valid results, and offer a compact failed-section Retry. A local match must not erase remote failure information. Discover has a related presentation gap: failed sections show empty messaging unless the entire page fails.

Check: simulate one category failing while others return data, all failing with local matches, successful empty results, offline, and recovery. Cached results should remain usable; missing sections must distinguish failure from no matches.

### F10 · P2 · Adding a custom speed commits the unfinished editor

[PlayerButtonSettingsFragment.kt:117](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/PlayerButtonSettingsFragment.kt#L117) calls `saveSpeeds(items)` before opening custom-speed input. Reorder/hide edits therefore persist even if the user presses Cancel in that second dialog, without confirming the original editor.

Fix: retain a draft across both dialogs and return to the list when custom input is canceled. Persist at the explicit final confirmation, or make immediate-save behavior explicit and provide Undo.

Check: reorder/hide a speed, open Add custom speed, cancel/back, and reopen. Also verify invalid input, duplicate speed, successful addition, rotation, and restoration of a hidden option.

### F11 · P2 · Settings reordering is touch-only

[SettingsDragListAdapter.kt:62](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/SettingsDragListAdapter.kt#L62) starts drag from a touch event. Rows provide click handling for visibility/default selection but no move-up/down keyboard or accessibility action. TV row-focus styling does not provide a way to reorder.

Fix: provide explicit Move up/Move down actions usable by remote, keyboard, and TalkBack, alongside dragging. Keep visibility/default guards and immediate preview behavior.

Check: reorder navigation tabs and playback speeds using only D-pad and accessibility actions. Verify first/last row behavior, saved order, focus retention, cancel, and reset.

### F12 · P2 · Statistics chart has no keyboard bucket selection

[ViewingActivityChartView.kt:280](../app/src/main/java/com/github/andreyasadchy/xtra/ui/statistics/ViewingActivityChartView.kt#L280) implements accessibility scrolling, but the custom focusable view has no key handling. `performClick()` does not select a bucket. A TV/keyboard user cannot change the bucket through ordinary left/right controls.

Fix: route left/right and appropriate confirmation behavior through the existing `selectIndex()` path. Preserve vertical page scrolling and the existing touch/TalkBack actions.

Check: navigate ranges and buckets with D-pad, touch, and TalkBack. Check empty/single/multiple buckets, first/last selection, RTL, large text, and opening bucket details.

### F13 · P2 · Notification profile action is hidden from accessibility

[item_twitch_notification.xml:18](../app/src/main/res/layout/item_twitch_notification.xml#L18) sets the avatar to `importantForAccessibility="no"`. [The adapter](../app/src/main/java/com/github/andreyasadchy/xtra/ui/notifications/TwitchNotificationsAdapter.kt#L91) later makes channel avatars clickable/focusable and labels them, but never restores accessibility importance. The distinct profile action remains excluded. Its touch bounds are also only 42dp square.

Fix: expose channel avatars as labeled actions, hide decorative avatars, and provide an anchored 48dp target without stealing adjacent row actions. This does not justify changing movable player-HUD hit regions.

Check: traverse channel and non-channel rows using TalkBack and keyboard. Verify avatar profile navigation, row navigation, read/dismiss buttons, and target separation on a small phone.

### F14 · P2 · Bookmarks repeatedly parses sort keys on the main thread

[BookmarksFragment.kt:155](../app/src/main/java/com/github/andreyasadchy/xtra/ui/saved/bookmarks/BookmarksFragment.kt#L155) sorts in a view-lifecycle collector. Expiration comparators parse timestamps and call `Clock.System.now()` while comparing, repeating the work across comparisons. Large libraries amplify this; F01 can multiply it again.

Fix: capture one time for the snapshot, precompute sort keys once per row, and sort off the main thread. Preserve null ordering, ascending/descending semantics, retention rules, and user overrides. Do not silently change expiration policy.

Check: sort a large synthetic library with malformed/missing dates and mixed retention types in both directions. Compare order with current semantics and record main-thread work. An actual CPU/jank measurement is still needed.

### F15 · P2 · Settings search can show old values after editing

[SettingsSearchFragment.kt:91](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/SettingsSearchFragment.kt#L91) caches switch/slider values on the fragment. Returning from a searched setting reuses that snapshot. The adapter's [content comparison](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/SettingsSearchAdapter.kt#L22) also always returns true, so refreshing rows alone would not fix the displayed values.

Fix: cache searchable metadata separately from current values, refresh value fields on return, and compare actual contents. Reflect developer/platform visibility changes too.

Check: search a switch/slider, change it, go Back, and search Enabled/Disabled or the new numeric value. Search display/filtering must agree with the saved setting.

### F16 · P2 · Invalid numeric settings dismiss without feedback

[MaterialEditTextPreference.kt:40](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/MaterialEditTextPreference.kt#L40) uses the normal auto-dismiss positive button. Several numeric [preference listeners](../app/src/main/java/com/github/andreyasadchy/xtra/ui/settings/SettingsFragment.kt#L1223) reject invalid values by returning false, such as chat badge size and batch interval. Validation runs through `onDialogClosed()` after dismissal, so the setting stays unchanged without an inline explanation. Custom-speed input already shows a better pattern.

Fix: validate before dismissing and show a localized field error with the accepted range. Keep the valid draft and keyboard in place; Cancel must preserve the original value.

Check: submit blank, invalid, overflow, boundary, and valid numeric values. Invalid input should stay editable with an explanation; valid input should persist once. Exercise the same dialogs in short landscape with the keyboard visible.

## Approved additions

The maintainer approved implementing all four after the findings. They originated as product ideas, rather than confirmed defects.

| Idea | Evidence / reason | Behavior to preserve |
| --- | --- | --- |
| Whisper Jump to latest / new-message indicator | The thread renderer explicitly scrolls only for initial load and older-history anchoring. Make returning to the newest messages explicit. | Never pull someone away from older history; preserve pagination anchoring and draft text. |
| Bulk Saved-item selection | Current bookmark/download operations are predominantly per item; Clips has delete-all. Selecting a subset would reduce repeated menus. | Separate removing a bookmark from deleting media; confirm destructive actions or support Undo. |
| Viewer-list name filter | Large lists grow in 100-row batches. A local filter would make a known person easier to find. | Label whether search covers loaded rows or the full fetched catalog; do not invent an unsupported server search. |
| Clearer stale/failed-section labels | Search/Discover can hide failed sections while other content remains useful. Small status/retry controls would make those states understandable. | Retain valid content and successful empty-state semantics; avoid a whole-page error for one failed section. |

## Follow-up order and runtime checks

Implement and verify each focused change within the single audit PR:

1. F01 lifecycle registration, then F03 hidden ticking. They remove work without changing visible defaults.
2. F04/F05/F06 unnecessary rebuilding. Keep view identity and action semantics stable.
3. F02/F09/F15/F16 search and state reliability, then F10 cancellation.
4. F08/F14 large-list work, F07 targeted binds, and F11/F12/F13 accessibility.

For each fix, build the normal debug variant and verify the expected clean commit, worktree/branch, local and installed APK hashes, package/version, AVD/serial, activity/process, and active player backend where relevant with `tools/verify-xtra-debug-target.ps1`. Keep device claims and authenticated state intact. Use the explicit dirty-worktree option only for intentional uncommitted testing.

Exercise the changed entry path, clicks/long presses, setting changes, scrolling, rotation, small portrait/landscape dimensions, large text, and relevant empty/loading/offline/failed/live/paused/replay states. Inspect screenshots and recordings before calling a fix verified. Compilation or a screenshot alone does not establish the behavior.

Compare performance only with the same artifact/package, commit, device, refresh rate, UI state, and workload. Use collector/bind counts, allocations, main-thread trace slices, and frame timing before claiming savings. Debug timings can guide triage but should not be presented as production CPU percentages. Manual verification is used instead of adding unit tests; stop owned build processes and restore deliberate device state when finished.

## Framework references

- [AndroidX preference iteration source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/preference/preference/src/main/java/androidx/preference/PreferenceGroup.kt) confirms immediate-child iteration used in F02.
- [Lifecycle-aware coroutines for Views](https://developer.android.com/topic/libraries/architecture/views/coroutines-views) explains view-lifetime scopes and restartable collection used in F01.
- [RecyclerView](https://developer.android.com/develop/ui/views/layout/recyclerview) describes row reuse relevant to F07/F08.
- [AndroidX edit-text preference dialog source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/preference/preference/src/main/java/androidx/preference/EditTextPreferenceDialogFragmentCompat.java) confirms post-close validation relevant to F16.

No external Twitch/API contract was changed or inferred during this review.

## Review follow-up and behavior risk

The follow-up review found two gaps in the original verification. Download fixtures had no video ID and missed the thumbnail cleanup branch. Activity recreation retained the Clips ViewModel and did not cover process death.

- Cache-image cleanup now accepts only the app-owned thumbnail/profile-image path for that ID and preserves files referenced as media or chat. Keep files was exercised with a nonblank video ID and thumbnail equal to the video path, plus playlist-segment thumbnails and media in the cache directory. Shared media survived the first removal and was deleted after its last reference. Ordinary cached thumbnails remained until their last video reference, then were removed. Android canonical and stored path aliases were included.
- Clips starts with an unloaded catalog distinct from an empty successful result. A native Saved selection survived task restoration in a new app process during a delayed first read and an initial read failure. Retry retained the selected URI; a later successful empty result pruned it. The temporary failure/delay hooks and owned fixtures were removed afterward.

Risk uses a scale of 1, low, to 5, high. It describes regression exposure, not a measured failure probability.

| Change family | Risk | User behavior to protect |
| --- | --- | --- |
| Labels, validation feedback, named accessibility actions | 1/5 | Existing actions, accepted values and Cancel semantics. |
| Lifecycle, diff/payload and view-reuse savings | 2/5 | Updates must still reach visible rows and actions must use current models. Manual counts show avoided work, not CPU percentages. |
| Viewer filtering, section Retry, Whisper Jump and compact layouts | 3/5 | Filtering scope, reading position, drafts, stale content and access to original controls. These change stateful UI paths. |
| Bulk Saved selection and file deletion | 3/5 | Exact selected subset, Keep files, shared media, active-download exclusion and failures that retain records. This deserves the most regression attention because deletion is irreversible. |
| The two review corrections | 2/5 | They narrow unsafe cleanup and preserve selection during unloaded/failed reads. No setting, default, schema or version-code change. |

Overall the PR is useful, with moderate regression exposure because it touches several stateful screens. The performance and accessibility fixes are worth retaining; the whole PR should not be described as risk-free. Authenticated side effects, TalkBack/TV coverage and the other per-finding limits above remain outside the exercised evidence.
