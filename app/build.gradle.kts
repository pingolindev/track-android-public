/*
 * Copyright (c) 2025 Pingolin LLC
 * This file is part of Pingolin Track.
 *
 * Pingolin Track is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Track is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.navigation.safeargs.kotlin")
    id("kotlin-parcelize")
    // google-services is declared here but APPLIED CONDITIONALLY at the bottom
    // of this file. It aborts any build it runs in when app/google-services.json
    // is absent, and the `libre` distribution flavour deliberately ships no
    // Firebase at all — so applying it unconditionally would make a Google-free
    // build impossible. See the bottom of this file.
    id("com.google.gms.google-services") apply false
    id("com.jaredsburrows.license")
}

// Third-party licence inventory. The plugin reads each resolved dependency's POM
// and emits a machine-readable report per variant; generateThirdPartyLicenses
// below turns that report into the two files we actually ship.
licenseReport {
    generateJsonReport = true
    generateHtmlReport = false
    generateCsvReport = false
    generateTextReport = false
    // We render our own HTML asset (with the manual additions and the JNA
    // licence election merged in), so the plugin must not copy its own.
    copyHtmlReportToAssets = false
    showVersions = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Release signing material comes from the environment; there is deliberately no
// default path here. Release artifacts are normally signed after the build with
// apksigner/jarsigner, and Gradle is never handed the keystore, so "unset" is the
// normal case rather than an error, and the release build type below then
// attaches no signingConfig.
val releaseKeystore = System.getenv("PINGOLIN_KEYSTORE_PATH")
    ?.let { file(it) }
    ?.takeIf { it.exists() }
val releaseKeystorePassword = System.getenv("PINGOLIN_KEYSTORE_PASSWORD")
    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
    ?: ""
val releaseSigningAvailable = releaseKeystore != null && releaseKeystorePassword.isNotBlank()

if (releaseKeystore == null && releaseKeystorePassword.isNotBlank()) {
    logger.warn(
        "Release signing disabled: a keystore password is set but PINGOLIN_KEYSTORE_PATH " +
            "is unset or points at a missing file. Export it to sign from Gradle."
    )
}

android {
    lint {
        // What remains in the baseline is understood and inert: the theme's
        // `android:windowLightNavigationBar` needs API 27 against minSdk 26,
        // and an unknown theme attribute is simply dropped by the resource
        // system on older releases. Fixing it "properly" means splitting the
        // theme into a Base + a values-v27 override, which is more risk than
        // the attribute is worth.
        //
        // The baseline exists so lint FAILS ON NEW ERRORS: a NewApi call can
        // crash older Android releases, and a `catch (Exception)` cannot
        // catch NoSuchMethodError. Regenerate deliberately with
        // `./gradlew updateLintBaseline` — and read the diff, because anything
        // added here is a check being switched off.
        baseline = file("lint-baseline.xml")
        // Track speaks seven more languages (res/values-xx). A new English string is owed to them, not an
        // error that fails the build: LocaleCoverageTest keeps each translated string true to its English, and
        // tools/i18n-missing.py lists what is owed. ExtraTranslation stays fatal: a translation whose English
        // was deleted would resolve in that language only.
        warning += "MissingTranslation"
    }
    // A language ships only when it is complete, including the text in Kotlin code and layouts, so the
    // app packages English alone; removing this filter ships all eight languages. `-Ptrack.allLocales`
    // builds a translated APK for checking the translations.
    androidResources {
        if (!project.hasProperty("track.allLocales")) localeFilters += "en"
    }
    namespace = "com.pingolin.track"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pingolin.track"
        minSdk = 26
        // Android 15 (SDK 35+) requires 16KB page size alignment
        // - SQLCipher: Using sqlcipher-android:4.9.0 (16KB compatible; 16KB support landed in 4.6.1)
        // - MapLibre: Version 11.5.0+ supports 16KB
        // - libpingolin_mobile.so: Built with -Clink-arg=-Wl,-z,max-page-size=16384
        targetSdk = 36

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // version.properties is generated by the release build; the fallbacks
        // below are only used when building from a bare checkout.
        val versionFile = rootProject.file("version.properties")
        val props = if (versionFile.exists()) versionFile.readLines()
            .filter { it.contains("=") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        else emptyMap()
        versionCode = (props["VERSION_CODE"] ?: "1").toInt()
        versionName = props["VERSION_NAME"] ?: "1.0.0"

        testInstrumentationRunner = "com.pingolin.track.HiltTestRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("PINGOLIN_KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("PINGOLIN_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No signingConfig is attached when the keystore is missing, so a
            // fresh clone can still assemble an (unsigned) release build.
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        debug {
            // Emulator support, DEBUG ONLY. defaultConfig restricts the app to
            // arm64-v8a; an x86_64 AVD would otherwise run the whole app through
            // ARM binary translation, which is slow and unreliable for testing.
            //
            // Scoped to the debug build type on purpose. abiFilters gates EVERY
            // native dependency, not just our SDK, so allowing x86_64 also pulls
            // in MapLibre (11.2 MB), SQLCipher (5.5 MB) and JNA alongside
            // libpingolin_mobile (12.0 MB) — 28.8 MB, enough to take a dev APK
            // from 33.7 MB to 62.6 MB. Release builds are only installed on real
            // phones; emulator work uses a locally built devDebug. Debug-only
            // keeps the emulator benefit at zero cost to what ships.
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
    }

    // Crossed with the build types above, the two dimensions below yield eight
    // variants: devGmsDebug, devLibreRelease, prodGmsRelease and so on.
    //
    // Dimension ORDER sets the variant name: environment first, then
    // distribution, so variants read `devGmsDebug` / `prodLibreRelease`.
    // Build scripts that name a variant's tasks depend on this order.
    flavorDimensions += listOf("environment", "distribution")
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "AUTH_BASE_URL", "\"https://api-auth.pingolindev.com/\"")
            // Host for the https verify-email autoVerify App
            // Link. MUST match AUTH_BASE_URL's host per flavour —
            // the shared manifest in app/src/main references it
            // via ${authDeepLinkHost} so each flavour verifies
            // links on its OWN auth host. Hard-coding both hosts
            // in the manifest would ship an App Link for the dev
            // auth host in the prod APK.
            manifestPlaceholders["authDeepLinkHost"] = "api-auth.pingolindev.com"
            buildConfigField("String", "API_BASE_URL", "\"https://api-track.pingolindev.com/\"")
            buildConfigField("String", "WEB_BASE_URL", "\"https://track.pingolindev.com/\"")
            buildConfigField("String", "INGEST_BASE_URL", "\"https://ingest-track.pingolindev.com\"")
            buildConfigField("String", "PUSH_BASE_URL", "\"https://push-track.pingolindev.com\"")
            // Account dashboard — the canonical surface for the full
            // signed-in-sessions list (Security page). Linked from the
            // in-app Manage Devices footer so users can audit web /
            // Note / admin sessions that don't share location.
            buildConfigField("String", "ACCOUNT_BASE_URL", "\"https://account.pingolindev.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"DEV\"")
        }
        create("prod") {
            dimension = "environment"
            // No applicationIdSuffix here — prod keeps the clean package
            // name. Auth is served at api-auth, never at a bare auth host:
            // only *-auth.pingolin.com subdomains are published, and the
            // dev flavour above uses api-auth.pingolindev.com the same
            // way. This host is also the one assetlinks.json is served
            // for and the one AuthActivity's App Link intent-filter names.
            buildConfigField("String", "AUTH_BASE_URL", "\"https://api-auth.pingolin.com/\"")
            // Host for the https verify-email autoVerify App Link — see the dev
            // flavour. Must match AUTH_BASE_URL's prod host, or prod
            // verify-email deep links never fire.
            manifestPlaceholders["authDeepLinkHost"] = "api-auth.pingolin.com"
            // The API host directly, matching dev and matching
            // SessionManager's own PROD fallback.
            //
            // Not the WEB host (track.pingolin.com): sending REST through
            // the web deployment's proxy would make prod the only
            // environment whose REST traffic goes through nginx and
            // terminates mTLS there, a path dev never exercises, and
            // would couple the mobile app's API to the web deployment,
            // so the web front end being down would take mobile REST
            // down with it.
            buildConfigField("String", "API_BASE_URL", "\"https://api-track.pingolin.com/\"")
            buildConfigField("String", "WEB_BASE_URL", "\"https://track.pingolin.com/\"")
            // The ingest-track and push-track hosts, as for the dev flavour. These
            // two cannot ride the web host, which only routes `/`, `/api/v1/auth`,
            // `/api/v1/support` and `/api`, so gRPC ingest and push have to name
            // their own hosts or they reach nothing.
            buildConfigField("String", "INGEST_BASE_URL", "\"https://ingest-track.pingolin.com\"")
            buildConfigField("String", "PUSH_BASE_URL", "\"https://push-track.pingolin.com\"")
            // Account dashboard (prod) — see the dev flavour for the
            // rationale.
            buildConfigField("String", "ACCOUNT_BASE_URL", "\"https://account.pingolin.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"PROD\"")
            // No certificate-pinning flag exists: see the note in
            // di/AppModule.kt (Cloudflare Universal SSL rotates the issuing CA).
        }

        // DISTRIBUTION — how much Google ships inside the APK.
        //
        // `gms` carries the Google pieces: Google Play services fused location
        // (and the keep-alive geofence), Firebase Cloud Messaging for push and
        // the Play Integrity API for registration attestation.
        //
        // `libre` resolves NONE of those artifacts (see `gmsImplementation`
        // below) so the APK qualifies as FLOSS. Fused location has a real
        // replacement in `src/libre`:
        //   fused location -> android.location.LocationManager, GPS +
        //                     NETWORK, in PlatformLocationProvider
        // The other pieces need none, because the app degrades on a
        // de-Googled device: `AttestationManager.detectPlatform()` falls through
        // Play Integrity -> Android Keystore hardware attestation -> Turnstile,
        // `PushNotificationManager` prefers UnifiedPush whenever a distributor
        // is installed, and the keep-alive geofence is simply absent. Motion
        // detection (`MotionDetectionManager`, in src/main) uses the device's
        // own sensors on both flavours, place alerts are evaluated on-device by
        // `PlaceAlertRules`, and MapLibre is FLOSS.
        create("gms") {
            dimension = "distribution"
            isDefault = true
            // No applicationIdSuffix / versionNameSuffix, ON PURPOSE. The
            // application IDs must stay exactly `com.pingolin.track.dev` /
            // `com.pingolin.track` — they are what the Play listing, the
            // Firebase project and the App Links assetlinks.json are all keyed
            // on.
        }
        create("libre") {
            dimension = "distribution"
            // Safe to suffix: the gms IDs above are unaffected, and
            // the manifest never hardcodes the id (the WorkManager
            // provider uses `${applicationId}.…`). Lets both APKs sit
            // on one device.
            // NOTE: the https verify-email App Link autoVerifies on a
            // libre build only if `com.pingolin.track.libre` and
            // `com.pingolin.track.dev.libre` are listed in the
            // assetlinks.json served by the auth host. The custom
            // `track://verify-email` scheme works either way.
            applicationIdSuffix = ".libre"
            versionNameSuffix = "-libre"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                // MockK + kotlin-reflect metadata accumulates over a test run; give the
                // worker enough heap that the full suite fits without OOMing, and fork a
                // fresh JVM after each test class so state doesn't carry between classes.
                it.maxHeapSize = "3g"
                it.setForkEvery(1)
                // Keep the per-class isolation, but run four
                // test classes in parallel instead of every
                // class serially.
                it.maxParallelForks = 4
            }
        }
    }

    // Packaging excludes for androidTest APK. JUnit Jupiter is dragged in
    // transitively by some test dependency and its META-INF/LICENSE.md file
    // collides across multiple jars. We pick the first one; the rest are
    // duplicate licensing text, not code.
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    // Pingolin Track SDK
    implementation(project(":library"))

    // Android Core
    implementation("androidx.core:core-ktx:1.17.0") // 1.19.0 requires compileSdk 37; stay on 36
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Material Design 3
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.7")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // Room Database with SQLCipher encryption.
    // Do not drop sqlcipher-android below 4.6.1 — see the 16KB note on targetSdk.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.9.0@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.6.2")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ---------------------------------------------------------------------
    // THE ENTIRE GOOGLE SURFACE OF THIS APP.
    //
    // `gmsImplementation` (not `implementation`) is what makes the `libre`
    // variant Google-free: these artifacts are scoped to the `gms` distribution
    // flavour and are simply absent from libre's classpath. Everything that
    // touches them lives in src/gms; src/main must stay clean or the libre
    // variant will not compile. Guarded by MainSourceSetIsGoogleFreeTest, and
    // verifiable with:
    //   ./gradlew :app:dependencies --configuration prodLibreReleaseRuntimeClasspath
    //
    // QUOTED on purpose: `gmsImplementation(...)` unquoted is a type-safe
    // accessor, and the Kotlin DSL generates those from the PREVIOUS build's
    // model — so a flavour declared in this same file has no accessor on a
    // fresh clone or after `clean`, and the script fails to compile with
    // "Unresolved reference 'gmsImplementation'" (plus cascading bogus errors on
    // unrelated lines). The string form always resolves. Do not "tidy" the
    // quotes away.
    // ---------------------------------------------------------------------

    // Google Play Services Location — fused location and the keep-alive
    // geofence. On libre, src/libre/…/PlatformLocationProvider.kt replaces
    // the location side with platform APIs and the geofence is a no-op.
    "gmsImplementation"("com.google.android.gms:play-services-location:21.3.0")

    // Google Play Integrity API for attestation (CAPTCHA bypass). On libre,
    // AttestationManager falls through to Android Keystore hardware attestation,
    // then Turnstile.
    "gmsImplementation"("com.google.android.play:integrity:1.4.0")

    // `kotlinx.coroutines.tasks.await` — the Task<T> -> suspend bridge. Only
    // src/gms uses it (FusedLocationProvider, FirebaseFcmTokenProvider,
    // PlayIntegrityManager). It pulls com.google.android.gms:play-services-tasks
    // in with it, so leaving it unscoped would keep a Google artifact on
    // the libre classpath with nothing using it.
    "gmsImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // MapLibre Maps SDK (open-source, no API key required).
    // Do not drop below 11.5.0 — see the 16KB note on targetSdk.
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    // Retrofit & OkHttp for REST API
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")

    // Gson
    implementation("com.google.code.gson:gson:2.13.2")

    // Security - Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")
    // Already in the app through security-crypto, at this version; named so the code can use its
    // X25519 to derive a sharing key's public half (the identity is decided from what the password
    // opened, never from the key the server serves).
    implementation("com.google.crypto.tink:tink-android:1.8.0")

    // WebView compat — used by the licences screen for
    // WebSettingsCompat.setAlgorithmicDarkeningAllowed, so the bundled notices
    // follow the app's Light/Dark/System setting below API 33.
    implementation("androidx.webkit:webkit:1.16.0")

    // JNA for UniFFI Rust bindings
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // BCrypt for password preprocessing (Proton-style)
    implementation("org.mindrot:jbcrypt:0.4")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Biometric Authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Image loading (Coil 3 — new io.coil-kt.coil3 group). No network loader, on
    // purpose: coil-network-okhttp builds its own OkHttpClient on plain system
    // trust, past util/CompromisedKeyTrust.kt, and no image here comes from the
    // network. One that must would come through a client of ours, never the API
    // client, which puts the session's Bearer on its requests.
    implementation("io.coil-kt.coil3:coil:3.4.0")

    // Firebase Cloud Messaging for push notifications. gms-only — see the note
    // on the Google surface above. On libre, PushNotificationManager's provider
    // list simply omits FCM and push is UnifiedPush or nothing.
    // BOM 34.x has no KTX modules: the KTX APIs live in the main
    // firebase-messaging artifact.
    "gmsImplementation"(platform("com.google.firebase:firebase-bom:34.14.1"))
    "gmsImplementation"("com.google.firebase:firebase-messaging")

    // UnifiedPush connector for self-hosted/privacy-focused push notifications
    // https://central.sonatype.com/artifact/org.unifiedpush.android/connector
    implementation("org.unifiedpush.android:connector:3.0.3") {
        // Exclude tink (JVM) to avoid conflict with tink-android from security-crypto
        exclude(group = "com.google.crypto.tink", module = "tink")
    }

    // LeakCanary for memory leak detection (debug builds only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.mockito:mockito-core:5.16.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // NOTE: OkHttp 5 moved MockWebServer to com.squareup.okhttp3:mockwebserver3
    // (package mockwebserver3, immutable MockResponse.Builder). This deliberately
    // keeps the LEGACY artifact (still published in 5.x), so tests that use the
    // okhttp3.mockwebserver package + setResponseCode()/setBody() setters work
    // unchanged.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // WorkManagerTestInitHelper, on the JVM side too: whether a periodic
    // worker is ARMED AT ALL is a scheduling fact a Robolectric test can
    // assert without a device.
    testImplementation("androidx.work:work-testing:2.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("io.mockk:mockk-android:1.13.17")

    // Instrumented testing dependencies
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60.1")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.60.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("org.mockito:mockito-android:5.16.1")
    androidTestImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.work:work-testing:2.11.1")
}

// ---------------------------------------------------------------------------
// Third-party licence notices
//
// `./gradlew :app:generateThirdPartyLicenses` regenerates both shipped files
// from one source of truth:
//
//   THIRD_PARTY_LICENSES.md                        repo root, for readers
//   app/src/main/assets/open_source_licenses.html  bundled in the APK and
//                                                  rendered by LicensesFragment
//
// Both are committed. Run the task after any dependency change and commit the
// result. It is deliberately NOT wired into assemble: a normal build must never
// rewrite tracked files.
//
// Notices describe what ships, so the inventory comes from the prodGmsRelease
// dependency graph. The environment flavours differ only in buildConfigField
// values, and the release build type is the one that drops debugImplementation
// deps such as LeakCanary — so prodGmsRelease is the variant that matches the
// published APK.
//
// gms and NOT libre, of the two distribution flavours: gms is the SUPERSET, so a
// report built from it covers everything either APK can contain. The libre APK
// therefore ships an attribution list naming a few components it does not
// actually contain (play-services-location, firebase-messaging, integrity),
// which over-discloses rather than under-discloses. It is also the only choice
// that works — app/licenses/manual-licenses.json carries entries keyed on those
// coordinates, and they are absent from the libre classpath.
// ---------------------------------------------------------------------------
val licenceReportTaskName = "licenseProdGmsReleaseReport"

tasks.register("generateThirdPartyLicenses") {
    group = "reporting"
    description =
        "Regenerates THIRD_PARTY_LICENSES.md and the in-app open_source_licenses.html asset."
    dependsOn(licenceReportTaskName)

    val jsonReport = layout.buildDirectory.file("reports/licenses/$licenceReportTaskName.json")
    val supplementFile = layout.projectDirectory.file("licenses/manual-licenses.json").asFile
    // The Rust crates linked into libpingolin_mobile.so. The licence plugin
    // reads POM metadata and cannot see any of them; this file is produced by
    // tools/generate-native-licenses.py from mobile-sdk's Cargo.lock and
    // committed, because a Gradle build has no business shelling out to cargo.
    val nativeFile = layout.projectDirectory.file("licenses/native-rust-licenses.json").asFile
    val markdownOut = rootProject.layout.projectDirectory.file("THIRD_PARTY_LICENSES.md").asFile
    val htmlOut = layout.projectDirectory.file("src/main/assets/open_source_licenses.html").asFile
    val appLabel = "Pingolin Track for Android"

    inputs.file(jsonReport)
    inputs.file(supplementFile)
    inputs.file(nativeFile)
    outputs.file(markdownOut)
    outputs.file(htmlOut)

    doLast {
        val slurper = groovy.json.JsonSlurper()

        @Suppress("UNCHECKED_CAST")
        val scanned = slurper.parse(jsonReport.get().asFile) as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val supplement = slurper.parse(supplementFile) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val overrides = (supplement["overrides"] as? Map<String, Map<String, Any?>>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val notes = (supplement["notes"] as? Map<String, String>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val elections = (supplement["elections"] as? List<Map<String, Any?>>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val additional = (supplement["additional"] as? List<Map<String, Any?>>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val nativeNotes = (supplement["native_notes"] as? Map<String, Any?>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val native = slurper.parse(nativeFile) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val nativeComponents = (native["components"] as? List<Map<String, Any?>>).orEmpty()

        if (nativeComponents.isEmpty()) {
            throw GradleException(
                "${nativeFile.path} lists no components. The Rust crates linked into " +
                    "libpingolin_mobile.so must appear in the notices; regenerate it with " +
                    "tools/generate-native-licenses.py."
            )
        }

        val electionsByGa = elections.associateBy { it["dependency"].toString() }

        // Canonical licence names, so the five spellings of Apache-2.0 that
        // appear across these POMs collapse to one heading. Anything not listed
        // passes through verbatim: a new licence gets its own heading rather
        // than being folded into a neighbour by a loose match.
        fun canonicalLicence(name: String?, url: String?): String {
            val n = name.orEmpty().trim()
            val u = url.orEmpty().trim()
            return when {
                n.equals("Apache-2.0", true) ||
                    n.equals("Apache 2.0", true) ||
                    n.equals("Apache License, Version 2.0", true) ||
                    n.equals("The Apache License, Version 2.0", true) ||
                    n.equals("The Apache Software License, Version 2.0", true) ->
                    "Apache License 2.0"
                n.equals("BSD", true) && u.contains("BSD-2-Clause", true) -> "BSD 2-Clause License"
                n.equals("BSD-3-Clause", true) -> "BSD 3-Clause License"
                n.equals("ISC", true) -> "ISC License"
                else -> n
            }
        }

        val problems = mutableListOf<String>()
        val rows = mutableListOf<Map<String, String>>()

        fun addRow(entry: Map<String, Any?>, manualReason: String?) {
            val coordinate = entry["dependency"].toString()
            val ga = coordinate.substringBeforeLast(':')

            @Suppress("UNCHECKED_CAST")
            val declared = (entry["licenses"] as? List<Map<String, Any?>>).orEmpty()
            val election = electionsByGa[ga]

            @Suppress("UNCHECKED_CAST")
            val overrideLicences =
                (overrides[ga]?.get("licenses") as? List<Map<String, Any?>>).orEmpty()

            var licence = ""
            var licenceUrl = ""
            var resolution = ""

            when {
                election != null -> {
                    @Suppress("UNCHECKED_CAST")
                    val elected = election["elected"] as Map<String, Any?>
                    licence = elected["license"].toString()
                    licenceUrl = elected["license_url"].toString()
                    resolution = "Dual-licensed; ${licence} elected by Pingolin — " +
                        "see \"Licence elections\" above."
                }
                overrideLicences.isNotEmpty() -> {
                    licence = canonicalLicence(
                        overrideLicences[0]["license"]?.toString(),
                        overrideLicences[0]["license_url"]?.toString(),
                    )
                    licenceUrl = overrideLicences[0]["license_url"]?.toString().orEmpty()
                    resolution = "Licence added by hand: " +
                        overrides[ga]?.get("reason")?.toString().orEmpty()
                }
                declared.size == 1 -> {
                    licence = canonicalLicence(
                        declared[0]["license"]?.toString(),
                        declared[0]["license_url"]?.toString(),
                    )
                    licenceUrl = declared[0]["license_url"]?.toString().orEmpty()
                }
                declared.isEmpty() ->
                    problems += "$coordinate declares no licence in its POM."
                else ->
                    problems += "$coordinate declares ${declared.size} licences " +
                        "(${declared.joinToString { it["license"].toString() }}) and no " +
                        "election is recorded."
            }

            if (licence.isBlank()) {
                if (problems.none { it.startsWith(coordinate) }) {
                    problems += "$coordinate has a <license> element with no name."
                }
                return
            }

            @Suppress("UNCHECKED_CAST")
            val developers = (entry["developers"] as? List<Any?>).orEmpty()
                .joinToString(", ") { it.toString() }

            rows += mapOf(
                "licence" to licence,
                "licenceUrl" to licenceUrl,
                "project" to entry["project"]?.toString().orEmpty(),
                "coordinate" to coordinate,
                "url" to entry["url"]?.toString().orEmpty(),
                "developers" to developers,
                "note" to listOfNotNull(
                    resolution.ifBlank { null },
                    notes[ga],
                    manualReason,
                ).joinToString(" "),
            )
        }

        scanned.forEach { addRow(it, manualReason = null) }
        additional.forEach {
            addRow(
                it,
                manualReason = "Added by hand — not reported by the POM scan: " +
                    it["_reason"]?.toString().orEmpty(),
            )
        }

        // Loud, not silent. A dependency whose POM omits its licence must be
        // resolved in licenses/manual-licenses.json before the notices can be
        // regenerated — otherwise it would simply vanish from the inventory and
        // nobody would notice until someone asked for the attribution.
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Cannot generate third-party licence notices; ")
                    appendLine("${problems.size} component(s) have unusable licence metadata:")
                    problems.sorted().forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Resolve each one in ${supplementFile.path} (add an ")
                    appendLine("\"overrides\" entry for a missing licence, or an \"elections\" ")
                    appendLine("entry to record which licence Pingolin takes), then re-run.")
                }
            )
        }

        // Biggest licence group first, then alphabetically. Components within a
        // group are ordered by coordinate, and the URL shown for the group is
        // the one the most POMs point at — both so that regenerating without a
        // dependency change produces a byte-identical file.
        val byLicence: Map<String, List<Map<String, String>>> = rows
            .groupBy { it["licence"]!! }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Map<String, String>>>> {
                it.value.size
            }.thenBy { it.key })
            .associate { it.key to it.value.sortedBy { row -> row["coordinate"]!!.lowercase() } }

        val licenceUrlFor: Map<String, String> = byLicence.mapValues { (_, items) ->
            items.map { it["licenceUrl"]!! }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key })
                .firstOrNull()?.key.orEmpty()
        }

        // Native components, grouped by SPDX expression exactly as each crate
        // publishes it. No canonicalisation here: a Cargo.toml licence field is
        // already an SPDX expression, and rewriting "Apache-2.0 AND ISC" or
        // "MIT OR Apache-2.0" would change what it says.
        val nativeByLicence: Map<String, List<Map<String, Any?>>> = nativeComponents
            .groupBy { it["license"].toString() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Map<String, Any?>>>> {
                it.value.size
            }.thenBy { it.key })
            .associate { it.key to it.value.sortedBy { c -> c["name"].toString().lowercase() } }

        val nativeAbisMap = (native["abis"] as? Map<*, *>).orEmpty()
        val nativeAbisCount = nativeAbisMap.size
        val nativeAbis = nativeAbisMap.entries.joinToString(", ") { "${it.key} (${it.value})" }
        val nativeIntro = listOf(
            "The components above are the Java and Android dependencies, taken " +
                "from POM metadata. They are not the whole picture: " +
                "${native["library"]} is a prebuilt native library with " +
                "third-party Rust crates statically linked into it, and no POM " +
                "describes those. They are inventoried separately, from the Rust " +
                "dependency graph, and listed here.",
            "ABIs covered: $nativeAbis.",
            native["inclusion_rule"].toString(),
            "Licences are reproduced as each crate publishes them, as SPDX " +
                "expressions. \"OR\" marks a choice of terms offered by the " +
                "author; every such choice here is between permissive licences " +
                "whose conditions Pingolin satisfies alike, by reproducing this " +
                "notice. \"AND\" means both sets of terms apply.",
        )

        val preamble = listOf(
            "$appLabel bundles the open-source components listed below. Their " +
                "copyright notices and licence terms are reproduced here in " +
                "satisfaction of those licences, including section 4(d) of the " +
                "Apache License, Version 2.0.",
            "This inventory is generated — do not edit it by hand. Regenerate it " +
                "with `./gradlew :app:generateThirdPartyLicenses` after any " +
                "dependency change and commit the result. It is derived from the " +
                "POM metadata of the resolved `prodGmsRelease` dependency graph, " +
                "supplemented by `app/licenses/manual-licenses.json` for the " +
                "artifacts whose POMs are incomplete, and by " +
                "`app/licenses/native-rust-licenses.json` for the native library " +
                "(refresh that one with `tools/generate-native-licenses.py`).",
            "Pingolin Track itself is free software, licensed under the GNU " +
                "General Public License, version 3 or later. The source code of " +
                "every release is at https://github.com/pingolindev/track-android-public.",
        )

        // ---- Markdown, for people reading the repo ----------------------------
        markdownOut.writeText(
            buildString {
                appendLine("# Third-party licences")
                appendLine()
                preamble.forEach { appendLine(it); appendLine() }
                appendLine("${rows.size} components, under ${byLicence.size} distinct licences.")
                appendLine()

                if (elections.isNotEmpty()) {
                    appendLine("## Licence elections")
                    appendLine()
                    appendLine(
                        "Where a dependency offers a choice of licences, the licence " +
                            "Pingolin takes is recorded here explicitly rather than left " +
                            "ambiguous."
                    )
                    appendLine()
                    elections.forEach { election ->
                        @Suppress("UNCHECKED_CAST")
                        val elected = election["elected"] as Map<String, Any?>

                        @Suppress("UNCHECKED_CAST")
                        val declaredNames = (election["declared"] as? List<Any?>).orEmpty()
                        appendLine("### ${election["title"]}")
                        appendLine()
                        appendLine("- Component: `${election["dependency"]}`")
                        appendLine(
                            "- Declared: ${declaredNames.joinToString(" OR ") { "`$it`" }}"
                        )
                        appendLine("- **Elected: ${elected["license"]}** (${elected["license_url"]})")
                        appendLine()

                        @Suppress("UNCHECKED_CAST")
                        (election["text"] as? List<Any?>).orEmpty().forEach {
                            appendLine(it.toString()); appendLine()
                        }
                    }
                }

                appendLine("## Summary")
                appendLine()
                appendLine("| Licence | Components |")
                appendLine("| --- | ---: |")
                byLicence.forEach { (licence, items) ->
                    appendLine("| $licence | ${items.size} |")
                }
                appendLine()

                appendLine("## Components")
                appendLine()
                byLicence.keys.forEach { licence ->
                    appendLine("### $licence")
                    appendLine()
                    licenceUrlFor[licence]?.takeIf { it.isNotBlank() }?.let {
                        appendLine("Licence text: <$it>")
                        appendLine()
                    }
                    byLicence.getValue(licence).forEach { row ->
                        val name = row["project"]!!.ifBlank { row["coordinate"]!! }
                        appendLine("- **$name** — `${row["coordinate"]}`")
                        row["developers"]!!.takeIf { it.isNotBlank() }?.let {
                            appendLine("  Copyright $it")
                        }
                        row["url"]!!.takeIf { it.isNotBlank() }?.let {
                            appendLine("  <$it>")
                        }
                        row["note"]!!.takeIf { it.isNotBlank() }?.let {
                            appendLine("  _${it}_")
                        }
                    }
                    appendLine()
                }

                appendLine("## Native (Rust) components")
                appendLine()
                appendLine(
                    "Statically linked into `${native["library"]}`, built from " +
                        "`${native["source_crate"]}` in the mobile-sdk repository."
                )
                appendLine()
                nativeIntro.forEach { appendLine(it); appendLine() }
                appendLine(
                    "${nativeComponents.size} crates, under ${nativeByLicence.size} " +
                        "distinct licence expressions."
                )
                appendLine()
                nativeByLicence.forEach { (licence, crates) ->
                    appendLine("### $licence")
                    appendLine()
                    crates.forEach { crate ->
                        appendLine("- **${crate["name"]}** ${crate["version"]}")
                        crate["description"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                            appendLine("  $it")
                        }
                        crate["repository"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                            appendLine("  <$it>")
                        }
                        // Not every crate is in every ABI, so say which when it
                        // is not all of them.
                        @Suppress("UNCHECKED_CAST")
                        val abis = (crate["abis"] as? List<Any?>).orEmpty()
                        if (abis.size < nativeAbisCount) {
                            appendLine("  Only in: ${abis.joinToString(", ")}")
                        }
                        nativeNotes[crate["name"]]?.toString()?.let {
                            appendLine("  _${it}_")
                        }
                    }
                    appendLine()
                }
            }
        )

        // ---- HTML, for the in-app screen --------------------------------------
        fun esc(s: String) = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        fun link(url: String, text: String = url) =
            if (url.isBlank()) "" else "<a href=\"${esc(url)}\">${esc(text)}</a>"

        htmlOut.parentFile.mkdirs()
        htmlOut.writeText(
            buildString {
                appendLine("<!DOCTYPE html>")
                appendLine("<html lang=\"en\"><head>")
                appendLine("<meta charset=\"utf-8\">")
                appendLine(
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                )
                appendLine("<title>Open source licenses</title>")
                // Self-contained: no network fetch, no external stylesheet. The
                // colours follow the system theme so the page matches the app in
                // both light and dark.
                appendLine("<style>")
                appendLine(
                    ":root{--bg:#ffffff;--fg:#1b1b1b;--muted:#5f6368;--rule:#e0e0e0;--link:#0b57d0}"
                )
                appendLine(
                    "@media(prefers-color-scheme:dark){:root{--bg:#121212;--fg:#e3e3e3;" +
                        "--muted:#9aa0a6;--rule:#2f2f2f;--link:#a8c7fa}}"
                )
                appendLine(
                    "body{background:var(--bg);color:var(--fg);margin:0;padding:16px;" +
                        "font-family:sans-serif;font-size:15px;line-height:1.5;" +
                        "word-wrap:break-word;overflow-wrap:anywhere}"
                )
                appendLine("h1{font-size:20px;margin:0 0 12px}")
                appendLine(
                    "h2{font-size:17px;margin:28px 0 8px;padding-top:12px;" +
                        "border-top:1px solid var(--rule)}"
                )
                appendLine("h3{font-size:15px;margin:18px 0 6px}")
                appendLine("p{margin:0 0 12px}")
                appendLine("a{color:var(--link)}")
                appendLine("ul{padding-left:20px;margin:0 0 12px}")
                appendLine("li{margin:0 0 10px}")
                appendLine(".muted{color:var(--muted);font-size:13px}")
                appendLine(".coord{font-family:monospace;font-size:13px;color:var(--muted)}")
                appendLine(
                    ".election{border:1px solid var(--rule);border-radius:8px;" +
                        "padding:12px;margin:0 0 16px}"
                )
                appendLine("</style>")
                appendLine("</head><body>")
                appendLine("<h1>Open source licenses</h1>")
                preamble.forEach { appendLine("<p>${esc(it)}</p>") }
                appendLine(
                    "<p class=\"muted\">${rows.size} components, under " +
                        "${byLicence.size} distinct licences.</p>"
                )

                if (elections.isNotEmpty()) {
                    appendLine("<h2>Licence elections</h2>")
                    elections.forEach { election ->
                        @Suppress("UNCHECKED_CAST")
                        val elected = election["elected"] as Map<String, Any?>

                        @Suppress("UNCHECKED_CAST")
                        val declaredNames = (election["declared"] as? List<Any?>).orEmpty()
                        appendLine("<div class=\"election\">")
                        appendLine("<h3>${esc(election["title"].toString())}</h3>")
                        appendLine(
                            "<p class=\"coord\">${esc(election["dependency"].toString())}</p>"
                        )
                        appendLine(
                            "<p>Declared: " +
                                declaredNames.joinToString(" OR ") { esc(it.toString()) } +
                                "<br><strong>Elected: " +
                                esc(elected["license"].toString()) + "</strong> " +
                                link(elected["license_url"].toString()) + "</p>"
                        )

                        @Suppress("UNCHECKED_CAST")
                        (election["text"] as? List<Any?>).orEmpty().forEach {
                            appendLine("<p>${esc(it.toString())}</p>")
                        }
                        appendLine("</div>")
                    }
                }

                byLicence.keys.forEach { licence ->
                    appendLine("<h2>${esc(licence)}</h2>")
                    licenceUrlFor[licence]?.takeIf { it.isNotBlank() }?.let {
                        appendLine("<p class=\"muted\">Licence text: ${link(it)}</p>")
                    }
                    appendLine("<ul>")
                    byLicence.getValue(licence).forEach { row ->
                        val name = row["project"]!!.ifBlank { row["coordinate"]!! }
                        appendLine("<li><strong>${esc(name)}</strong>")
                        appendLine("<br><span class=\"coord\">${esc(row["coordinate"]!!)}</span>")
                        row["developers"]!!.takeIf { it.isNotBlank() }?.let {
                            appendLine("<br><span class=\"muted\">Copyright ${esc(it)}</span>")
                        }
                        row["url"]!!.takeIf { it.isNotBlank() }?.let {
                            appendLine("<br>${link(it)}")
                        }
                        row["note"]!!.takeIf { it.isNotBlank() }?.let {
                            appendLine("<br><span class=\"muted\">${esc(it)}</span>")
                        }
                        appendLine("</li>")
                    }
                    appendLine("</ul>")
                }

                appendLine("<h2>Native (Rust) components</h2>")
                appendLine(
                    "<p>Statically linked into <span class=\"coord\">" +
                        "${esc(native["library"].toString())}</span>, built from " +
                        "<span class=\"coord\">${esc(native["source_crate"].toString())}" +
                        "</span> in the mobile-sdk repository.</p>"
                )
                nativeIntro.forEach { appendLine("<p>${esc(it)}</p>") }
                appendLine(
                    "<p class=\"muted\">${nativeComponents.size} crates, under " +
                        "${nativeByLicence.size} distinct licence expressions.</p>"
                )
                nativeByLicence.forEach { (licence, crates) ->
                    appendLine("<h3>${esc(licence)}</h3>")
                    appendLine("<ul>")
                    crates.forEach { crate ->
                        appendLine(
                            "<li><strong>${esc(crate["name"].toString())}</strong> " +
                                "<span class=\"coord\">${esc(crate["version"].toString())}</span>"
                        )
                        crate["description"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                            appendLine("<br>${esc(it)}")
                        }
                        crate["repository"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                            appendLine("<br>${link(it)}")
                        }

                        @Suppress("UNCHECKED_CAST")
                        val abis = (crate["abis"] as? List<Any?>).orEmpty()
                        if (abis.size < nativeAbisCount) {
                            appendLine(
                                "<br><span class=\"muted\">Only in: " +
                                    esc(abis.joinToString(", ")) + "</span>"
                            )
                        }
                        nativeNotes[crate["name"]]?.toString()?.let {
                            appendLine("<br><span class=\"muted\">${esc(it)}</span>")
                        }
                        appendLine("</li>")
                    }
                    appendLine("</ul>")
                }
                appendLine("</body></html>")
            }
        )

        logger.lifecycle(
            "Third-party licences: ${rows.size} Gradle components + " +
                "${nativeComponents.size} native Rust crates -> " +
                "${markdownOut.relativeTo(rootProject.projectDir)} and " +
                "${htmlOut.relativeTo(rootProject.projectDir)}"
        )
    }
}

// ---------------------------------------------------------------------------
// APPLYING google-services WITHOUT MAKING A GOOGLE-FREE BUILD IMPOSSIBLE.
//
// The plugin registers a `process<Variant>GoogleServices` task for EVERY variant
// and fails that task when app/google-services.json is absent. Applied
// unconditionally, that makes `:app:assembleProdLibreRelease` impossible
// without a Firebase file the libre APK never uses.
//
// So apply it UNLESS the requested tasks name a libre variant and no gms one.
// The default when nothing names a variant (`./gradlew build`, `assemble`, an
// IDE sync, a plain test run) is to APPLY, deliberately: the failure mode of the
// opposite default is SILENT. A gms APK assembled with the plugin skipped still
// builds and installs — it just has no google_app_id resource, so FCM never
// registers and push is dead with nothing in the log to say why.
// ---------------------------------------------------------------------------
val requestedVariantTasks = gradle.startParameter.taskNames.filter {
    it.contains("gms", ignoreCase = true) || it.contains("libre", ignoreCase = true)
}
val libreOnlyInvocation = requestedVariantTasks.isNotEmpty() &&
    requestedVariantTasks.none { it.contains("gms", ignoreCase = true) }

if (!libreOnlyInvocation) {
    apply(plugin = "com.google.gms.google-services")

    // A mixed invocation (`./gradlew build`, or a gms and a libre task together)
    // has the plugin applied, so switch its task off for the libre variants as
    // well: a libre APK must carry no google_app_id / gcm_defaultSenderId string
    // resource whether or not a gms task happens to share the build.
    tasks.matching {
        it.name.startsWith("process") &&
            it.name.endsWith("GoogleServices") &&
            it.name.contains("Libre")
    }.configureEach { enabled = false }
}
