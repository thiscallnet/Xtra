# Agent instructions

## Commit identity

Commits in this repository must use the maintainer's GitHub identity:

- Name: `thiscallnet`
- Email: `55761090+thiscallnet@users.noreply.github.com`

Do not use `Xtra Contributors`, `xtra@users.noreply.github.com`, or another shared identity for commits.

## Xtra-specific rules

### Android verification

- Use the debug build for normal manual verification: `:app:assembleDebug`, package `com.github.andreyasadchy.xtra.debug`, and `app\build\outputs\apk\debug\app-debug.apk`.
- Do not substitute the release or perf package unless the task explicitly asks for it.
- Record `git rev-parse HEAD` before building and pass it as `-ExpectedHead` to `tools\verify-xtra-debug-target.ps1`; the verifier rejects dirty worktrees unless `-AllowDirtyWorktree` is explicit.
- Verify the exact branch, commit, local APK hash, installed APK hash, package, version, AVD serial, foreground activity, process, and current player backend before reporting a runtime result. For player work, open the player first, then use `-RequireRunning -RequirePlaybackBackend`; pass `-ExpectedPlaybackBackend` when a specific backend is required.
- Keep the authenticated debug app's data intact. Do not clear its data, uninstall it, log out, or send authenticated chat or reward actions without explicit approval for the exact action.
- Use the target verification script in `tools\verify-xtra-debug-target.ps1` after installing a debug APK.

### Player HUD contract

- The settings preview uses the production `PlayerHudLayout` hierarchy over a local Media3 player. Do not replace it with a fake control mock or a second geometry implementation.
- Runtime and preview must use the same `HudLayoutEngine`, default policy, safe-area handling, and state semantics.
- The default layout must preserve the legacy action coverage while fitting compact phone viewports. Test portrait, landscape, small phone sizes, and long metadata without clipping or unexplained gaps.
- The replay timeline is fixed player chrome. It spans the player boundary, is not a configurable HUD element, and keeps its deliberate fixed 48dp scrub target for touch accessibility. Keep that target anchored to the timeline and do not let it activate neighboring controls.
- A movable control's hit region must match its visible bounds. Deliberate accessibility targets, including the timeline scrub target and interaction-unlock safety target, are allowed only when they stay anchored to that control and do not create broad neighboring hit areas.
- Verify live, behind-live, paused, replay, caption, and unavailable states through the real player path.

### Chat and migration contract

- A chat-v2 or presentation migration must preserve the legacy picker groups, favorites, recents, personal emotes, badges, GIFs, event layouts, message interactions, replies, typing, and send semantics unless the task explicitly changes one of them.
- Treat transient provider or network failures as failures, not as an empty catalog. Keep last-known-good data when it is still valid.
- Validate Twitch and dependency contracts from current schemas, captured requests, or authoritative documentation before changing parsers or request semantics.

### Scope

- Keep fixes focused. Preserve existing user-facing settings and defaults when the request is for an internal backup, fallback, or performance change.
- For UI changes, inspect screenshots or recordings before reporting success. Check for truncation, clipping, overlap, flicker, alignment, unexpected spacing, and controls that look present but do not work.
