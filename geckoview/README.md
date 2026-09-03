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

- `safe`: GeckoView Lite, HLS disabled, Gecko's upstream 40-service content
  pool, and the low-risk production removals. Printing remains enabled because the
  pinned Gecko 150 Android build still registers its print-settings component
  when printing is disabled. WebRTC remains enabled.
- `nowebrtc`: the safe profile plus `--disable-webrtc`. Its build-only patch
  deletes GeckoView's single WebRTC-dependent `VideoCaptureTest` source before
  the Android test APK is compiled; it does not restore or replace runtime
  WebRTC functionality. This is experimental and must pass Twitch login,
  2FA/challenges, passkeys,
  remembered sessions, logout, and account switching before it can be
  considered for release.
- `nowebspeech`: the runtime-qualified `nowebrtc` profile plus only
  `--disable-webspeech`. Its build completed, but it saved only about 0.13%
  of the AAR and is not the preferred direction for the auth engine.
- `auth`: an x86_64-only authentication baseline. It uses the safe profile's
  existing reductions and does not remove another browser subsystem. It must
  qualify against real Twitch authentication before any further reduction is
  considered.
- `minimal`: an x86_64-only multi-flag candidate. It combines WebRTC, Web
  Speech, WebDriver/remote protocols, parental controls, and privileged
  zipwriter removal. It is not release-ready and is not the next experiment;
  each reduction must first be tested independently.
- `twitch-auth-radical-r1`: the purpose-built footprint profile, first
  qualified on x86_64.
  It inherits the proven WebRTC removal and adds size-oriented optimization,
  packaging minification, development/profiling cuts, selected product
  component cuts, and bundled browser-product assets. WebGPU and VR/XR remain
  enabled because they are part of Gecko's rendering and IPC dependency graph;
  removing them is isolated to a future experiment. Accessibility remains
  enabled because Android widget sources require its exported implementation.
  The same feature set is available for `arm64-v8a` and `armeabi-v7a` through
  the opt-in R1 release-candidate workflow; those builds are not production
  releases until their artifacts are published and qualified.

The auth profile does not synthesize or hardcode Kasada integrity values.
Twitch's own JavaScript must execute inside Gecko and complete the normal
integrity request during qualification.

The x86_64 auth baseline also validates the generated AAR manifest: it must
contain Gecko's default `tab0` through `tab39` and `isolatedTab0` through
`isolatedTab39` services. A single-content-service AAR is rejected because it
cannot provide a valid baseline for multi-origin authentication pages.

## GitHub experiment builds

The x86_64 experiments are built on a standard GitHub-hosted runner so a
developer workstation does not compile Gecko. Dispatch `Custom GeckoView`
with `experiment=x86_64-auth` first. The job cleans disposable runner tools,
restores only the pinned compressed source archive and Mozilla toolchain state,
and runs the native build with `GECKO_BUILD_JOBS=1`. It does not cache the
expanded Gecko checkout or object directory. Because the final `gkrust` Rust
compile can exceed the runner's physical RAM, the job raises total swap to
12 GiB for this disposable runner and removes that temporary swap before the
job ends. Gecko's Gradle daemon is disabled for the native build so Java
generation does not leave several GiB resident while `rustc` builds `gkrust`.

The full `mach build` runs GeckoView's Gradle AAR and local Maven publication
tasks. After `mach package`, the recipe creates `target.maven.zip` directly
from that Maven directory instead of invoking a second standalone
`archive-geckoview` Gradle build. It then injects the exact AAR into
`app:assembleDebug`, records the AAR/APK/libxul/omni measurements, and uploads
the debug APK plus `measurements.tsv` for one day. Download that APK and use
the local x86_64 AVD for Twitch qualification. `x86_64-nowebrtc`,
`x86_64-nowebspeech`, `x86_64-minimal`, and
`x86_64-twitch-auth-radical-r1` remain manual follow-up experiments; the
radical profile is a separate bundled candidate and none is part of the
production build. VR/XR removal remains a separate follow-up experiment. The
compact AAR, matching Maven archive, and provenance
manifest are cached and uploaded as soon as Gecko packaging succeeds, before
the Xtra Gradle build, so later integration retries do not need to recompile
Gecko.

To build R1 for all currently supported release ABIs, dispatch `Custom
GeckoView` with `experiment=validate` and `scope=r1-release`. This builds
`arm64-v8a`, `armeabi-v7a`, and `x86_64`, then builds one Xtra debug APK
against each exact AAR and verifies that the APK contains the requested
`libxul.so`. The optional `r1_x86_run_id` input may promote an R1 x86_64 run
from the same commit; the workflow rejects a different commit instead of
silently using a stale binary. Omit it for the first multi-ABI run so the
new shared recipe is built consistently for every ABI.

Before that full run, use `scope=r1-x86` with `experiment=validate` to qualify
the x86 toolchain and integration path alone.

For a native crash investigation, dispatch the same x86_64 experiment with
`diagnostic_symbols=true`. This bypasses only the compiled-AAR cache; source,
toolchain, and sccache caches remain enabled. The job preserves the matching
unstripped native ELF files, attempts `mach buildsymbols`, verifies the
unstripped and packaged `libxul.so` Build IDs, and uploads those diagnostics in
a separate short-lived artifact. The normal stripped AAR and APK path remains
unchanged. `scripts/symbolicate-android-crash.sh` accepts that symbol-bearing
`libxul.so` (or its directory) and an Android tombstone/PC list.

To process an existing diagnostic run without compiling Gecko again, dispatch
with `experiment=validate`, `symbolicate_run_id=<run id>`, and the matching
`symbolicate_crash_build_id`. The job downloads the short-lived symbol
artifact, refuses Build ID mismatches, and uploads only `symbolicated-crash.txt`.

All x86_64 experiments share the same v1 sccache namespace so an interrupted
auth build can help a later retry, regardless of the selected profile. The
GitHub Actions sccache backend is enabled only on the default branch, where
GitHub permits cache writes. Stacked PR/feature-branch jobs use a local-only
sccache wrapper with a bounded 4 GiB disk cache and strip GitHub cache
variables at the invocation boundary, avoiding rejected writes. A
successful Mozilla bootstrap writes a source-specific completion marker. CI
uses that marker to save newly bootstrapped toolchain state even if native
compilation fails afterward. An exact restored cache validates the cached NDK,
clang, sccache, Rust, and Cargo before skipping bootstrap. If the cache is
valid but predates the marker, CI repairs the marker; an incomplete cache is
rejected instead of being bootstrapped over.
Gecko 150 builds use the pinned Rust toolchain in `RUST_TOOLCHAIN_VERSION`;
cache keys include that version so a newer incompatible bootstrap cannot be
mistaken for a compatible restored toolchain.
The native build also prints a five-minute RAM and disk heartbeat to the
Actions console. These memory safeguards affect CI resource management only;
they do not remove Gecko browser functionality.

The R1 x86 build selects the pinned Android NDK LLVM compiler explicitly. The
Mozilla Gecko 150 clang bundle used for the other ABIs does not carry the
i686 Android compiler-rt runtime, while the NDK toolchain does. The build runs
a minimal i686 Android link probe before configure and records this selection
in the x86 artifact identity. Gecko's separate WASI compiler remains the
Mozilla clang bundle because the Android NDK does not provide WASI libraries.

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
Native Gecko compilation is capped at two parallel jobs by default so Gecko
does not size its native fan-out from the host's total RAM. Override it
deliberately with `GECKO_BUILD_JOBS`, but keep the cap in place on shared
workstations. This does not cap bootstrap, Gradle packaging, or the Docker VM.
For the local Docker runner, apply a hard limit before the one build session:

```bash
docker update --cpus 4 --memory 24g --memory-swap 24g xtra-gecko-baseline
```

The container limit is the actual RAM/CPU guard; `GECKO_BUILD_JOBS=2` is the
additional native-build guard.

The no-WebRTC experiment is explicit:

```bash
./geckoview/scripts/build-aar.sh arm64-v8a nowebrtc
```

After the auth baseline passes, the first x86_64 reduction uses the same
isolated profile:

```bash
./geckoview/scripts/build-aar.sh x86_64 nowebrtc
```

This is a separate experiment from `x86_64 auth`; it must be measured and
qualified independently before any additional subsystem is removed.

The small Web Speech experiment inherited the runtime-qualified no-WebRTC
profile:

```bash
./geckoview/scripts/build-aar.sh x86_64 nowebspeech
```

It adds only `--disable-webspeech`. Do not use the multi-flag `minimal` profile
as a substitute; every reduction needs its own size and real-login result.

The purpose-built radical footprint experiment is dispatched separately:

```bash
GECKO_BUILD_JOBS=1 ./geckoview/scripts/build-aar.sh x86_64 twitch-auth-radical-r1
```

It does not include `--disable-webspeech`; the standalone build saved only
about 0.13% of the AAR. This radical profile keeps the ordinary DOM,
networking, storage, cryptography, rendering, canvas/WebGL, timing, and browser
environment surfaces intact, and must pass real Twitch authentication before
any of its bundled cuts are retained.

The first aggressive x86_64 candidate is:

```bash
GECKO_BUILD_JOBS=1 ./geckoview/scripts/build-aar.sh x86_64 minimal
```

This candidate must not be built until the untouched auth baseline has passed
Twitch qualification. It is deliberately kept as a separate configuration so
its size and login result cannot replace the baseline row.

Do not publish a profile merely because it compiles. The output must be
installed into a test Xtra build and exercised through the real Twitch login
flow first.

The first auth build is x86_64-only:

```bash
./geckoview/scripts/build-aar.sh x86_64 auth
```

The build writes or updates `geckoview/artifacts/measurements.tsv`. The row key
is the generated artifact name, which includes the ABI, profile, source
revision, and configuration digest. Native builds record the AAR size and its
`libxul.so` and `omni.ja` members, with the login result left as `not-tested`.
A new configuration gets a new row. Re-running an existing artifact is safe,
and an automatic `not-tested` result will not replace an existing qualification
result. Inject the AAR into a debug APK, then update that artifact's row after
exercising Twitch:

```bash
mapfile -t aar_files < <(find geckoview/artifacts -maxdepth 1 -type f \
  -name 'xtra-geckoview-x86_64-auth-*.aar' -print | sort)
[[ "${#aar_files[@]}" -eq 1 ]] || exit 1
aar="${aar_files[0]}"
artifact_id="$(basename "$aar" .aar)"
./gradlew :app:assembleDebug -PxtraGeckoViewAar="$(pwd)/$aar"
./geckoview/scripts/measure-artifacts.sh \
  "$artifact_id" \
  "$aar" \
  app/build/outputs/apk/debug/app-debug.apk \
  passed \
  geckoview/artifacts/measurements.tsv
```

Repeat the measurement with the same artifact identity after each auth
qualification result. Use a new generated artifact identity for every Gecko
configuration experiment so the history remains comparable.

The universal packaging job consumes the two skinny Maven archives:

```bash
./geckoview/scripts/build-fat-aar.sh \
  artifacts/xtra-geckoview-arm64-v8a-*.target.maven.zip \
  artifacts/xtra-geckoview-armeabi-v7a-*.target.maven.zip
```

The fat job uses Mozilla's official multi-architecture packaging path. It does
not compile Gecko a second time.

R1 uses a separate immutable release path. Once its four ABI integrations
pass, dispatch the same workflow on `master` with `experiment=validate`,
`scope=r1-release`, and a new tag such as
`geckoview-twitch-auth-r1-v1`. One release contains the four ABI-specific
AARs, matching Maven archives, per-ABI identity manifests, and one aggregate
manifest. The tag is never moved or reused. Future CI can fetch and verify one
ABI without recompiling Gecko:

```bash
./geckoview/scripts/download-r1-release-aar.sh \
  geckoview-twitch-auth-r1-v1 arm64-v8a .ci/geckoview
./gradlew :app:assembleDebug \
  -PxtraGeckoViewAar="$PWD/.ci/geckoview/xtra-geckoview-twitch-auth-r1-arm64-v8a.aar"
```

The downloader fails closed on missing assets, manifest or SHA-256 mismatch,
source revision mismatch, ABI mismatch, or missing native Gecko libraries. It
is intentionally separate from the normal stock Maven fallback; release CI
must pass an explicit verified AAR.

To preserve a qualified run before its short-lived build artifacts expire,
dispatch with `experiment=validate`, `scope=r1-preserve`, and
`r1_source_run_id=<qualified run>`. This performs no native build: it
re-downloads and verifies the four integration artifacts plus the universal
artifact, then uploads five 90-day artifacts carrying the original qualified
run ID and commit. Promotion can consume those preserved artifacts by also
setting `r1_preserved_run_id=<preservation run>` while keeping
`r1_source_run_id` set to the original qualification run.

## Release policy

Artifacts are immutable and named with their profile, Gecko revision, and
configuration digest. The published production artifact is one universal ARM
AAR. Xtra's normal Android build consumes it by passing
`-PxtraGeckoViewAar=/path/to/the.aar`; without that property, local/debug work
continues to use the stock Maven GeckoView dependency.

Pull requests run only the fast recipe validation job. Native ARM builds are
manual (`scope=smoke-arm64` for quick development checks or
`scope=release-both` for the full ARM64 + ARMv7 qualification) and run
automatically on matching pushes to `master`. The full path packages the
universal AAR and builds one Xtra `app-release.apk` against it. To publish the
AAR, dispatch with `experiment=validate`, `scope=release-both`, and a new
`xtra-gv-*` value in `release_tag`.

Only the `safe` profile is published by that step. The `nowebrtc` profile is
available only through the explicit manual experiment input and is never part
of the production fat AAR.

After publishing an immutable GeckoView release, configure the repository
variable `XTRA_GECKOVIEW_RELEASE_TAG` to that exact tag. The normal Android
release workflow then downloads the one AAR, verifies its source revision and
SHA-256 manifest, and builds the unchanged `app-release.apk` updater contract.

The R1 release-candidate path is separate from that safe production path and
uses tags matching `geckoview-twitch-auth-r1-v*`. It publishes only after all
four ABI integrations succeed on the same Xtra commit and recipe identity.

Mozilla's source and GeckoView build remain under the licenses in that source
tree. This repository contains configuration and scripts only.
