# Runtime verification

Use this flow when a task needs proof from the Android app. It prevents a release APK, stale process, or different emulator from being mistaken for the build under review.

1. Record the exact commit and build the debug variant from the feature branch:

   ```powershell
   $expectedHead = (& git rev-parse HEAD).Trim()
   .\gradlew.bat :app:assembleDebug
   ```

2. Install it on the explicitly chosen serial without clearing app data:

   ```powershell
   & $adb -s <serial> install -r 'app\build\outputs\apk\debug\app-debug.apk'
   ```

3. Verify the artifact, source commit, and installed target before launching:

   ```powershell
   .\tools\verify-xtra-debug-target.ps1 -Serial <serial> -ExpectedHead $expectedHead
   ```

   The command requires a clean worktree and checks the exact commit, local APK hash, installed APK hash, package, version, AVD, and installed artifact. It fails when the APK package, version, or installed APK hash does not match. Pass `-AllowDirtyWorktree` only when intentionally testing uncommitted changes.

4. Launch the expected package if it is not already running:

   ```powershell
   & $adb -s <serial> shell monkey -p com.github.andreyasadchy.xtra.debug 1
   ```

5. For player work, open the stream, video, or clip under test through the real user path before checking the backend. Merely launching the browsing screen does not create an active player.

6. Verify the running target and current backend:

   ```powershell
   .\tools\verify-xtra-debug-target.ps1 -Serial <serial> -ExpectedHead $expectedHead -RequireRunning -RequirePlaybackBackend
   ```

   The debug build answers an on-demand diagnostic from the current process. It reports `none` when no player is attached, rather than reusing a historical log line. Add `-ExpectedPlaybackBackend media3`, `legacy_exoplayer`, or `android_media_player` when the task requires one specific implementation.

7. Exercise the changed user path. For player work, cover portrait and landscape, a small phone viewport, rotation, long metadata, live, behind-live, paused, replay, captions, and unavailable states. For chat work, cover picker groups, typing, send gating, GIFs, badges, event rows, replies, tap, and long press.

Do not send authenticated chat, reward, or claim actions unless the maintainer approved the exact action. A build or install never grants that permission. Preserve the logged-in debug app and leave it installed.
