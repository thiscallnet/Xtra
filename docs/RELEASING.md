# Releasing Xtra

1. Change `applicationVersionName` in `app/build.gradle.kts`, for example from `2.58.5` to `2.58.6`.
2. Merge that change to `master` and make sure CI passes.
3. Tag that exact `master` commit and push the tag:

   ```bash
   git tag v2.58.6
   git push origin v2.58.6
   ```

The existing Build workflow validates that `v2.58.6` matches `applicationVersionName` and publishes the stable release as `Xtra 2.58.6`. It also checks that the tagged commit is reachable from `master`.

The first semantic release must advance beyond the last build-tag release. Since existing releases use versionName `2.58.5`, the bridge release is `2.58.6`; do not create a first semantic tag named `v2.58.5`.

Each stable release contains these APKs and metadata:

```text
app-release.apk                  universal compatibility APK
app-arm64-v8a-release.apk        ARM64
app-armeabi-v7a-release.apk      ARM 32-bit
app-x86-release.apk              x86 32-bit
app-x86_64-release.apk           x86 64-bit
xtra-release-metadata.json
```

`app-release.apk` is intentionally retained so old Xtra installations can continue updating even if they skipped the first ABI-aware version. New versions prefer the ABI-specific APK that matches the device, which reduces download size, and fall back to the universal APK when needed.

CI builds from `master`, pull requests, and manual runs are Actions artifacts, not GitHub Releases. CI generates the Android `versionCode` from the workflow's run number. That internal build number is separate from the product SemVer and should not be included in release tags.
