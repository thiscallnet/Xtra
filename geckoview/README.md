# Xtra GeckoView

This directory contains the reproducible configuration for Xtra's experimental
custom GeckoView artifacts. It is intentionally separate from the normal Xtra
Android build: Gecko is compiled only when this directory changes or when the
workflow is dispatched manually.

The source revision is pinned to the Gecko 150 revision used by Xtra's current
published GeckoView dependency. `SOURCE_LOCK.json` repeats the repository and
revision and records the patch digest:

```text
2853d763c9486ebd79a1d93396ffadc53c91f187
```

Mozilla's revision metadata maps this changeset to Git commit
`a077abc2b0f43ed7cc59a8bfcd873e683500d23a` in the official
`mozilla-firefox/firefox` mirror. CI downloads that exact commit archive and
checks its pinned SHA-256 before extraction. The compressed source archive and
its provenance marker are cached by archive digest, so a cold run downloads a
snapshot instead of cloning the full Mercurial history; the cache and Mozilla
toolchain state are saved even when a later configure, build, or verifier step
fails.

Profiles:

- `safe`: GeckoView Lite, HLS disabled, one content-service declaration, and
  the low-risk production removals. WebRTC remains enabled.
- `nowebrtc`: the safe profile plus `--disable-webrtc`. This is experimental
  and must pass Twitch login, 2FA/challenges, passkeys, remembered sessions,
  logout, and account switching before it can be considered for release.

The safe profile applies `patches/0001-disable-android-hls.patch` to the
pinned Gecko checkout. The patch is included in the artifact configuration
digest and is applied idempotently; an unrelated source-tree modification is
rejected.

The production artifact is a universal ARM AAR containing `arm64-v8a` and
`armeabi-v7a`. The two native builds run in parallel; the fat AAR is then
assembled by Mozilla's `android-fat-aar-artifact` target without recompiling
Gecko.

## Local build

Run this on a Linux build host after installing the prerequisites documented by
Mozilla for GeckoView/Firefox for Android:

```bash
./geckoview/scripts/build-aar.sh arm64-v8a safe
```

The script downloads and SHA-256 verifies the pinned source snapshot when
needed, applies the pinned patch, bootstraps the build tools, configures the
selected profile, verifies the generated substitutions, builds Gecko, archives
the GeckoView AAR, and writes a manifest beside the artifact. No full
Mercurial history is required for the normal archive-based build.
It never changes Xtra's ordinary Gradle dependency. Mozilla's build state is
kept under `MOZBUILD_STATE_PATH` (defaulting to `~/.mozbuild`) and is cached in
CI. Compiled ABI archives are cached separately from verification scripts, so
workflow and verifier fixes do not force another native Gecko compilation.

The no-WebRTC experiment is explicit:

```bash
./geckoview/scripts/build-aar.sh arm64-v8a nowebrtc
```

Do not publish either profile merely because it compiles. The output must be
installed into a test Xtra build and exercised through the real Twitch login
flow first.

The universal packaging job consumes the two skinny Maven archives:

```bash
./geckoview/scripts/build-fat-aar.sh \
  artifacts/xtra-geckoview-arm64-v8a-*.target.maven.zip \
  artifacts/xtra-geckoview-armeabi-v7a-*.target.maven.zip
```

The fat job uses Mozilla's official multi-architecture packaging path. It does
not compile Gecko a second time.

## Release policy

Artifacts are immutable and named with their profile, Gecko revision, and
configuration digest. The published production artifact is one universal ARM
AAR. Xtra's normal Android build consumes it by passing
`-PxtraGeckoViewAar=/path/to/the.aar`; without that property, local/debug work
continues to use the stock Maven GeckoView dependency.

Pull requests run only the fast recipe validation job. Native builds are
manual (`smoke-arm64` for quick development checks or `release-both` for the
full ARM64 + ARMv7 qualification) and run automatically on matching pushes to
`master`. The full path packages the universal AAR and builds one Xtra
`app-release.apk` against it. To publish the AAR, manually dispatch
`release-both` with a new `xtra-gv-*` value in `release_tag`.

Only the `safe` profile is published by that step. The `nowebrtc` profile is
available only through the explicit manual experiment input and is never part
of the production fat AAR.

After publishing an immutable GeckoView release, configure the repository
variable `XTRA_GECKOVIEW_RELEASE_TAG` to that exact tag. The normal Android
release workflow then downloads the one AAR, verifies its source revision and
SHA-256 manifest, and builds the unchanged `app-release.apk` updater contract.

Mozilla's source and GeckoView build remain under the licenses in that source
tree. This repository contains configuration and scripts only.
