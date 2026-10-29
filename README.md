# Pingolin Track for Android

The Android client for [Pingolin Track](https://pingolin.com), an end-to-end
encrypted location-sharing service. Locations, place names and circle metadata
are encrypted on the device; the server stores ciphertext and cannot read any of
it. The cryptography lives in a shared Rust SDK that the iOS client uses too — it
is not reimplemented here.

## Repository layout

| Path | What it is |
| --- | --- |
| `app/` | The application: UI, storage, networking, background tracking |
| `library/` | **Generated.** UniFFI Kotlin bindings and the prebuilt `libpingolin_mobile.so` for the Rust SDK — see [The Rust SDK](#the-rust-sdk-in-library) |
| `THIRD_PARTY_LICENSES.md` | **Generated.** Attribution for every bundled dependency |

The UI is Fragments and Views with view binding, not Compose. Dependency
injection is Hilt, persistence is Room over SQLCipher, and the map is MapLibre.

## Prerequisites

- **JDK 17 or newer.** `app/build.gradle.kts` compiles to JVM 17
  (`jvmTarget.set(JvmTarget.JVM_17)`); this repo is built with JDK 21.
- **Android SDK** with the API 36 platform. `compileSdk = 36`, `targetSdk = 36`,
  `minSdk = 26` (Android 8.0).
- **Gradle** — do not install it. Use the wrapper (`./gradlew`), which pins
  9.6.1.
- **No NDK.** Neither module runs `externalNativeBuild`; the `.so` in `library/`
  is prebuilt and committed. You only need an NDK if you rebuild the Rust SDK
  itself, which happens in a different repository.

Point Gradle at your SDK with a `local.properties` in the repo root:

```properties
sdk.dir=/path/to/android-sdk
```

`local.properties` is gitignored, as is the whole `build/` tree.

### `google-services.json`, and how to build without one

The `gms` distribution flavour needs `app/google-services.json`. The plugin that
reads it aborts the build with `File google-services.json is missing.` when it is
absent, and the file carries project-specific Firebase API keys, so it is
gitignored (`.gitignore` line 38) and **is not in this repository**.

**The `libre` flavour needs no such file** and is the answer for a fresh clone:

```bash
./gradlew :app:assembleDevLibreDebug
```

The plugin is applied conditionally (bottom of `app/build.gradle.kts`) precisely
so that a Google-free build is possible without Google's file. It is applied
whenever the invocation is not libre-only, because the failure mode of the other
default is silent: a gms APK built with the plugin skipped installs fine and
simply never receives a push.

For a `gms` build, get a `google-services.json` from a Firebase project of your
own (Firebase console → Project settings → Your apps → Android) and register both
application IDs: `com.pingolin.track.dev` and `com.pingolin.track`. Firebase
Cloud Messaging is only one of the two push transports — the app also supports
[UnifiedPush](https://unifiedpush.org/), selectable in Settings.

## Build

```bash
./gradlew :app:assembleDevGmsDebug      # what you want for local work
./gradlew :app:assembleDevLibreDebug    # Google-free; needs no google-services.json
```

Two flavour dimensions crossed with two build types give eight variants, named
environment first: `devGmsDebug`, `prodLibreRelease` and so on. **There is
no staging flavour.**

| `environment` | Application ID | Backend |
| --- | --- | --- |
| `dev` | `com.pingolin.track.dev` | `*.pingolindev.com` |
| `prod` | `com.pingolin.track` | `*.pingolin.com` |

Neither flavour pins TLS certificates. The hosts sit behind Cloudflare
Universal SSL, which chooses and rotates the issuing CA, so a pin would break
the app on the next rotation; the clients rely on system trust instead (see
`di/AppModule.kt`).

| `distribution` | Application ID suffix | What is inside |
| --- | --- | --- |
| `gms` | none | Google Play services fused location, Firebase Cloud Messaging, Play Integrity attestation |
| `libre` | `.libre` | none of the above — zero `com.google.android.gms`, `com.google.firebase` or `com.google.android.play` on the classpath |

The two `distribution` flavours are the same app. `libre` replaces fused location
with `android.location.LocationManager` (GPS + NETWORK), in `app/src/libre/`.
Motion detection is the same in both: `MotionDetectionManager` reads
`Sensor.TYPE_SIGNIFICANT_MOTION` and an accelerometer variance dwell, which need no
permission. Push falls back to UnifiedPush, and registration attestation to
Android Keystore hardware attestation or Turnstile — the same paths a `gms` APK
takes on a phone without Google Play services. Place alerts are evaluated
on-device and MapLibre is FLOSS, so
neither changes.

The `.libre` suffix lets both APKs sit on one device. It also means the libre
application IDs are not in the `assetlinks.json` the auth host serves, so the
`https` verify-email App Link does not autoVerify on a libre build until they are
added there; the `track://verify-email` scheme keeps working meanwhile.

`debug` additionally packages the `x86_64` ABI so the app runs natively on an
emulator; `release` is `arm64-v8a` only and is minified with R8. A release build
without signing material configured produces an unsigned APK rather than
failing — see the comment above `signingConfigs` in `app/build.gradle.kts`.

`version.properties` in the repo root is written by the release build. When it is absent the
build falls back to version `1.0.0` / code `1`, so a bare clone still assembles.

If you are building on a small machine, note that `gradle.properties` sizes the
heap for a large build host (`-Xmx4096m` plus a 3 GB Kotlin daemon).

## Test

```bash
./gradlew :app:testDevGmsDebugUnitTest :app:testDevLibreDebugUnitTest --continue
```

That is the full unit suite (`--continue` so a gms failure cannot hide a libre
one). Most tests live in `app/src/test/` and run under both flavours; the ones
that pin libre-specific behaviour live in `app/src/testLibre/` and run in no
other task. It
is JVM-only: JUnit 4 with MockK, Mockito and Robolectric. Robolectric tests pin
`@Config(sdk = [34], ...)` because Robolectric 4.14.1 ships no SDK 36 image.

Two things worth knowing before you spend time on tests:

- `app/src/androidTest/` (Espresso, UI Automator, Hilt instrumentation) is **not
  part of that suite** and needs a device or emulator. Put new coverage in `app/src/test/`
  with Robolectric, where it will actually run.
- Gradle reporting `UP-TO-DATE` is not a test result. Pass `--rerun` when you
  need proof a suite executed, and read the JUnit XML under
  `app/build/test-results/`.

## The Rust SDK in `library/`

Everything under `library/src/main/java/uniffi/` and
`library/src/main/jniLibs/<abi>/` is **generated output, committed as a binary
mirror**. Do not hand-edit it: the next regeneration overwrites it, and the
Kotlin bindings must stay in lockstep with the `.so` they describe.

The source is the `mobile-sdk` repository, whose `build-android.sh` builds the
Rust crates for each Android ABI and runs `uniffi-bindgen`. That script expects a
sibling checkout layout:

```
<parent>/
  mobile-sdk/            # the SDK source and build-android.sh
  pingolin-crypto/       # depended on by relative path from mobile-sdk
  track-android-public/  # this repository
```

Clone the two siblings under exactly those names; the relative paths depend on them:

```sh
git clone https://github.com/pingolindev/mobile-sdk-public.git mobile-sdk
git clone https://github.com/pingolindev/pingolin-crypto-public.git pingolin-crypto
```

To refresh the bindings, run `./build-android.sh` in `mobile-sdk` (it needs Rust
with the Android targets, an NDK r26+, and `protoc`; the script's header lists
the details and it checks each prerequisite before starting), then copy its
output over the committed copies as the script prints on completion:

```
<abi>/*.so       -> track-android-public/library/src/main/jniLibs/<abi>/
kotlin/*/uniffi/ -> track-android-public/library/src/main/java/uniffi/
```

Diffing a fresh build against the committed files is how you verify the shipped
binaries match the SDK source.

`library/` also declares a `maven-publish` publication (`com.pingolin:track-sdk`).
No repository is configured for it, so it publishes only to the local Maven cache;
there is no published artifact to depend on.

## Third-party licences

`THIRD_PARTY_LICENSES.md` lists every bundled component with its licence and
copyright holder. The same inventory ships inside the app, reachable from
Settings → About → Open source licenses, rendered offline from
`app/src/main/assets/open_source_licenses.html`.

Both files are generated and committed. Regenerate them after **any** dependency
change and commit the result:

```bash
./gradlew :app:generateThirdPartyLicenses
```

The Java/Android half of the inventory comes from the resolved `prodGmsRelease`
dependency graph's POM metadata — `gms` because it is the superset of the two
distribution flavours, so the libre APK ships an attribution list naming a few
components it does not actually contain (over-disclosure, not under-disclosure), via the `com.jaredsburrows.license` plugin.
Some artifacts publish incomplete licence metadata, and one dependency (JNA) is
dual-licensed; `app/licenses/manual-licenses.json` records those corrections and
the licence Pingolin elects. The task **fails** rather than emitting an inventory
with a gap in it, and tells you what to add — so a new dependency with a bare POM
cannot quietly go unattributed.

### The native library needs a separate step

POM metadata only describes JVM and Android artifacts. It says nothing about the
third-party **Rust crates statically linked into `libpingolin_mobile.so`**, and
those carry licence obligations of their own — 244 crates, at the last count.
They are inventoried by:

```bash
tools/generate-native-licenses.py            # then re-run the Gradle task above
```

which resolves the dependency graph of `crates/mobile-sdk` for all three shipped
ABIs and unions them (the sets genuinely differ — `cpufeatures` is x86_64-only),
excluding dev-, build- and proc-macro dependencies, which leave no code in the
library. It needs `cargo` on `PATH` but compiles nothing, so no NDK is required.

It finds the mobile-sdk checkout from `--mobile-sdk-dir`, then `$MOBILE_SDK_DIR`,
then a sibling `../mobile-sdk` — the layout described above — and fails with a
message naming all three if none resolve:

```bash
tools/generate-native-licenses.py --mobile-sdk-dir /path/to/mobile-sdk
MOBILE_SDK_DIR=/path/to/mobile-sdk tools/generate-native-licenses.py
```

Its output, `app/licenses/native-rust-licenses.json`, is committed and read by
the Gradle task. Run it whenever the SDK's dependencies change — a Gradle build
will not, and should not, shell out to cargo.

Nothing in a normal build regenerates any of these files, so a stale inventory
would otherwise be invisible; `OpenSourceLicensesAssetTest` in the unit test
suite guards against the asset going missing or losing its key entries, on both
the Gradle and the native side.

## Contributing and security

This repository is published as one snapshot per release: each commit is a
shipped version, tagged with it, and the tree at a tag is the source that
version was built from. Development happens elsewhere, so this repository
takes no pull requests or issues.

Report security problems by email to **security@mail.pingolin.com**, not in
public.

## Licence

Pingolin Track is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

The Pingolin name and logos are trademarks of Pingolin LLC and are not covered
by the licence. A modified version you distribute must not use them.

The licences of the third-party components this app bundles are separate; they
are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
