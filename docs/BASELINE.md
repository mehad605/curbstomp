# Phase 1: Baseline Measurements — Curbstomp

> **Measured at commit:** `0d821a2` (clean working tree, before any code or build changes)  
> **Build environment:** Linux, OpenJDK `21.0.13.1`, Gradle `8.10.2`, AGP `8.7.2`, Android SDK Platform `34` / Build-Tools `34.0.0`, `apkanalyzer` (Android cmdline-tools)  
> **Device / Emulator availability:** **No physical device or emulator is connected** (`adb devices -l` returned empty). All build/static metrics below are **verified (measured)** directly from Gradle and `apkanalyzer` outputs. All on-device runtime metrics are marked **Unavailable (no device connected)** and will not be invented.

---

## 1. Baseline Summary Table

| Metric | Baseline Value | Verification Status | Source Command |
|---|---|---|---|
| **Release APK size (universal)** | **6,332,773 bytes (6.04 MB / 6,184.3 KB)** | Verified (measured) | `ls -l app/build/outputs/apk/release/Curbstomp-release.apk` & `apkanalyzer apk file-size` |
| **Release APK per-ABI splits** | N/A (`splits.abi.isEnable = false`, single universal APK) | Verified (measured) | `app/build.gradle.kts` |
| **APK download size** | **5,310,043 bytes (5.06 MB / 5,185.6 KB)** | Verified (measured) | `apkanalyzer apk download-size` |
| **DEX files & method references** | **2 DEX files** (`classes.dex`: 65,444 refs; `classes2.dex`: 13,624 refs) → **79,068 total method refs**, **65,366 defined methods** | Verified (measured) | `apkanalyzer dex list` & `apkanalyzer dex references` & `apkanalyzer dex packages` |
| **DEX size (in APK / uncompressed)** | **4,499,511 bytes raw in APK** (4,487,378 B download) / **6,570,104 bytes** uncompressed bytecode (`<TOTAL>`) | Verified (measured) | `apkanalyzer files list` & `apkanalyzer dex packages --defined-only` |
| **Native libraries (`lib/`)** | **4 `.so` files** (`libdatastore_shared_counter.so` × 4 ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), **36,192 bytes raw** (13,124 B download), unstripped | Verified (measured) | `apkanalyzer files list --files-only` & `stripReleaseDebugSymbols` build warning |
| **Resources (`res/` + `resources.arsc`)** | **877 files** in `res/` (**574,084 B** raw / 561,996 B dl) + `resources.arsc` (**1,045,784 B** raw / 232,756 B dl) = **1,619,868 B raw** | Verified (measured) | `apkanalyzer files list --files-only` |
| **String locale configs in APK** | **89 configs** (`default` + 88 locales from libraries; app itself only defines English `values/strings.xml`) | Verified (measured) | `apkanalyzer resources configs --type string \| wc -l` |
| **Assets (`assets/`)** | **2 files** (`baseline.prof`: 2,604 B; `baseline.profm`: 243 B) = **2,847 B** | Verified (measured) | `apkanalyzer files list --files-only` |
| **Dependencies (`releaseRuntimeClasspath`)** | **18 direct** entries (`16` declared + `viewbinding` + `kotlin-stdlib`), **103 distinct resolved artifacts** | Verified (measured) | `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` |
| **Merged Manifest components** | 5 Activities (incl. unused `CaptureActivity`), 2 Services (incl. unused `MultiInstanceInvalidationService`), 2 Receivers (incl. `ProfileInstallReceiver`), 1 ContentProvider (`androidx.startup.InitializationProvider` with 3 eager initializers), 6 Permissions (incl. `CAMERA`, `API_V23`, `INTERNET`, `PACKAGE_USAGE_STATS`) | Verified (measured) | `apkanalyzer manifest print` |
| **Baseline Lint / Unit Tests** | `lint`: **0 errors, 327 warnings**; `test`: **0 tests** (`NO-SOURCE`) | Verified (measured) | `./gradlew lint test` |
| **Installed size on device** | Unavailable (no device/emulator connected) | Unmeasured | `adb shell dumpsys package` / `du` |
| **Cold start time (median of 10)** | Unavailable (no device/emulator connected) | Unmeasured | `adb shell am start -W` |
| **Idle RAM (PSS)** | Unavailable (no device/emulator connected) | Unmeasured | `adb shell dumpsys meminfo` |
| **Active RAM (PSS)** | Unavailable (no device/emulator connected) | Unmeasured | `adb shell dumpsys meminfo` |
| **Idle CPU** | Unavailable (no device/emulator connected) | Unmeasured | `adb shell top` / `dumpsys cpuinfo` |
| **Background wakeups / jobs / alarms** | Unavailable on device (static code inspection shows a **1-second infinite coroutine ticker** in `CurbstompService` = **3,600 ticks/hour** while service is connected, plus full window-tree traversal on every `TYPE_WINDOW_CONTENT_CHANGED` event) | Static analysis only (runtime unmeasured) | Code inspection (`CurbstompService.kt`) |
| **Frame jank (`gfxinfo`)** | Unavailable (no device/emulator connected) | Unmeasured | `adb shell dumpsys gfxinfo` |

---

## 2. APK File Breakdown (`apkanalyzer files list --raw-size --download-size --files-only`)

Total files in APK: **960** (raw content sum: **6,184,111 bytes**; ZIP container / APK file size: **6,332,773 bytes**; download size: **5,310,043 bytes**).

| Category | File Count | Raw Size (Bytes) | Download Size (Bytes) | Share of Raw Content |
|---|---:|---:|---:|---:|
| `dex` (`classes.dex`, `classes2.dex`) | 2 | 4,499,511 | 4,487,378 | **72.8%** |
| `resources.arsc` | 1 | 1,045,784 | 232,756 | **16.9%** |
| `res/` | 877 | 574,084 | 561,996 | **9.3%** |
| `lib/` (native `.so`) | 4 | 36,192 | 13,124 | 0.6% |
| `META-INF/` | 64 | 11,487 | 11,597 | 0.2% |
| `kotlin/` metadata | 7 | 10,468 | 10,412 | 0.2% |
| `assets/` (`dexopt/baseline.prof*`) | 2 | 2,847 | 2,857 | <0.1% |
| `AndroidManifest.xml` | 1 | 2,678 | 2,678 | <0.1% |
| `other` (`DebugProbesKt.bin`, `kotlin-tooling-metadata.json`) | 2 | 1,060 | 1,060 | <0.1% |
| **TOTAL** | **960** | **6,184,111** | **5,323,858** | **100%** |

### Native Libraries Packaged (`extractNativeLibs="false"`)
| Path | Raw Bytes | Download Bytes | Note |
|---|---:|---:|---|
| `/lib/arm64-v8a/libdatastore_shared_counter.so` | 10,360 | 3,445 | From `MultiProcessDataStoreFactory` (unstripped) |
| `/lib/x86_64/libdatastore_shared_counter.so` | 9,424 | 3,217 | From `MultiProcessDataStoreFactory` (unstripped) |
| `/lib/armeabi-v7a/libdatastore_shared_counter.so` | 8,432 | 3,167 | From `MultiProcessDataStoreFactory` (unstripped) |
| `/lib/x86/libdatastore_shared_counter.so` | 7,976 | 3,295 | From `MultiProcessDataStoreFactory` (unstripped) |

---

## 3. DEX Package Breakdown (`apkanalyzer dex packages --defined-only`)

Total uncompressed DEX bytecode: **6,570,104 bytes**, **65,366 defined methods**, **77,950 referenced methods** across 2 DEX files.  
App code (`com.mhm.curbstomp`) is only **114,770 bytes (984 defined methods)** — **1.75%** of total DEX bytecode.

### Top Packages by Uncompressed DEX Size
| Package | Uncompressed Bytes | Defined Methods | Referenced Methods | Used by App? |
|---|---:|---:|---:|---|
| `com.google.android` (Material) | 994,720 | 10,602 | 11,756 | Partial (unminified) |
| `kotlin` (stdlib) | 921,031 | 10,121 | 11,206 | Partial (unminified) |
| `androidx.constraintlayout` | 622,640 | 3,826 | 4,264 | Minimal (1 layout uses ConstraintLayout; MotionLayout unused) |
| `androidx.core` | 551,677 | 6,707 | 6,902 | Partial (unminified) |
| `kotlinx.coroutines` | 447,547 | 4,785 | 5,009 | Partial (unminified) |
| `androidx.appcompat` | 404,530 | 4,547 | 5,365 | Partial (unminified) |
| `com.google.zxing` | 318,681 | 1,907 | 2,091 | **Unused** |
| `kotlinx.serialization` | 226,442 | 2,738 | 2,842 | **Unused** (transitive from `lifecycle-viewmodel-ktx 2.10.0` / `savedstate`) |
| `com.github.mikephil` (MPAndroidChart) | 203,454 | 2,087 | 2,556 | **Unused** |
| `androidx.recyclerview` | 201,578 | 1,942 | 2,149 | Partial (unminified) |
| `androidx.fragment` | 166,199 | 1,565 | 1,650 | **Unused directly** (transitive via AppCompat) |
| `okio` | 164,279 | 1,516 | 1,535 | Transitive via `androidx.datastore` |
| `com.google.gson` | 120,499 | 1,210 | 1,239 | Yes (for `settings.json` only) |
| `com.mhm.curbstomp` (**App code**) | **114,770** | **984** | **1,024** | **App** |
| `androidx.room` | 111,612 | 1,196 | 1,212 | **Unused** |
| `androidx.lifecycle` | 89,102 | 927 | 941 | Partial |
| `androidx.transition` | 82,719 | 871 | 932 | Transitive via Material |
| `androidx.emoji2` | 78,195 | 996 | 1,045 | Transitive via AppCompat (eager startup initializer!) |
| `androidx.datastore` | 74,801 | 704 | 717 | Yes (`settings.json`) |
| `androidx.activity` | 61,989 | 769 | 794 | Partial |
| `com.journeyapps.barcodescanner` | 49,686 | 576 | 609 | **Unused** |
| `nl.joery.timerangepicker` | 48,566 | 224 | 236 | **Unused** |
| `rikka.*` + `moe.shizuku.*` | 30,097 | 344 | 386 | **Unused** |

---

## 4. Direct Dependencies (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`)

18 direct entries resolving to **103 distinct artifacts**:
1. `androidx.databinding:viewbinding:8.7.2`
2. `org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.0.21`
3. `androidx.core:core-ktx:1.13.1`
4. `androidx.appcompat:appcompat:1.7.0`
5. `com.google.android.material:material:1.12.0`
6. `androidx.activity:activity:1.9.2`
7. `androidx.constraintlayout:constraintlayout:2.1.4`
8. `androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0`
9. `androidx.fragment:fragment-ktx:1.8.9`
10. `androidx.datastore:datastore:1.2.1`
11. `androidx.room:room-runtime:2.6.1`
12. `androidx.room:room-ktx:2.6.1`
13. `com.journeyapps:zxing-android-embedded:4.3.0`
14. `dev.rikka.shizuku:api:13.1.5`
15. `dev.rikka.shizuku:provider:13.1.5`
16. `com.google.code.gson:gson:2.11.0`
17. `com.github.PhilJay:MPAndroidChart:v3.1.0`
18. `nl.joery.timerangepicker:timerangepicker:1.0.0`

---

## 5. Merged Manifest Findings (`apkanalyzer manifest print`)

Notable unexpected entries merged into the release APK manifest:
- **Unexpected Permissions:**
  - `android.permission.CAMERA` (merged from unused `zxing-android-embedded`)
  - `moe.shizuku.manager.permission.API_V23` (merged from unused `dev.rikka.shizuku`)
  - `android.permission.INTERNET` (declared in app manifest; unused by app code)
  - `android.permission.PACKAGE_USAGE_STATS` (declared in app manifest; unused by app code)
- **Unexpected Components:**
  - `<activity android:name="com.journeyapps.barcodescanner.CaptureActivity" />` (from unused ZXing)
  - `<service android:name="androidx.room.MultiInstanceInvalidationService" />` (from unused Room)
  - `<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />` (from unused Shizuku)
- **Startup ContentProvider (`androidx.startup.InitializationProvider`)** runs 3 initializers at cold start (including whenever the OS starts `CurbstompService` in the background!):
  - `androidx.emoji2.text.EmojiCompatInitializer`
  - `androidx.lifecycle.ProcessLifecycleInitializer`
  - `androidx.profileinstaller.ProfileInstallerInitializer`
- **Build & Packaging Flags:**
  - `android:extractNativeLibs="false"` (already set by AGP)
  - `DebugProbesKt.bin` (777 B) and `kotlin-tooling-metadata.json` (283 B) and `kotlin/` metadata (10,468 B) packaged because R8/packaging exclusions are not configured.
