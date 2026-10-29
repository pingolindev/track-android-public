# Third-party licences

Pingolin Track for Android bundles the open-source components listed below. Their copyright notices and licence terms are reproduced here in satisfaction of those licences, including section 4(d) of the Apache License, Version 2.0.

This inventory is generated — do not edit it by hand. Regenerate it with `./gradlew :app:generateThirdPartyLicenses` after any dependency change and commit the result. It is derived from the POM metadata of the resolved `prodGmsRelease` dependency graph, supplemented by `app/licenses/manual-licenses.json` for the artifacts whose POMs are incomplete, and by `app/licenses/native-rust-licenses.json` for the native library (refresh that one with `tools/generate-native-licenses.py`).

Pingolin Track itself is free software, licensed under the GNU General Public License, version 3 or later. The source code of every release is at https://github.com/pingolindev/track-android-public.

160 components, under 7 distinct licences.

## Licence elections

Where a dependency offers a choice of licences, the licence Pingolin takes is recorded here explicitly rather than left ambiguous.

### Java Native Access (JNA)

- Component: `net.java.dev.jna:jna`
- Declared: `LGPL-2.1-or-later` OR `Apache-2.0`
- **Elected: Apache License 2.0** (https://www.apache.org/licenses/LICENSE-2.0.txt)

JNA is dual-licensed. Its POM declares both LGPL-2.1-or-later and Apache-2.0, with the comment: "Java Native Access (JNA) is licensed under the LGPL, version 2.1 or later, or the Apache License, version 2.0. You can freely decide which license you want to apply to the project."

Pingolin elects the Apache License, Version 2.0 for JNA and distributes it under those terms only. The LGPL option is not exercised. JNA is therefore listed under Apache License 2.0 in the inventory below rather than under both licences.

JNA is used solely as the FFI bridge for the UniFFI-generated bindings to the Pingolin mobile SDK. It is consumed unmodified, as a published binary artifact, and is not statically linked into or otherwise combined with Pingolin code.

## Summary

| Licence | Components |
| --- | ---: |
| Apache License 2.0 | 145 |
| Android Software Development Kit License | 8 |
| BSD 2-Clause License | 2 |
| BSD 3-Clause License | 2 |
| ISC License | 1 |
| Play Core Software Development Kit Terms of Service | 1 |
| Play Integrity API Terms of Service | 1 |

## Components

### Apache License 2.0

Licence text: <http://www.apache.org/licenses/LICENSE-2.0.txt>

- **Activity Kotlin Extensions** — `androidx.activity:activity-ktx:1.11.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/activity#1.11.0>
- **Activity** — `androidx.activity:activity:1.11.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/activity#1.11.0>
- **Experimental annotation** — `androidx.annotation:annotation-experimental:1.5.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/annotation#1.5.0>
- **Annotation** — `androidx.annotation:annotation:1.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/annotation#1.10.0>
- **AppCompat Resources** — `androidx.appcompat:appcompat-resources:1.7.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.1>
- **AppCompat** — `androidx.appcompat:appcompat:1.7.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.1>
- **Android Arch-Common** — `androidx.arch.core:core-common:2.2.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0>
- **Android Arch-Runtime** — `androidx.arch.core:core-runtime:2.2.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0>
- **Biometric** — `androidx.biometric:biometric:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/biometric#1.1.0>
- **Android Support CardView v7** — `androidx.cardview:cardview:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Collections Kotlin Extensions** — `androidx.collection:collection-ktx:1.5.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/collection#1.5.0>
- **collections** — `androidx.collection:collection:1.5.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/collection#1.5.0>
- **AndroidX Futures Kotlin Extensions** — `androidx.concurrent:concurrent-futures-ktx:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/topic/libraries/architecture/index.html>
- **AndroidX Futures** — `androidx.concurrent:concurrent-futures:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/topic/libraries/architecture/index.html>
- **ConstraintLayout Core** — `androidx.constraintlayout:constraintlayout-core:1.1.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/constraintlayout#1.1.1>
- **ConstraintLayout** — `androidx.constraintlayout:constraintlayout:2.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/constraintlayout#2.2.1>
- **Android Support Library Coordinator Layout** — `androidx.coordinatorlayout:coordinatorlayout:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **Core Kotlin Extensions** — `androidx.core:core-ktx:1.17.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/core#1.17.0>
- **SplashScreen** — `androidx.core:core-splashscreen:1.0.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/core#1.0.1>
- **androidx.core:core-viewtree** — `androidx.core:core-viewtree:1.0.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/core#1.0.0>
- **Core** — `androidx.core:core:1.17.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/core#1.17.0>
- **Android Support Library Cursor Adapter** — `androidx.cursoradapter:cursoradapter:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **androidx.customview:poolingcontainer** — `androidx.customview:customview-poolingcontainer:1.0.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/customview#1.0.0>
- **Android Support Library Custom View** — `androidx.customview:customview:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **viewbinding** — `androidx.databinding:viewbinding:9.3.1`
- **DataStore Core Okio** — `androidx.datastore:datastore-core-okio:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **DataStore Core** — `androidx.datastore:datastore-core:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **Preferences DataStore Core** — `androidx.datastore:datastore-preferences-core:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **Preferences DataStore Proto** — `androidx.datastore:datastore-preferences-proto:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **Preferences DataStore** — `androidx.datastore:datastore-preferences:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **DataStore** — `androidx.datastore:datastore:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **Android Support Library Document File** — `androidx.documentfile:documentfile:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Android Support Library Drawer Layout** — `androidx.drawerlayout:drawerlayout:1.1.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **DynamicAnimation** — `androidx.dynamicanimation:dynamicanimation:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/dynamicanimation#1.1.0>
- **Android Emoji2 Compat view helpers** — `androidx.emoji2:emoji2-views-helper:1.3.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/emoji2#1.3.0>
- **Android Emoji2 Compat** — `androidx.emoji2:emoji2:1.3.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/emoji2#1.3.0>
- **ExifInterface** — `androidx.exifinterface:exifinterface:1.4.2`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2>
- **Fragment Kotlin Extensions** — `androidx.fragment:fragment-ktx:1.8.9`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9>
- **fragment** — `androidx.fragment:fragment:1.8.9`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9>
- **Graphics Shapes** — `androidx.graphics:graphics-shapes:1.0.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1>
- **Hilt Common** — `androidx.hilt:hilt-common:1.3.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/hilt#1.3.0>
- **WorkManager Hilt Extension** — `androidx.hilt:hilt-work:1.3.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/hilt#1.3.0>
- **Android Support Library Interpolators** — `androidx.interpolator:interpolator:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Android Support Library core utils** — `androidx.legacy:legacy-support-core-utils:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Lifecycle-Common** — `androidx.lifecycle:lifecycle-common:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **LiveData Core Kotlin Extensions** — `androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle LiveData Core** — `androidx.lifecycle:lifecycle-livedata-core:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **LiveData Kotlin Extensions** — `androidx.lifecycle:lifecycle-livedata-ktx:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle LiveData** — `androidx.lifecycle:lifecycle-livedata:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle Process** — `androidx.lifecycle:lifecycle-process:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle Kotlin Extensions** — `androidx.lifecycle:lifecycle-runtime-ktx:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle Runtime** — `androidx.lifecycle:lifecycle-runtime:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle Service** — `androidx.lifecycle:lifecycle-service:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle ViewModel Kotlin Extensions** — `androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle ViewModel with SavedState** — `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Lifecycle ViewModel** — `androidx.lifecycle:lifecycle-viewmodel:2.10.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0>
- **Android Support Library loader** — `androidx.loader:loader:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Android Support Library Local Broadcast Manager** — `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Navigation Common** — `androidx.navigation:navigation-common:2.9.7`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7>
- **Navigation Fragment Kotlin Extensions** — `androidx.navigation:navigation-fragment-ktx:2.9.7`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7>
- **Navigation Fragment** — `androidx.navigation:navigation-fragment:2.9.7`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7>
- **Navigation Runtime** — `androidx.navigation:navigation-runtime:2.9.7`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7>
- **Navigation UI Kotlin Extensions** — `androidx.navigation:navigation-ui-ktx:2.9.7`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7>
- **Navigation UI** — `androidx.navigation:navigation-ui:2.9.7`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7>
- **Android Support Library Print** — `androidx.print:print:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Profile Installer** — `androidx.profileinstaller:profileinstaller:1.4.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/profileinstaller#1.4.1>
- **RecyclerView** — `androidx.recyclerview:recyclerview:1.4.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/recyclerview#1.4.0>
- **Android Resource Inspection - Annotations** — `androidx.resourceinspection:resourceinspection-annotation:1.0.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/resourceinspection#1.0.1>
- **Room-Common** — `androidx.room:room-common:2.8.4`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/room#2.8.4>
- **Room Kotlin Extensions** — `androidx.room:room-ktx:2.8.4`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/room#2.8.4>
- **Room-Runtime** — `androidx.room:room-runtime:2.8.4`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/room#2.8.4>
- **SavedState Kotlin Extensions** — `androidx.savedstate:savedstate-ktx:1.4.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0>
- **Saved State** — `androidx.savedstate:savedstate:1.4.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0>
- **Security** — `androidx.security:security-crypto:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/security#1.1.0>
- **Android Support Library Sliding Pane Layout** — `androidx.slidingpanelayout:slidingpanelayout:1.2.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/slidingpanelayout#1.2.0>
- **SQLite Framework Integration** — `androidx.sqlite:sqlite-framework:2.6.2`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2>
- **SQLite Kotlin Extensions** — `androidx.sqlite:sqlite-ktx:2.6.2`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2>
- **SQLite** — `androidx.sqlite:sqlite:2.6.2`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2>
- **Android App Startup Runtime** — `androidx.startup:startup-runtime:1.1.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/startup#1.1.1>
- **Startup Runtime** — `androidx.startup:startup-runtime:1.2.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/startup#1.2.0>
- **Android Support Library Custom View** — `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **Android Tracing Runtime Kotlin Extensions** — `androidx.tracing:tracing-ktx:1.2.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0>
- **Android Tracing** — `androidx.tracing:tracing:1.2.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0>
- **Transition** — `androidx.transition:transition:1.5.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/transition#1.5.0>
- **Android Support AnimatedVectorDrawable** — `androidx.vectordrawable:vectordrawable-animated:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **Android Support VectorDrawable** — `androidx.vectordrawable:vectordrawable:1.1.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **VersionedParcelable** — `androidx.versionedparcelable:versionedparcelable:1.1.1`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **AndroidX Widget ViewPager2** — `androidx.viewpager2:viewpager2:1.0.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx>
- **ViewPager2** — `androidx.viewpager2:viewpager2:1.1.0-beta02`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/viewpager2#1.1.0-beta02>
- **Android Support Library View Pager** — `androidx.viewpager:viewpager:1.0.0`
  Copyright The Android Open Source Project
  <http://developer.android.com/tools/extras/support-library.html>
- **Webkit** — `androidx.webkit:webkit:1.16.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/webkit#1.16.0>
- **Jetpack WindowManager Library** — `androidx.window:window:1.0.0`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/window#1.0.0>
- **WorkManager Kotlin Extensions** — `androidx.work:work-runtime-ktx:2.11.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/work#2.11.1>
- **WorkManager Runtime** — `androidx.work:work-runtime:2.11.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/work#2.11.1>
- **transport-api** — `com.google.android.datatransport:transport-api:3.1.0`
- **transport-backend-cct** — `com.google.android.datatransport:transport-backend-cct:3.1.9`
- **transport-runtime** — `com.google.android.datatransport:transport-runtime:3.1.9`
- **Material Components for Android** — `com.google.android.material:material:1.13.0`
  Copyright The Android Open Source Project
  <https://github.com/material-components/material-components-android>
- **FindBugs-jsr305** — `com.google.code.findbugs:jsr305:3.0.2`
  <http://findbugs.sourceforge.net/>
- **Gson** — `com.google.code.gson:gson:2.13.2`
- **Tink Cryptography API for Message Encryption for Web Push (RFC 8291)** — `com.google.crypto.tink:apps-webpush:1.11.0`
  <http://github.com/tink-crypto/tink-java-apps>
- **Tink Cryptography API for Android** — `com.google.crypto.tink:tink-android:1.8.0`
  <http://github.com/tink-crypto/tink-java>
- **Dagger Lint Rules AAR Distribution** — `com.google.dagger:dagger-lint-aar:2.60.1`
  <https://github.com/google/dagger>
- **Dagger** — `com.google.dagger:dagger:2.60.1`
  <https://github.com/google/dagger>
- **Hilt Android** — `com.google.dagger:hilt-android:2.60.1`
  <https://github.com/google/dagger>
- **Hilt Core** — `com.google.dagger:hilt-core:2.60.1`
  <https://github.com/google/dagger>
- **error-prone annotations** — `com.google.errorprone:error_prone_annotations:2.41.0`
- **firebase-annotations** — `com.google.firebase:firebase-annotations:17.0.0`
- **firebase-bom** — `com.google.firebase:firebase-bom:34.14.1`
- **firebase-common** — `com.google.firebase:firebase-common:22.0.1`
- **firebase-components** — `com.google.firebase:firebase-components:19.0.0`
- **firebase-datatransport** — `com.google.firebase:firebase-datatransport:18.2.0`
- **firebase-encoders-json** — `com.google.firebase:firebase-encoders-json:18.0.0`
- **firebase-encoders-proto** — `com.google.firebase:firebase-encoders-proto:16.0.0`
- **firebase-encoders** — `com.google.firebase:firebase-encoders:17.0.0`
- **firebase-installations-interop** — `com.google.firebase:firebase-installations-interop:17.3.0`
- **firebase-installations** — `com.google.firebase:firebase-installations:19.1.0`
- **firebase-messaging** — `com.google.firebase:firebase-messaging:25.0.2`
- **Guava ListenableFuture only** — `com.google.guava:listenablefuture:1.0`
- **Timber** — `com.jakewharton.timber:timber:5.0.1`
  Copyright Jake Wharton
  <https://github.com/JakeWharton/timber>
- **logging-interceptor** — `com.squareup.okhttp3:logging-interceptor:5.4.0`
  Copyright Square, Inc.
  <https://square.github.io/okhttp/>
- **okhttp** — `com.squareup.okhttp3:okhttp:5.4.0`
  Copyright Square, Inc.
  <https://square.github.io/okhttp/>
- **okio** — `com.squareup.okio:okio:3.17.0`
  Copyright Square, Inc.
  <https://github.com/square/okio/>
- **Converter: Gson** — `com.squareup.retrofit2:converter-gson:3.0.0`
  Copyright Square, Inc.
  <https://github.com/square/retrofit>
- **Retrofit** — `com.squareup.retrofit2:retrofit:3.0.0`
  Copyright Square, Inc.
  <https://github.com/square/retrofit>
- **coil-core** — `io.coil-kt.coil3:coil-core:3.4.0`
  Copyright Coil Contributors
  <https://github.com/coil-kt/coil>
- **coil** — `io.coil-kt.coil3:coil:3.4.0`
  Copyright Coil Contributors
  <https://github.com/coil-kt/coil>
- **Jakarta Dependency Injection** — `jakarta.inject:jakarta.inject-api:2.0.1`
  Copyright Antoine Sabot-Durand, Martin Kouba, Tomas Remes, Matej Novotny
  <https://github.com/eclipse-ee4j/injection-api>
- **javax.inject** — `javax.inject:javax.inject:1`
  <http://code.google.com/p/atinject/>
- **Java Native Access** — `net.java.dev.jna:jna:5.18.1`
  Copyright Timothy Wall, Matthias Bläsing
  <https://github.com/java-native-access/jna>
  _Dual-licensed; Apache License 2.0 elected by Pingolin — see "Licence elections" above._
- **Kotlin Libraries bill-of-materials** — `org.jetbrains.kotlin:kotlin-bom:1.8.22`
  Copyright JetBrains Team
  <https://kotlinlang.org/>
- **Parcelize Runtime** — `org.jetbrains.kotlin:kotlin-parcelize-runtime:2.3.10`
  Copyright Kotlin Team
  <https://kotlinlang.org/>
- **Kotlin Stdlib** — `org.jetbrains.kotlin:kotlin-stdlib:2.3.21`
  Copyright Kotlin Team
  <https://kotlinlang.org/>
- **kotlinx-coroutines-android** — `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.coroutines>
- **kotlinx-coroutines-bom** — `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.coroutines>
- **kotlinx-coroutines-core** — `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.coroutines>
- **kotlinx-coroutines-play-services** — `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.coroutines>
- **kotlinx-serialization-bom** — `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.serialization>
- **kotlinx-serialization-core** — `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.serialization>
- **kotlinx-serialization-json** — `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`
  Copyright JetBrains Team
  <https://github.com/Kotlin/kotlinx.serialization>
- **JetBrains Java Annotations** — `org.jetbrains:annotations:23.0.0`
  Copyright JetBrains Team
  <https://github.com/JetBrains/java-annotations>
- **JSpecify annotations** — `org.jspecify:jspecify:1.0.0`
  Copyright Kevin Bourrillion
  <http://jspecify.org/>
- **MapLibre Android Java Utilities** — `org.maplibre.gl:android-sdk-geojson:6.0.1`
  Copyright MapLibre
  <https://github.com/maplibre/maplibre-java>
- **MapLibre Android Java Utilities** — `org.maplibre.gl:android-sdk-turf:6.0.1`
  Copyright MapLibre
  <https://github.com/maplibre/maplibre-java>
- **org.unifiedpush.android:connector** — `org.unifiedpush.android:connector:3.0.3`
  Copyright S1m
  <https://unifiedpush.org/developers/android/>

### Android Software Development Kit License

Licence text: <https://developer.android.com/studio/terms.html>

- **play-services-base** — `com.google.android.gms:play-services-base:18.9.0`
- **play-services-basement** — `com.google.android.gms:play-services-basement:18.9.0`
- **play-services-cloud-messaging** — `com.google.android.gms:play-services-cloud-messaging:17.2.0`
- **play-services-location** — `com.google.android.gms:play-services-location:21.3.0`
- **play-services-stats** — `com.google.android.gms:play-services-stats:17.0.2`
- **play-services-tasks** — `com.google.android.gms:play-services-tasks:18.4.0`
- **firebase-iid-interop** — `com.google.firebase:firebase-iid-interop:17.1.0`
- **firebase-measurement-connector** — `com.google.firebase:firebase-measurement-connector:19.0.0`

### BSD 2-Clause License

Licence text: <https://opensource.org/licenses/BSD-2-Clause>

- **MapLibre Android** — `org.maplibre.gl:android-sdk:11.11.0`
  Copyright MapLibre
  <https://github.com/maplibre/maplibre-native>
  _The POM records the licence name as the bare string 'BSD' with the URL https://opensource.org/licenses/BSD-2-Clause; it is reported here under the two-clause name the URL identifies._
- **MapLibre Android Gestures Library** — `org.maplibre.gl:maplibre-android-gestures:0.0.4`
  Copyright MapLibre
  <https://github.com/maplibre/maplibre-gestures-android>

### BSD 3-Clause License

Licence text: <https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE>

- **Preferences External Protobuf** — `androidx.datastore:datastore-preferences-external-protobuf:1.2.1`
  Copyright The Android Open Source Project
  <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1>
- **sqlcipher-android** — `net.zetetic:sqlcipher-android:4.9.0`
  Copyright Zetetic Support
  <https://www.zetetic.net/sqlcipher>
  _Licence added by hand: The published POM declares a <license> element with a <url> but no <name>, so the POM scan yields a blank licence. Filled in from the project's own LICENSE file, which is a three-clause BSD licence: 'Copyright (c) 2008-2023, ZETETIC LLC' with the redistribution, binary-reproduction and non-endorsement clauses. SQLCipher Community Edition. Zetetic's licence overview is at https://www.zetetic.net/sqlcipher/license/ — the URL the POM points at. Consumed unmodified as a published binary artifact._

### ISC License

Licence text: <https://opensource.org/licenses/isc-license>

- **jBCrypt** — `org.mindrot:jbcrypt:0.4`
  Copyright Damien Miller
  <https://github.com/djmdjm/jBCrypt>

### Play Core Software Development Kit Terms of Service

Licence text: <https://developer.android.com/guide/playcore/license>

- **core-common** — `com.google.android.play:core-common:2.0.4`

### Play Integrity API Terms of Service

Licence text: <https://developer.android.com/google/play/integrity/overview#tos>

- **integrity** — `com.google.android.play:integrity:1.4.0`

## Native (Rust) components

Statically linked into `libpingolin_mobile.so`, built from `crates/mobile-sdk` in the mobile-sdk repository.

The components above are the Java and Android dependencies, taken from POM metadata. They are not the whole picture: libpingolin_mobile.so is a prebuilt native library with third-party Rust crates statically linked into it, and no POM describes those. They are inventoried separately, from the Rust dependency graph, and listed here.

ABIs covered: arm64-v8a (aarch64-linux-android), armeabi-v7a (armv7-linux-androideabi), x86_64 (x86_64-linux-android).

Normal dependency closure of crates/mobile-sdk, unioned across the ABIs above. Dev-dependencies, build-dependencies and proc-macro crates are excluded: they run at build time and leave no code in the shared library. Pingolin's own crates are excluded; they are covered by LICENSE.

Licences are reproduced as each crate publishes them, as SPDX expressions. "OR" marks a choice of terms offered by the author; every such choice here is between permissive licences whose conditions Pingolin satisfies alike, by reproducing this notice. "AND" means both sets of terms apply.

244 crates, under 25 distinct licence expressions.

### MIT OR Apache-2.0

- **aead** 0.5.2
  Traits for Authenticated Encryption with Associated Data (AEAD) algorithms,
such as AES-GCM as ChaCha20Poly1305, which provide a high-level API
  <https://github.com/RustCrypto/traits>
- **ahash** 0.8.12
  A non-cryptographic hash function using AES-NI for high performance
  <https://github.com/tkaitchuck/ahash>
- **android_log-sys** 0.2.0
  FFI bindings to Android log Library.
  <https://github.com/nercury/android_log-sys-rs>
- **android_log-sys** 0.3.2
  FFI bindings to Android log Library.
  <https://github.com/rust-mobile/android_log-sys-rs>
- **android_logger** 0.14.1
  A logging implementation for `log` which hooks to android log output.
  <https://github.com/rust-mobile/android_logger-rs>
- **anyhow** 1.0.103
  Flexible concrete Error type built on std::error::Error
  <https://github.com/dtolnay/anyhow>
- **argon2** 0.5.3
  Pure Rust implementation of the Argon2 password hashing function with support
for the Argon2d, Argon2i, and Argon2id algorithmic variants
  <https://github.com/RustCrypto/password-hashes/tree/master/argon2>
- **arrayvec** 0.7.7
  A vector with fixed capacity, backed by an array (it can be stored on the stack too). Implements fixed capacity ArrayVec and ArrayString.
  <https://github.com/bluss/arrayvec>
- **askama** 0.13.1
  Type-safe, compiled Jinja-like templates for Rust
  <https://github.com/askama-rs/askama>
- **async-compression** 0.4.42
  Adaptors between compression crates and Rust's modern asynchronous IO types.
  <https://github.com/Nullus157/async-compression>
- **base64** 0.22.1
  encodes and decodes base64 as bytes or utf8
  <https://github.com/marshallpierce/rust-base64>
- **bitflags** 2.13.0
  A macro to generate structures which behave like bitflags.
  <https://github.com/bitflags/bitflags>
- **blake2** 0.10.6
  BLAKE2 hash functions
  <https://github.com/RustCrypto/hashes>
- **block-buffer** 0.10.4
  Buffer type for block processing of data
  <https://github.com/RustCrypto/utils>
- **blowfish** 0.9.1
  Blowfish block cipher
  <https://github.com/RustCrypto/block-ciphers>
- **camino** 1.2.3
  UTF-8 paths
  <https://github.com/camino-rs/camino>
- **cargo-platform** 0.1.9
  Cargo's representation of a target platform.
  <https://github.com/rust-lang/cargo>
- **cfg-if** 1.0.4
  A macro to ergonomically define an item depending on a large number of #[cfg]
parameters. Structured like an if-else chain, the first matching branch is the
item that gets emitted.
  <https://github.com/rust-lang/cfg-if>
- **chrono** 0.4.45
  Date and time library for Rust
  <https://github.com/chronotope/chrono>
- **cipher** 0.4.4
  Traits for describing block ciphers and stream ciphers
  <https://github.com/RustCrypto/traits>
- **compression-codecs** 0.4.38
  Adaptors for various compression algorithms.
  <https://github.com/Nullus157/async-compression>
- **compression-core** 0.4.32
  Abstractions for compression algorithms.
  <https://github.com/Nullus157/async-compression>
- **cpufeatures** 0.2.17
  Lightweight runtime CPU feature detection for aarch64, loongarch64, and x86/x86_64 targets, 
with no_std support and support for mobile targets including Android and iOS
  <https://github.com/RustCrypto/utils>
  Only in: arm64-v8a, x86_64
- **cpufeatures** 0.3.0
  Lightweight runtime CPU feature detection for aarch64, loongarch64, and x86/x86_64 targets,
with no_std support and support for mobile targets including Android and iOS
  <https://github.com/RustCrypto/utils>
  Only in: x86_64
- **crc32fast** 1.5.0
  Fast, SIMD-accelerated CRC32 (IEEE) checksum computation
  <https://github.com/srijs/rust-crc32fast>
- **crypto-common** 0.1.7
  Common cryptographic traits
  <https://github.com/RustCrypto/traits>
- **digest** 0.10.7
  Traits for cryptographic hash functions and message authentication codes
  <https://github.com/RustCrypto/traits>
- **env_filter** 0.1.4
  Filter log events using environment variables
  <https://github.com/rust-cli/env_logger>
- **errno** 0.3.14
  Cross-platform interface to the `errno` variable.
  <https://github.com/lambda-fairy/rust-errno>
- **flate2** 1.1.9
  DEFLATE compression and decompression exposed as Read/BufRead/Write streams.
Supports miniz_oxide and multiple zlib implementations. Supports zlib, gzip,
and raw deflate streams.
  <https://github.com/rust-lang/flate2-rs>
- **form_urlencoded** 1.2.2
  Parser and serializer for the application/x-www-form-urlencoded syntax, as used by HTML forms.
  <https://github.com/servo/rust-url>
- **futures** 0.3.32
  An implementation of futures and streams featuring zero allocations,
composability, and iterator-like interfaces.
  <https://github.com/rust-lang/futures-rs>
- **futures-channel** 0.3.32
  Channels for asynchronous communication using futures-rs.
  <https://github.com/rust-lang/futures-rs>
- **futures-core** 0.3.32
  The core traits and types in for the `futures` library.
  <https://github.com/rust-lang/futures-rs>
- **futures-executor** 0.3.32
  Executors for asynchronous tasks based on the futures-rs library.
  <https://github.com/rust-lang/futures-rs>
- **futures-io** 0.3.32
  The `AsyncRead`, `AsyncWrite`, `AsyncSeek`, and `AsyncBufRead` traits for the futures-rs library.
  <https://github.com/rust-lang/futures-rs>
- **futures-sink** 0.3.32
  The asynchronous `Sink` trait for the futures-rs library.
  <https://github.com/rust-lang/futures-rs>
- **futures-task** 0.3.32
  Tools for working with tasks.
  <https://github.com/rust-lang/futures-rs>
- **futures-util** 0.3.32
  Common utilities and extension traits for the futures-rs library.
  <https://github.com/rust-lang/futures-rs>
- **getrandom** 0.2.17
  A small cross-platform library for retrieving random data from system source
  <https://github.com/rust-random/getrandom>
- **getrandom** 0.3.4
  A small cross-platform library for retrieving random data from system source
  <https://github.com/rust-random/getrandom>
- **getrandom** 0.4.3
  A small cross-platform library for retrieving random data from system source
  <https://github.com/rust-random/getrandom>
- **glob** 0.3.3
  Support for matching file paths against Unix shell style patterns.
  <https://github.com/rust-lang/glob>
- **hashbrown** 0.14.5
  A Rust port of Google's SwissTable hash map
  <https://github.com/rust-lang/hashbrown>
- **hashbrown** 0.17.1
  A Rust port of Google's SwissTable hash map
  <https://github.com/rust-lang/hashbrown>
- **hashlink** 0.9.1
  HashMap-like containers that hold their key-value pairs in a user controllable order
  <https://github.com/kyren/hashlink>
- **heck** 0.5.0
  heck is a case conversion library.
  <https://github.com/withoutboats/heck>
- **hex** 0.4.3
  Encoding and decoding data into/from hexadecimal representation.
  <https://github.com/KokaKiwi/rust-hex>
- **http** 1.4.2
  A set of types for representing HTTP requests and responses.
  <https://github.com/hyperium/http>
- **httparse** 1.10.1
  A tiny, safe, speedy, zero-copy HTTP/1.x parser.
  <https://github.com/seanmonstar/httparse>
- **httpdate** 1.0.3
  HTTP date parsing and formatting
  <https://github.com/pyfisch/httpdate>
- **hyper-timeout** 0.5.2
  A connect, read and write timeout aware connector to be used with hyper Client.
  <https://github.com/hjr3/hyper-timeout>
- **iana-time-zone** 0.1.65
  get the IANA time zone for the current system
  <https://github.com/strawlab/iana-time-zone>
- **idna** 1.1.0
  IDNA (Internationalizing Domain Names in Applications) and Punycode.
  <https://github.com/servo/rust-url/>
- **inout** 0.1.4
  Custom reference types for code generic over in-place and buffer-to-buffer modes of operation.
  <https://github.com/RustCrypto/utils>
- **ipnet** 2.12.0
  Provides types and useful methods for working with IPv4 and IPv6 network addresses, commonly called IP prefixes. The new `IpNet`, `Ipv4Net`, and `Ipv6Net` types build on the existing `IpAddr`, `Ipv4Addr`, and `Ipv6Addr` types already provided in Rust's standard library and align to their design to stay consistent. The module also provides useful traits that extend `Ipv4Addr` and `Ipv6Addr` with methods for `Add`, `Sub`, `BitAnd`, and `BitOr` operations. The module only uses stable feature so it is guaranteed to compile using the stable toolchain.
  <https://github.com/krisprice/ipnet>
- **itoa** 1.0.18
  Fast integer primitive to string conversion
  <https://github.com/dtolnay/itoa>
- **lazy_static** 1.5.0
  A macro for declaring lazily evaluated statics in Rust.
  <https://github.com/rust-lang-nursery/lazy-static.rs>
- **libc** 0.2.186
  Raw FFI bindings to platform libraries like libc.
  <https://github.com/rust-lang/libc>
- **log** 0.4.33
  A lightweight logging facade for Rust
  <https://github.com/rust-lang/log>
- **mime** 0.3.17
  Strongly Typed Mimes
  <https://github.com/hyperium/mime>
- **num-bigint** 0.4.6
  Big integer implementation for Rust
  <https://github.com/rust-num/num-bigint>
- **num-integer** 0.1.46
  Integer traits and functions
  <https://github.com/rust-num/num-integer>
- **num-traits** 0.2.19
  Numeric traits for generic mathematics
  <https://github.com/rust-num/num-traits>
- **num_cpus** 1.17.0
  Get the number of CPUs on a machine.
  <https://github.com/seanmonstar/num_cpus>
- **once_cell** 1.21.4
  Single assignment cells and lazy values.
  <https://github.com/matklad/once_cell>
- **opaque-debug** 0.3.1
  Macro for opaque Debug trait implementation
  <https://github.com/RustCrypto/utils>
- **openssl-probe** 0.2.1
  A library for helping to find system-wide trust anchor ("root") certificate
locations based on paths typically used by `openssl`.
  <https://github.com/rustls/openssl-probe>
- **password-hash** 0.5.0
  Traits which describe the functionality of password hashing algorithms,
as well as a `no_std`-friendly implementation of the PHC string format
(a well-defined subset of the Modular Crypt Format a.k.a. MCF)
  <https://github.com/RustCrypto/traits/tree/master/password-hash>
- **percent-encoding** 2.3.2
  Percent encoding and decoding
  <https://github.com/servo/rust-url/>
- **ppv-lite86** 0.2.21
  Cross-platform cryptography-oriented low-level SIMD library.
  <https://github.com/cryptocorrosion/cryptocorrosion>
- **quinn** 0.11.11
  Versatile QUIC transport protocol implementation
  <https://github.com/quinn-rs/quinn>
- **quinn-proto** 0.11.15
  State machine for the QUIC transport protocol
  <https://github.com/quinn-rs/quinn>
- **quinn-udp** 0.5.14
  UDP sockets with ECN information for the QUIC transport protocol
  <https://github.com/quinn-rs/quinn>
- **rand** 0.8.6
  Random number generators and other randomness functionality.
  <https://github.com/rust-random/rand>
- **rand** 0.9.4
  Random number generators and other randomness functionality.
  <https://github.com/rust-random/rand>
- **rand_chacha** 0.3.1
  ChaCha random number generator
  <https://github.com/rust-random/rand>
- **rand_chacha** 0.9.0
  ChaCha random number generator
  <https://github.com/rust-random/rand>
- **rand_core** 0.6.4
  Core random number generator traits and tools for implementation.
  <https://github.com/rust-random/rand>
- **rand_core** 0.9.5
  Core random number generator traits and tools for implementation.
  <https://github.com/rust-random/rand>
- **regex** 1.12.4
  An implementation of regular expressions for Rust. This implementation uses
finite automata and guarantees linear time matching on all inputs.
  <https://github.com/rust-lang/regex>
- **regex-automata** 0.4.14
  Automata construction and matching using regular expressions.
  <https://github.com/rust-lang/regex>
- **regex-syntax** 0.8.11
  A regular expression parser.
  <https://github.com/rust-lang/regex>
- **reqwest** 0.12.28
  higher level HTTP client library
  <https://github.com/seanmonstar/reqwest>
- **rustls-pki-types** 1.14.1
  Shared types for the rustls PKI ecosystem
  <https://github.com/rustls/pki-types>
- **salsa20** 0.10.2
  Salsa20 Stream Cipher
  <https://github.com/RustCrypto/stream-ciphers>
- **semver** 1.0.28
  Parser and evaluator for Cargo's flavor of Semantic Versioning
  <https://github.com/dtolnay/semver>
- **serde** 1.0.228
  A generic serialization/deserialization framework
  <https://github.com/serde-rs/serde>
- **serde_core** 1.0.228
  Serde traits only, with no support for derive -- use the `serde` crate instead
  <https://github.com/serde-rs/serde>
- **serde_json** 1.0.150
  A JSON serialization file format
  <https://github.com/serde-rs/json>
- **serde_spanned** 1.1.1
  Serde-compatible spanned Value
  <https://github.com/toml-rs/toml>
- **sha2** 0.10.9
  Pure Rust implementation of the SHA-2 hash function family
including SHA-224, SHA-256, SHA-384, and SHA-512.
  <https://github.com/RustCrypto/hashes>
- **smallvec** 1.15.2
  'Small vector' optimization: store up to a small number of items on the stack
  <https://github.com/servo/rust-smallvec>
- **socket2** 0.6.4
  Utilities for handling networking sockets with a maximal amount of configuration
possible intended.
  <https://github.com/rust-lang/socket2>
- **srp** 0.6.0
  Secure Remote Password (SRP) protocol implementation
  <https://github.com/RustCrypto/PAKEs>
- **stable_deref_trait** 1.2.1
  An unsafe marker trait for types like Box and Rc that dereference to a stable address even when moved, and hence can be used with libraries such as owning_ref and rental.
  <https://github.com/storyyeller/stable_deref_trait>
- **static_assertions** 1.1.0
  Compile-time assertions to ensure that invariants are met.
  <https://github.com/nvzqz/static-assertions-rs>
- **tempfile** 3.27.0
  A library for managing temporary files and directories.
  <https://github.com/Stebalien/tempfile>
- **thiserror** 2.0.18
  derive(Error)
  <https://github.com/dtolnay/thiserror>
- **thread_local** 1.1.9
  Per-object thread-local storage
  <https://github.com/Amanieu/thread_local-rs>
- **tokio-rustls** 0.26.4
  Asynchronous TLS/SSL streams for Tokio using Rustls.
  <https://github.com/rustls/tokio-rustls>
- **toml** 0.9.12+spec-1.1.0
  A native Rust encoder and decoder of TOML-formatted files and streams. Provides
implementations of the standard Serialize/Deserialize traits for TOML data to
facilitate deserializing and serializing Rust structures.
  <https://github.com/toml-rs/toml>
- **toml_datetime** 0.7.5+spec-1.1.0
  A TOML-compatible datetime type
  <https://github.com/toml-rs/toml>
- **toml_parser** 1.1.2+spec-1.1.0
  Yet another format-preserving TOML parser.
  <https://github.com/toml-rs/toml>
- **toml_writer** 1.1.1+spec-1.1.0
  A low-level interface for writing out TOML
  <https://github.com/toml-rs/toml>
- **tracing-android** 0.2.0
  Tracing layer to log to Android's logcat
  <https://github.com/Actyx/tracing-android>
- **typenum** 1.20.1
  Typenum is a Rust library for type-level numbers evaluated at
    compile time. It currently supports bits, unsigned integers, and signed
    integers. It also provides a type-level array of type-level numbers, but its
    implementation is incomplete.
  <https://github.com/paholg/typenum>
- **universal-hash** 0.5.1
  Traits which describe the functionality of universal hash functions (UHFs)
  <https://github.com/RustCrypto/traits>
- **url** 2.5.8
  URL library for Rust, based on the WHATWG URL Standard
  <https://github.com/servo/rust-url>
- **zstd-safe** 7.2.4
  Safe low-level bindings for the zstd compression library.
  <https://github.com/gyscos/zstd-rs>

### MIT

- **async-stream** 0.3.6
  Asynchronous streams using async & await notation
  <https://github.com/tokio-rs/async-stream>
- **axum** 0.8.9
  HTTP routing and request handling library that focuses on ergonomics and modularity
  <https://github.com/tokio-rs/axum>
- **axum-core** 0.5.6
  Core types and traits for axum
  <https://github.com/tokio-rs/axum>
- **bcrypt** 0.16.0
  Easily hash and verify passwords using bcrypt
  <https://github.com/Keats/rust-bcrypt>
- **bytes** 1.12.0
  Types and traits for working with bytes
  <https://github.com/tokio-rs/bytes>
- **cargo_metadata** 0.19.2
  structured access to the output of `cargo metadata`
  <https://github.com/oli-obk/cargo_metadata>
- **generic-array** 0.14.7
  Generic types implementing functionality of arrays
  <https://github.com/fizyk20/generic-array.git>
- **goblin** 0.8.2
  An impish, cross-platform, ELF, Mach-o, and PE binary parsing and loading crate
  <https://github.com/m4b/goblin>
- **h2** 0.4.15
  An HTTP/2 client and server
  <https://github.com/hyperium/h2>
- **http-body** 1.0.1
  Trait representing an asynchronous, streaming, HTTP request or response body.
  <https://github.com/hyperium/http-body>
- **http-body-util** 0.1.3
  Combinators and adapters for HTTP request or response bodies.
  <https://github.com/hyperium/http-body>
- **hyper** 1.10.1
  A protective and efficient HTTP library for all.
  <https://github.com/hyperium/hyper>
- **hyper-util** 0.1.20
  hyper utilities
  <https://github.com/hyperium/hyper-util>
- **libsqlite3-sys** 0.28.0
  Native bindings to the libsqlite3 library
  <https://github.com/rusqlite/rusqlite>
  _Built with the `bundled` feature, so this crate compiles the SQLite C amalgamation into libpingolin_mobile.so. The crate's own MIT licence covers the Rust binding; the bundled SQLite source is separately dedicated to the public domain by its authors (https://sqlite.org/copyright.html), which imposes no attribution condition. Note that `bundled` is plain SQLite — NOT `bundled-sqlcipher`; the SQLCipher terms listed elsewhere in these notices apply to the Android net.zetetic:sqlcipher-android artifact, not to this library._
- **mio** 1.2.1
  Lightweight non-blocking I/O.
  <https://github.com/tokio-rs/mio>
- **nom** 7.1.3
  A byte-oriented, zero-copy, parser combinators library
  <https://github.com/Geal/nom>
- **nu-ansi-term** 0.50.3
  Library for ANSI terminal colors and styles (bold, underline)
  <https://github.com/nushell/nu-ansi-term>
- **rusqlite** 0.31.0
  Ergonomic wrapper for SQLite
  <https://github.com/rusqlite/rusqlite>
- **scroll** 0.12.0
  A suite of powerful, extensible, generic, endian-aware Read/Write traits for byte buffers
  <https://github.com/m4b/scroll>
- **sharded-slab** 0.1.7
  A lock-free concurrent slab.
  <https://github.com/hawkw/sharded-slab>
- **simd-adler32** 0.3.9
  A SIMD-accelerated Adler-32 hash algorithm implementation.
  <https://github.com/mcountryman/simd-adler32>
- **slab** 0.4.12
  Pre-allocated storage for a uniform data type
  <https://github.com/tokio-rs/slab>
- **smawk** 0.3.3
  Functions for finding row-minima in a totally monotone matrix.
  <https://github.com/mgeisler/smawk>
- **textwrap** 0.16.2
  Library for word wrapping, indenting, and dedenting strings. Has optional support for Unicode and emojis as well as machine hyphenation.
  <https://github.com/mgeisler/textwrap>
- **tokio** 1.52.3
  An event-driven, non-blocking I/O platform for writing asynchronous I/O
backed applications.
  <https://github.com/tokio-rs/tokio>
- **tokio-stream** 0.1.18
  Utilities to work with `Stream` and `tokio`.
  <https://github.com/tokio-rs/tokio>
- **tokio-util** 0.7.18
  Additional utilities for working with Tokio.
  <https://github.com/tokio-rs/tokio>
- **tonic** 0.14.6
  A gRPC over HTTP/2 implementation focused on high performance, interoperability, and flexibility.
  <https://github.com/hyperium/tonic>
- **tonic-prost** 0.14.6
  Prost codec implementation for tonic
  <https://github.com/hyperium/tonic>
- **tower** 0.5.3
  Tower is a library of modular and reusable components for building robust
clients and servers.
  <https://github.com/tower-rs/tower>
- **tower-http** 0.6.11
  Tower middleware and utilities for HTTP clients and servers
  <https://github.com/tower-rs/tower-http>
- **tower-layer** 0.3.3
  Decorates a `Service` to allow easy composition between `Service`s.
  <https://github.com/tower-rs/tower>
- **tower-service** 0.3.3
  Trait representing an asynchronous, request / response based, client or server.
  <https://github.com/tower-rs/tower>
- **tracing** 0.1.44
  Application-level tracing for Rust.
  <https://github.com/tokio-rs/tracing>
- **tracing-core** 0.1.36
  Core primitives for application-level tracing.
  <https://github.com/tokio-rs/tracing>
- **tracing-log** 0.2.0
  Provides compatibility between `tracing` and the `log` crate.
  <https://github.com/tokio-rs/tracing>
- **tracing-subscriber** 0.3.23
  Utilities for implementing and composing `tracing` subscribers.
  <https://github.com/tokio-rs/tracing>
- **try-lock** 0.2.5
  A lightweight atomic lock.
  <https://github.com/seanmonstar/try-lock>
- **voprf** 0.5.0
  An implementation of a verifiable oblivious pseudorandom function (VOPRF)
  <https://github.com/facebook/voprf/>
- **want** 0.3.1
  Detect when another Future wants a result.
  <https://github.com/seanmonstar/want>
- **weedle2** 5.0.0
  A WebIDL Parser
  <https://github.com/mozilla/uniffi-rs>
- **winnow** 0.7.15
  A byte-oriented, zero-copy, parser combinators library
  <https://github.com/winnow-rs/winnow>
- **winnow** 1.0.3
  A byte-oriented, zero-copy, parser combinators library
  <https://github.com/winnow-rs/winnow>
- **zmij** 1.0.21
  A double-to-string conversion algorithm based on Schubfach and yy
  <https://github.com/dtolnay/zmij>
- **zstd** 0.13.3
  Binding for the zstd compression library.
  <https://github.com/gyscos/zstd-rs>

### Apache-2.0 OR MIT

- **atomic-waker** 1.1.2
  A synchronization primitive for task wakeup
  <https://github.com/smol-rs/atomic-waker>
- **base16ct** 0.2.0
  Pure Rust implementation of Base16 a.k.a hexadecimal (RFC 4648) which avoids
any usages of data-dependent branches/LUTs and thereby provides portable
"best effort" constant-time operation and embedded-friendly no_std support
  <https://github.com/RustCrypto/formats/tree/master/base16ct>
- **base64ct** 1.8.3
  Pure Rust implementation of Base64 (RFC 4648) which avoids any usages of
data-dependent branches/LUTs and thereby provides portable "best effort"
constant-time operation and embedded-friendly no_std support
  <https://github.com/RustCrypto/formats>
- **chacha20** 0.9.1
  The ChaCha20 stream cipher (RFC 8439) implemented in pure Rust using traits
from the RustCrypto `cipher` crate, with optional architecture-specific
hardware acceleration (AVX2, SSE2). Additionally provides the ChaCha8, ChaCha12,
XChaCha20, XChaCha12 and XChaCha8 stream ciphers, and also optional
rand_core-compatible RNGs based on those ciphers.
  <https://github.com/RustCrypto/stream-ciphers>
- **chacha20poly1305** 0.10.1
  Pure Rust implementation of the ChaCha20Poly1305 Authenticated Encryption
with Additional Data Cipher (RFC 8439) with optional architecture-specific
hardware acceleration. Also contains implementations of the XChaCha20Poly1305
extended nonce variant of ChaCha20Poly1305, and the reduced-round
ChaCha8Poly1305 and ChaCha12Poly1305 lightweight variants.
  <https://github.com/RustCrypto/AEADs/tree/master/chacha20poly1305>
- **const-oid** 0.9.6
  Const-friendly implementation of the ISO/IEC Object Identifier (OID) standard
as defined in ITU X.660, with support for BER/DER encoding/decoding as well as
heapless no_std (i.e. embedded) support
  <https://github.com/RustCrypto/formats/tree/master/const-oid>
- **crypto-bigint** 0.5.5
  Pure Rust implementation of a big integer library which has been designed from
the ground-up for use in cryptographic applications. Provides constant-time,
no_std-friendly implementations of modern formulas using const generics.
  <https://github.com/RustCrypto/crypto-bigint>
- **crypto_secretbox** 0.1.1
  Pure Rust implementation of the XSalsa20Poly1305 (a.k.a. NaCl crypto_secretbox)
authenticated encryption cipher as well as the libsodium variant of
XChaCha20Poly1305
  <https://github.com/RustCrypto/nacl-compat/tree/master/crypto_secretbox>
- **der** 0.7.10
  Pure Rust embedded-friendly implementation of the Distinguished Encoding Rules
(DER) for Abstract Syntax Notation One (ASN.1) as described in ITU X.690 with
full support for heapless no_std targets
  <https://github.com/RustCrypto/formats/tree/master/der>
- **ed25519** 2.2.3
  Edwards Digital Signature Algorithm (EdDSA) over Curve25519 (as specified in RFC 8032)
support library providing signature type definitions and PKCS#8 private key
decoding/encoding support
  <https://github.com/RustCrypto/signatures/tree/master/ed25519>
- **elliptic-curve** 0.13.8
  General purpose Elliptic Curve Cryptography (ECC) support, including types
and traits for representing various elliptic curve forms, scalars, points,
and public/secret keys composed thereof.
  <https://github.com/RustCrypto/traits/tree/master/elliptic-curve>
- **equivalent** 1.0.2
  Traits for key comparison in maps.
  <https://github.com/indexmap-rs/equivalent>
- **fastrand** 2.4.1
  A simple and fast random number generator
  <https://github.com/smol-rs/fastrand>
- **idna_adapter** 1.2.2
  Back end adapter for idna
  <https://github.com/hsivonen/idna_adapter>
- **indexmap** 2.14.0
  A hash table with consistent order and fast iteration.
  <https://github.com/indexmap-rs/indexmap>
- **pin-project** 1.1.13
  A crate for safe and ergonomic pin-projection.
  <https://github.com/taiki-e/pin-project>
- **pin-project-lite** 0.2.17
  A lightweight version of pin-project written with declarative macros.
  <https://github.com/taiki-e/pin-project-lite>
- **pkcs8** 0.10.2
  Pure Rust implementation of Public-Key Cryptography Standards (PKCS) #8:
Private-Key Information Syntax Specification (RFC 5208), with additional
support for PKCS#8v2 asymmetric key packages (RFC 5958)
  <https://github.com/RustCrypto/formats/tree/master/pkcs8>
- **poly1305** 0.8.0
  The Poly1305 universal hash function and message authentication code
  <https://github.com/RustCrypto/universal-hashes>
- **rustc-hash** 2.1.2
  A speedy, non-cryptographic hashing algorithm used by rustc
  <https://github.com/rust-lang/rustc-hash>
- **sec1** 0.7.3
  Pure Rust implementation of SEC1: Elliptic Curve Cryptography encoding formats
including ASN.1 DER-serialized private keys as well as the
Elliptic-Curve-Point-to-Octet-String encoding
  <https://github.com/RustCrypto/formats/tree/master/sec1>
- **signature** 2.2.0
  Traits for cryptographic signature algorithms (e.g. ECDSA, Ed25519)
  <https://github.com/RustCrypto/traits/tree/master/signature>
- **spki** 0.7.3
  X.509 Subject Public Key Info (RFC5280) describing public keys as well as their
associated AlgorithmIdentifiers (i.e. OIDs)
  <https://github.com/RustCrypto/formats/tree/master/spki>
- **utf8_iter** 1.0.4
  Iterator by char over potentially-invalid UTF-8 in &[u8]
  <https://github.com/hsivonen/utf8_iter>
- **uuid** 1.23.4
  A library to generate and parse UUIDs.
  <https://github.com/uuid-rs/uuid>
- **zeroize** 1.9.0
  Securely clear secrets from memory with a simple trait built on
stable Rust primitives which guarantee memory is zeroed using an
operation will not be 'optimized away' by the compiler.
Uses a portable pure Rust implementation that works everywhere,
even WASM!
  <https://github.com/RustCrypto/utils>

### Unicode-3.0

- **icu_collections** 2.2.0
  Collection of API for use in ICU libraries.
  <https://github.com/unicode-org/icu4x>
- **icu_locale_core** 2.2.0
  API for managing Unicode Language and Locale Identifiers
  <https://github.com/unicode-org/icu4x>
- **icu_normalizer** 2.2.0
  API for normalizing text into Unicode Normalization Forms
  <https://github.com/unicode-org/icu4x>
- **icu_normalizer_data** 2.2.0
  Data for the icu_normalizer crate
  <https://github.com/unicode-org/icu4x>
- **icu_properties** 2.2.0
  Definitions for Unicode properties
  <https://github.com/unicode-org/icu4x>
- **icu_properties_data** 2.2.0
  Data for the icu_properties crate
  <https://github.com/unicode-org/icu4x>
- **icu_provider** 2.2.0
  Trait and struct definitions for the ICU data provider
  <https://github.com/unicode-org/icu4x>
- **litemap** 0.8.2
  A key-value Map implementation based on a flat, sorted Vec.
  <https://github.com/unicode-org/icu4x>
- **potential_utf** 0.1.5
  Unvalidated string and character types
  <https://github.com/unicode-org/icu4x>
- **tinystr** 0.8.3
  A small ASCII-only bounded length string representation.
  <https://github.com/unicode-org/icu4x>
- **writeable** 0.6.3
  A more efficient alternative to fmt::Display
  <https://github.com/unicode-org/icu4x>
- **yoke** 0.8.3
  Abstraction allowing borrowed data to be carried along with the backing data it borrows from
  <https://github.com/unicode-org/icu4x>
- **zerofrom** 0.1.8
  ZeroFrom trait for constructing
  <https://github.com/unicode-org/icu4x>
- **zerotrie** 0.2.4
  A data structure that efficiently maps strings to integers
  <https://github.com/unicode-org/icu4x>
- **zerovec** 0.11.6
  Zero-copy vector backed by a byte array
  <https://github.com/unicode-org/icu4x>

### MIT/Apache-2.0

- **android_system_properties** 0.1.5
  Minimal Android system properties wrapper
  <https://github.com/nical/android_system_properties>
- **fallible-iterator** 0.3.0
  Fallible iterator traits
  <https://github.com/sfackler/rust-fallible-iterator>
- **fallible-streaming-iterator** 0.1.9
  Fallible streaming iteration
  <https://github.com/sfackler/fallible-streaming-iterator>
- **ff** 0.13.1
  Library for building and interfacing with finite fields
  <https://github.com/zkcrypto/ff>
- **fs-err** 2.11.0
  A drop-in replacement for std::fs with more helpful error messages.
  <https://github.com/andrewhickman/fs-err>
- **group** 0.13.0
  Elliptic curve group traits and utilities
  <https://github.com/zkcrypto/group>
- **minimal-lexical** 0.2.1
  Fast float parsing conversion routines.
  <https://github.com/Alexhuszagh/minimal-lexical>
- **plain** 0.2.3
  A small Rust library that allows users to reinterpret data of certain types safely.
  <https://github.com/randomites/plain>
- **serde_urlencoded** 0.7.1
  `x-www-form-urlencoded` meets Serde
  <https://github.com/nox/serde_urlencoded>
- **siphasher** 0.3.11
  SipHash-2-4, SipHash-1-3 and 128-bit variants in pure Rust
  <https://github.com/jedisct1/rust-siphash>
- **zstd-sys** 2.0.16+zstd.1.5.7
  Low-level bindings for the zstd compression library.
  <https://github.com/gyscos/zstd-rs>

### MPL-2.0

- **uniffi** 0.30.0
  a multi-language bindings generator for rust
  <https://github.com/mozilla/uniffi-rs>
- **uniffi_bindgen** 0.30.0
  a multi-language bindings generator for rust (codegen and cli tooling)
  <https://github.com/mozilla/uniffi-rs>
- **uniffi_build** 0.30.0
  a multi-language bindings generator for rust (build script helpers)
  <https://github.com/mozilla/uniffi-rs>
- **uniffi_core** 0.30.0
  a multi-language bindings generator for rust (runtime support code)
  <https://github.com/mozilla/uniffi-rs>
- **uniffi_meta** 0.30.0
  uniffi_meta
  <https://github.com/mozilla/uniffi-rs>
- **uniffi_pipeline** 0.30.0
  a multi-language bindings generator for rust (codegen and cli tooling)
  <https://github.com/mozilla/uniffi-rs>
- **uniffi_udl** 0.30.0
  udl parsing for the uniffi project
  <https://github.com/mozilla/uniffi-rs>

### BSD-3-Clause

- **curve25519-dalek** 4.1.3
  A pure-Rust implementation of group operations on ristretto255 and Curve25519
  <https://github.com/dalek-cryptography/curve25519-dalek/tree/main/curve25519-dalek>
- **ed25519-dalek** 2.2.0
  Fast and efficient ed25519 EdDSA key generations, signing, and verification in pure Rust.
  <https://github.com/dalek-cryptography/curve25519-dalek/tree/main/ed25519-dalek>
- **subtle** 2.6.1
  Pure-Rust traits and utilities for constant-time cryptographic implementations.
  <https://github.com/dalek-cryptography/subtle>
- **x25519-dalek** 2.0.1
  X25519 elliptic curve Diffie-Hellman key exchange in pure-Rust, using curve25519-dalek.
  <https://github.com/dalek-cryptography/curve25519-dalek/tree/main/x25519-dalek>

### Apache-2.0 OR ISC OR MIT

- **hyper-rustls** 0.27.9
  Rustls+hyper integration for pure rust HTTPS
  <https://github.com/rustls/hyper-rustls>
- **rustls** 0.23.41
  Rustls is a modern TLS library written in Rust.
  <https://github.com/rustls/rustls>
- **rustls-native-certs** 0.8.4
  rustls-native-certs allows rustls to use the platform native certificate store
  <https://github.com/rustls/rustls-native-certs>

### Unlicense OR MIT

- **aho-corasick** 1.1.4
  Fast multiple substring searching.
  <https://github.com/BurntSushi/aho-corasick>
- **byteorder** 1.5.0
  Library for reading/writing numbers in big-endian and little-endian.
  <https://github.com/BurntSushi/byteorder>
- **memchr** 2.8.2
  Provides extremely fast (uses SIMD on x86_64, aarch64 and wasm32) routines for
1, 2 or 3 byte search and single substring search.
  <https://github.com/BurntSushi/memchr>

### Apache-2.0

- **prost** 0.14.4
  A Protocol Buffers implementation for the Rust Language.
  <https://github.com/tokio-rs/prost>
- **sync_wrapper** 1.0.2
  A tool for enlisting the compiler's help in proving the absence of concurrency
  <https://github.com/Actyx/sync_wrapper>

### Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT

- **linux-raw-sys** 0.12.1
  Generated bindings for Linux's userspace API
  <https://github.com/sunfishcode/linux-raw-sys>
- **rustix** 1.1.4
  Safe Rust bindings to POSIX/Unix/Linux/Winsock-like syscalls
  <https://github.com/bytecodealliance/rustix>

### ISC

- **rustls-webpki** 0.103.13
  Web PKI X.509 Certificate Verification.
  <https://github.com/rustls/webpki>
- **untrusted** 0.9.0
  Safe, fast, zero-panic, zero-crashing, zero-allocation parsing of untrusted inputs in Rust.
  <https://github.com/briansmith/untrusted>

### MIT OR Apache-2.0 OR Zlib

- **lru-slab** 0.1.2
  Pre-allocated storage with constant-time LRU tracking
  <https://github.com/Ralith/lru-slab>
- **tinyvec_macros** 0.1.1
  Some macros for tiny containers
  <https://github.com/Soveu/tinyvec_macros>

### 0BSD OR MIT OR Apache-2.0

- **adler2** 2.0.1
  A simple clean-room implementation of the Adler-32 checksum
  <https://github.com/oyvindln/adler2>

### Apache-2.0 / MIT

- **fnv** 1.0.7
  Fowler–Noll–Vo hash function
  <https://github.com/servo/rust-fnv>

### Apache-2.0 AND ISC

- **ring** 0.17.14
  An experiment.
  <https://github.com/briansmith/ring>
  _Licensed `Apache-2.0 AND ISC` — a conjunction, not a choice: both sets of terms apply._

### Apache-2.0 OR BSL-1.0

- **ryu** 1.0.23
  Fast floating point to string conversion
  <https://github.com/dtolnay/ryu>

### BSD-2-Clause

- **arrayref** 0.3.9
  Macros to take array references of slices
  <https://github.com/droundy/arrayref>

### BSD-2-Clause OR Apache-2.0 OR MIT

- **zerocopy** 0.8.52
  Zerocopy makes zero-cost memory manipulation effortless. We write "unsafe" so you don't have to.
  <https://github.com/google/zerocopy>

### CC0-1.0 OR Apache-2.0 OR Apache-2.0 WITH LLVM-exception

- **blake3** 1.8.5
  the BLAKE3 hash function
  <https://github.com/BLAKE3-team/BLAKE3>

### CC0-1.0 OR MIT-0 OR Apache-2.0

- **constant_time_eq** 0.4.2
  Compares two equal-sized byte strings in constant time.
  <https://github.com/cesarb/constant_time_eq>

### CDLA-Permissive-2.0

- **webpki-roots** 1.0.8
  Mozilla's CA root certificates for use with webpki
  <https://github.com/rustls/webpki-roots>

### MIT AND BSD-3-Clause

- **matchit** 0.8.4
  A high performance, zero-copy URL router.
  <https://github.com/ibraheemdev/matchit>

### MIT OR Zlib OR Apache-2.0

- **miniz_oxide** 0.8.9
  DEFLATE compression and decompression library rewritten in Rust based on miniz
  <https://github.com/Frommi/miniz_oxide/tree/master/miniz_oxide>

### Zlib OR Apache-2.0 OR MIT

- **tinyvec** 1.11.0
  `tinyvec` provides 100% safe vec-like data structures.
  <https://github.com/Lokathor/tinyvec>

