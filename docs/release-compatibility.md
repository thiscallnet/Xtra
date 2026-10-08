# Installing older GitHub releases

Starting with the releases that use Android version code 10000, download an older
Xtra APK from GitHub and install it over the current app. Use the same package and
signing identity, and the matching architecture or universal APK. App data stays
in place. Installing a newer release works the same way; the in-app updater still
offers newer releases using their version and GitHub build number.

This applies within a compatibility group. Existing releases with lower Android
version codes cannot be installed over the new group without uninstalling first.

## Maintaining a compatibility group

`defaultVersionCode` in `app/build.gradle.kts` is the Android installation and data
compatibility boundary. Keep it constant for compatible releases. Increase it
before publishing any change that an earlier release cannot safely read, including
Room schema changes without supported reverse migrations, incompatible settings
formats, or new irreversible persisted state. A higher boundary allows upgrades
but prevents users from installing older, incompatible releases over their data.
Never decrease it or reuse a previous boundary.

`ciBuildNumber` is the independent GitHub Actions run number. It identifies the
release in BuildConfig, the manifest, diagnostics, and release metadata. It must
not change Android's version code. The publisher orders builds by this number;
the updater compares semantic versions and then build numbers, and checks the
downloaded APK's embedded identity before handing it to Android's installer.

These rules target the GitHub APK distribution. Store distributions that require
a unique version code for every upload need their own versioning policy.
