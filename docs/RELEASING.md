# Releasing Xtra

Every push to `master`, including a merge, runs the Build workflow. The workflow keeps the product version from `applicationVersionName` and assigns a unique Android build number from the GitHub Actions run number. It uploads the verified release APK package as the `xtra-master-release-package` Actions artifact for 14 days.

The generated package contains:

```text
app-release.apk                  universal compatibility APK
app-arm64-v8a-release.apk        ARM64
app-armeabi-v7a-release.apk      ARM 32-bit
app-x86-release.apk              x86 32-bit
app-x86_64-release.apk           x86 64-bit
xtra-release-metadata.json
```

To publish one of those builds for users:

1. Open the `Publish build release` workflow in GitHub Actions.
2. Enter the successful `Build` workflow run ID from a push to `master`.
3. Select `prerelease` for a direct-download build or `stable` for a build that the in-app updater may offer.
4. Start the workflow.

The publish workflow downloads the exact package from that run. It creates the matching immutable tag `v<version>-build.<run-number>` at the original `master` commit, validates every APK and metadata file, and publishes the GitHub Release. It never rebuilds the selected source.

Prereleases remain available on GitHub for manual downloads but are ignored by the app updater. Stable build releases are available through the normal update flow. The updater understands build tags and compares their build numbers, so several builds can share the same product version while still upgrading in order.

Change `applicationVersionName` in `app/build.gradle.kts` only when intentionally starting a new product version. Do not commit an automatic version bump for every build. The CI build number is derived from the workflow run and is recorded in the APK versionCode and release metadata.

`app-release.apk` is retained for older Xtra installations that skipped the first ABI-aware version. New versions prefer the ABI-specific APK for the device and fall back to the universal APK when needed.
