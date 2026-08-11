# Xtra for Twitch

<p align="center">
  <img src="https://github.com/thiscallnet/Xtra/raw/master/app/src/main/ic_launcher-web.png" width="96" alt="Xtra logo">
</p>

<p align="center">
  A community-maintained fork of <a href="https://github.com/crackededed/Xtra">Xtra</a>,<br>
  focused on practical fixes and Twitch features that are useful in everyday viewing.
</p>

## What this fork adds

Compared with the upstream project, this fork currently includes:

- More reliable live notifications with persisted follow IDs, Helix fallback, and an optional on-device real-time monitoring mode.
- More reliable playback across background playback, picture-in-picture, and app task removal, with automatic recovery for interrupted live streams.
- Channel Points in chat: balances, custom icons, watch streaks and streak sharing, text-input rewards, voting, and redemption, including web GQL login support.
- Poll and prediction activity: active results appear in chat and the Channel Points dialog; the latest observed poll is retained per channel for the next visit. Hermes supplies arbitrary-channel live poll updates, while Helix snapshots are used only for the authenticated broadcaster's own channel.
- Searchable emote pickers limited to emotes belonging to the active channel.
- A reorganized settings experience with search, categorized pages, and clearer playback and background-playback controls.
- Fork-hosted releases and update links for existing Xtra installs.
- A clearer main screen with compact live cards
- Accessibility improvements across cards, menus, chat actions, and dynamic player controls

For arbitrary channels, Twitch's official API does not provide a viewer snapshot of a poll that Xtra never observed live. If a poll starts and finishes while Xtra is closed, its result cannot be reconstructed; once observed, it remains available from Xtra's local cache.

### Live notification timing

Live notifications have three modes in Settings:

- **Battery saving** uses periodic Android WorkManager checks. It uses the least battery, but Android may delay alerts.
- **Fast** keeps a Twitch EventSub connection and performs frequent batched Helix reconciliation while Xtra's process is alive. It has no persistent notification; Android may pause monitoring after Xtra is backgrounded.
- **Persistent real-time** runs the same monitoring through an Android foreground service for the strongest on-device background reliability. Android requires a quiet ongoing notification while it is enabled. Deep Doze can still pause network access; the live-notification settings include an optional battery-optimization shortcut for users who need the strongest idle behavior.

WorkManager reconciliation remains scheduled as a slower fallback. Fast mode is best-effort while the process is alive; Persistent mode is the opt-in choice when continuous background monitoring matters most.

### Update checks

When enabled, Xtra checks for a new GitHub release whenever the app is opened or resumed, subject to the configured minimum interval. The default interval is one day. Available release notes are shown in the update dialog and in Settings, with a red settings indicator while an update is waiting. A release can be ignored locally; ignoring one version does not hide later releases.

### VAFT alternate streams

The optional **VAFT alternate streams** setting manages alternate Twitch playback sources when needed for playback continuity, then automatically returns to your preferred source when appropriate. It preserves the underlying source handoff and recovery behavior without changing your preferred playback settings.

<p align="center">
  <img src="./docs/images/channel-points-rewards.jpg" width="320" alt="Channel Points rewards and watch streaks">
  <img src="./docs/images/channel-points-navigation.jpg" width="620" alt="Channel Points balance in the navigation bar">
</p>

## Download

You can find released APKs [here](https://github.com/thiscallnet/Xtra/releases/latest).

[![Join the Xtra community on Discord](https://img.shields.io/badge/Join%20the%20community-Discord-5865F2?logo=discord&logoColor=white)](https://discord.gg/2cKy8DNgPX)

## License
Xtra is licensed under the [GNU Affero General Public License v3.0](LICENSE).
