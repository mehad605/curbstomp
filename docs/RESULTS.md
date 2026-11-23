# Optimization Results — Curbstomp

> **Baseline commit:** `0d821a2` (see `docs/BASELINE.md`)  
> **Final optimized commit:** `d3973f8` (17 kept iterations + 1 reverted experiment; see `docs/PROGRESS.md`)  
> **Target device profile:** Realme Narzo 20 (MediaTek Helio G85, 4 GB RAM, Android 11 / API 30, Realme UI 2.0)  
> **Measurement boundary:** **No physical device or emulator was connected** (`adb devices -l` returned empty throughout). Every static/build metric below was **directly measured** via Gradle, `apkanalyzer`, and JUnit. All on-device runtime metrics (installed size, cold start ms, PSS RAM, live CPU %, `gfxinfo` jank) are explicitly marked **Unmeasured (no device connected)** — only static code-level behavioral improvements are stated for runtime paths.

---

## 1. Baseline vs. Final Comparison

### A. Verified Static & Build Metrics

| Metric | Baseline (`0d821a2`) | Final (`d3973f8`) | Delta | Status |
|---|---:|---:|---:|---|
| **Release APK size (universal)** | 6,332,773 B (6.04 MB) | **1,503,324 B (1.43 MB)** | **−4,829,449 B (−76.3%)** | Verified (`apkanalyzer apk file-size`) |
| **APK download size** | 5,310,043 B (5.06 MB) | **1,058,875 B (1.01 MB)** | **−4,251,168 B (−80.1%)** | Verified (`apkanalyzer apk download-size`) |
| **DEX files** | 2 (`classes.dex`, `classes2.dex`) | **1 (`classes.dex`)** | **−1 file (−50%)** | Verified (`apkanalyzer dex list`) |
| **DEX method references** | 79,068 | **12,000** | **−67,068 (−84.8%)** | Verified (`apkanalyzer dex references`) |
| **DEX defined methods** | 65,366 | **8,722** | **−56,644 (−86.7%)** | Verified (`apkanalyzer dex packages`) |
| **DEX raw size in APK** | 4,499,511 B | **701,227 B** | **−3,798,284 B (−84.4%)** | Verified (`apkanalyzer files list`) |
| **DEX uncompressed bytecode (`<TOTAL>`)** | 6,570,104 B | **1,177,790 B** | **−5,392,314 B (−82.1%)** | Verified (`apkanalyzer dex packages --defined-only`) |
| **Native libraries (`.so`)** | 4 files (36,192 B) | **0 files (0 B)** | **−4 files (−100%)** | Verified (`apkanalyzer files list`) |
| **`resources.arsc` raw / download** | 1,045,784 B / 232,756 B | **443,672 B / 84,612 B** | **−57.6% raw / −63.6% dl** | Verified (`apkanalyzer files list`) |
| **`res/` files & raw size** | 877 files (574,084 B) | **676 files (271,125 B)** | **−201 files (−52.8% raw)** | Verified (`apkanalyzer files list`) |
| **String locale configs** | 89 | **1 (`default`)** | **−88 (−98.9%)** | Verified (`apkanalyzer resources configs`) |
| **Packaging metadata (`META-INF/`, `kotlin/`, other)** | 73 files (23,015 B) | **3 files (66 B)** | **−70 files (−99.7%)** | Verified (`apkanalyzer files list`) |
| **Dependencies (`releaseRuntimeClasspath`)** | 18 direct / 103 resolved | **7 direct / 70 resolved** | **−11 direct / −33 resolved** | Verified (`:app:dependencies`) |
| **Manifest permissions** | 6 | **2** (`QUERY_ALL_PACKAGES` + dynamic receiver) | **−4 permissions** | Verified (`apkanalyzer manifest permissions`) |
| **Manifest components** | 5 Activities, 2 Services, 3 Startup initializers | **4 Activities, 1 Service, 1 Startup initializer** | **−1 Act, −1 Svc, −2 Init** | Verified (`apkanalyzer manifest print`) |
| **Android Lint** | 0 errors, 327 warnings | **0 errors, 187 warnings** | **−140 warnings (−42.8%)** | Verified (`./gradlew lint`) |
| **Unit tests** | 0 (`NO-SOURCE`) | **11 passing (0 failed)** | **+11 tests** | Verified (`./gradlew test`) |

### B. APK File Breakdown (`apkanalyzer files list --raw-size --download-size --files-only`)

| Category | Baseline Files / Raw B | Final Files / Raw B | Final Download B | Raw Delta |
|---|---:|---:|---:|---:|
| `dex` | 2 / 4,499,511 B | **1 / 701,227 B** | 699,410 B | −84.4% |
| `resources.arsc` | 1 / 1,045,784 B | **1 / 443,672 B** | 84,612 B | −57.6% |
| `res/` | 877 / 574,084 B | **676 / 271,125 B** | 264,330 B | −52.8% |
| `lib/` (native `.so`) | 4 / 36,192 B | **0 / 0 B** | 0 B | −100.0% |
| `AndroidManifest.xml` | 1 / 2,678 B | **1 / 1,995 B** | 1,995 B | −25.5% |
| `assets/` (`baseline.prof*`) | 2 / 2,847 B | **2 / 1,509 B** | 1,519 B | −47.0% |
| `META-INF/` + `kotlin/` + `other` | 73 / 23,015 B | **3 / 66 B** | 66 B | −99.7% |
| **Total APK entries** | **960 / 6,184,111 B** | **684 / 1,419,594 B** | **1,051,932 B** | **−77.0%** |

### C. On-Device Runtime Metrics & Static Behavioral Changes

| Metric / Runtime Area | Baseline | Final | Verification Status |
|---|---|---|---|
| **Installed size on device** | Unavailable | Unavailable | Unmeasured (no device connected) |
| **Cold start time (median of 10)** | Unavailable | Unavailable | Unmeasured (no device connected) |
| **Idle & Active RAM (PSS)** | Unavailable | Unavailable | Unmeasured (no device connected) |
| **Idle CPU & Frame jank (`gfxinfo`)** | Unavailable | Unavailable | Unmeasured (no device connected) |
| **Background periodic ticker (`CurbstompService`)** | 1-second infinite coroutine loop (**3,600 ticks/hr**) + per-second disk writes for active usage limits | **0 periodic idle ticks/hr** — event-driven `elapsedRealtime()` accounting + single-shot wakeups at exact schedule/focus/limit boundaries + Screen ON/OFF pause | Static code verified (`CurbstompService.kt`, `RuleEngineTest`); device runtime unmeasured |
| **Accessibility IPCs on normal (non-blocked, non-settings) apps** | `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` + `rootInActiveWindow` full tree walk on every `TYPE_WINDOW_CONTENT_CHANGED` | **0 `rootInActiveWindow` Binder IPCs** on non-protected apps; `notificationTimeout="100"`; 200 ms debounce + zero-allocation streaming scan only on resolved settings/installer packages | Static code verified (`CurbstompService.kt`, `curbstomp_service_config.xml`); device runtime unmeasured |

---

## 2. Summary of Kept Optimizations (17 Iterations)

### APK, DEX, Resource & Dependency Reduction
- **Iter 1 (`4a9803c`, F-002):** Removed 7 unused dependencies (`Room`, `ZXing`, `Shizuku`, `MPAndroidChart`, `TimeRangePicker`, `fragment-ktx`, `lifecycle-viewmodel-ktx:2.10.0`) and KSP plugin; replaced with `lifecycle-runtime-ktx:2.8.7`. (**−626.0 KB APK**)
- **Iter 2 (`a48b0f4`, F-001):** Enabled R8 full mode (`isMinifyEnabled = true`), `isShrinkResources = true`, and release `Log.v/d/i` stripping. (**−3,456.4 KB APK**, 2 DEX → 1 DEX)
- **Iter 3 (`473f9ca`, F-004):** Replaced `MultiProcessDataStoreFactory` + `Gson` with `AtomicFile` + `MutableStateFlow` + `Mutex` + Android platform `org.json` (`SettingsJson`), eliminating all 4 unstripped `libdatastore_shared_counter.so` native binaries and adding `SettingsJsonTest`. (**−141.0 KB APK**, 0 native `.so`)
- **Iter 4 (`d906a25`, F-005):** Added `resourceConfigurations += listOf("en")` to strip 88 unused library translations from `resources.arsc`. (**−247.7 KB APK**)
- **Iter 5 (`2a77d03`, F-003):** Replaced 3 root `ConstraintLayout`s with flat vertical `LinearLayout`s and removed the direct `constraintlayout` dependency. (**−20.6 KB APK**)
- **Iter 6 (`1bcaa54`, F-006):** Replaced 328 KB / 52 KB traced vector launcher drawables with crisp 432×432 lossless WebP adaptive layers, moved adaptive XMLs to `res/mipmap/`, and deleted 20 unused pre-API-26 mipmap PNGs. (**−174.1 KB APK**, −91 lint warnings)
- **Iter 8 (`c480215`, F-007):** Excluded `DebugProbesKt.bin`, `kotlin/**`, `kotlin-tooling-metadata.json`, `META-INF/*.version`, and `META-INF/androidx/**`; set `buildConfig = false`; deleted 5 dead Kotlin files and 10 unused resources. (**−21.0 KB APK**)
- **Iter 16 (`f74433a`, Extra):** Enabled `android.enableNewResourceShrinker.preciseShrinking=true` in `gradle.properties` and strict resource shrinking (`res/raw/keep.xml`, `tools:shrinkMode="strict"`). Verified all fonts, XML configs, certs, icons, and all 12 app layouts remain intact. (**−32.0 KB APK**, `resources.arsc` −19.8 KB)
- **Iter 17 (`d3973f8`, Extra):** Disabled `vcsInfo` and `dependenciesInfo` (APK & bundle) and excluded unused transitive `androidx.viewpager2` from Material. (**−5.4 KB APK**)

### Battery, CPU & Accessibility Service Efficiency
- **Iter 11 (`87f02b8`, F-008, F-020):** Re-architected `CurbstompService` from a 24/7 1-second polling loop + per-second disk writes into an event-driven engine using `SystemClock.elapsedRealtime()`, single-shot boundary/limit timers, dynamic `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` / time / timezone receiver (`ContextCompat.registerReceiver`), `Mutex`-serialized state mutations, and `Long`-precision cooldown checks in `BaseBlockingService`.
- **Iter 12 (`add0017`, F-009):** Removed `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` and unused `flagReportViewIds`; set `notificationTimeout="100"`; cached dialer, SMS, launcher, and dynamically resolved self-protection target packages (including Realme/ColorOS/Oplus/HeyTap, MIUI, Samsung, and Play Store); pre-filtered packages before touching `rootInActiveWindow`; debounced content scans (200 ms) with zero-allocation streaming node traversal and API-guarded recycling.

### Memory, Lifecycle, Startup & Rendering
- **Iter 7 (`1183f56`, F-013):** Removed eager `EmojiCompatInitializer` and `ProcessLifecycleInitializer` from `androidx.startup.InitializationProvider` (retaining `ProfileInstallerInitializer` for ART baseline profiles).
- **Iter 13 (`686e100`, F-010, F-019):** Gated `MainActivity` state collection with `repeatOnLifecycle(STARTED)`, canceled the countdown coroutine in `onStop()`, replaced `notifyDataSetChanged()` with `DiffUtil`, added a bounded 48-entry icon `LruCache` cleared in `onDestroy()`, avoided redundant active-focus UI rebuilds, fixed `Locale.US` formatting, and persisted `deviceAdminActivationRequestedAt` before launching the Device Admin request.
- **Iter 14 (`a3687e0`, F-011, F-012):** Added bounded 48-entry icon `LruCache` + cancellable per-holder `iconJob` in `SelectAppsActivity`; bounded and cleared icon caches in `CreateRuleActivity` and `InstantFocusActivity`; bound `PasswordDialog` coroutine to `LifecycleOwner.lifecycleScope`; evicted `AppCache` on `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` in `Curbstomp`.
- **Iter 15 (`13e6e2d`, F-016, F-022):** Capped `CrashLogger`'s `crash_log.txt` at 64 KB with `.use {}` and `Locale.US`; set `android:windowBackground` on `Base.Theme.Curbstomp`; swapped `MainActivity` out of the splash theme before `super.onCreate()`; dropped the splash theme from secondary activities; removed redundant root activity backgrounds.

### Correctness, Storage & Privacy Surface
- **Iter 9 (`5b97a8e`, F-021):** Removed unused `INTERNET` and `PACKAGE_USAGE_STATS` permissions from `AndroidManifest.xml`, giving Curbstomp a clean offline permission surface.
- **Iter 10 (`c411841`, F-015, F-017, F-018):** Extracted pure locale-independent `RuleEngine` (ASCII `YYYYMMDD` keys, integer minute-of-day math, paused `InstantFocus` blocking/expiry fix, stale `appUsageMillis` pruning, exact `nextWakeDelayMillis`) with 8 JUnit tests (`RuleEngineTest`).

### Reverted Experiment
- **Iter 17a (Reverted):** Tried `-repackageclasses ''` in `app/proguard-rules.pro`. Although uncompressed `classes.dex` shrank by 9.9 KB, flattening package groupings broke Deflate compression locality and increased compressed DEX by +11.0 KB (+6.7 KB APK). Immediately reverted.

---

## 3. User Decisions & Tradeoffs Respected

- **F-014 (Google Fonts):** Kept GMS Downloadable Google Fonts (`Inter`, `Playfair Display`) as requested — no visual typography change.
- **F-021 (`android:allowBackup`):** Kept `android:allowBackup="true"` as requested (only unused `INTERNET` and `PACKAGE_USAGE_STATS` permissions were removed).
- **F-006 (Launcher Icon):** Used crisp 432×432 lossless WebP adaptive icon layers instead of low-resolution raster fallbacks.
- **Min SDK:** Preserved `minSdk = 26` and `targetSdk = 34` (Android 8.0+ including Realme Narzo 20 on Android 11).

---

## 4. Optional Future Opportunities (Not Applied)

If you ever want to push APK size even lower beyond 1.43 MB:
1. **Upgrade AGP to 8.9+ and enable `android.r8.optimizedResourceShrinking=true`:** Currently AGP 8.7.2 runs AAPT keep-rule generation before resource shrinking, which keeps Material's unused `MaterialTimePicker` dependency (`androidx.constraintlayout`, **99.2 KB** in DEX + `attr:layout_constraint*` in `resources.arsc`). Integrated R8 resource shrinking in AGP 8.9+ drops `ConstraintLayout` automatically with zero code changes (~50–70 KB APK win).
2. **Replace Material Components (`com.google.android.material`) with lean AppCompat/platform views:** Material + its transitive `ConstraintLayout`/`CoordinatorLayout`/`Transition` + styles account for ~650–850 KB of the remaining 1.43 MB APK, though replacing it requires restyling `BottomSheetDialog`, `MaterialCardView`, `Chip`, `SwitchMaterial`, and `TextInputLayout`.
3. **Remove GMS Downloadable Fonts (F-014) or set `android:allowBackup="false"` (F-021):** Intentionally skipped per your preference.

---

## 5. Manual Smoke-Test & Profiling Checklist (Realme Narzo 20 — Android 11 / Realme UI 2.0)

Build artifact: `app/build/outputs/apk/release/Curbstomp-release.apk`

### Functional Verification
- [ ] **Install & Cold Launch:** Install `Curbstomp-release.apk` over an existing install (verifying existing `settings.json` loads cleanly via `SettingsJson`) and on a fresh install. Confirm `Inter` / `Playfair Display` typography, crisp launcher icon, and clean splash-to-main transition with no dark/white flash.
- [ ] **Accessibility Service & Blocking:** Enable `CurbstompService` in Settings → Accessibility. Create a scheduled rule (including an overnight window, e.g., 23:00–06:00) and a daily usage-limit rule (e.g., 1 min). Confirm the target app is blocked right at the window start / limit expiry without any delay, and unblocked immediately after the window ends.
- [ ] **Screen OFF / ON Accuracy:** With a 2-minute usage limit active on a foreground app, turn the screen OFF for 2 minutes, then turn it ON. Verify screen-off time was **not** counted toward the app's usage limit.
- [ ] **Instant Focus (Active & Paused):** Start an Instant Focus session, verify blocked apps are intercepted, pause the session (verify apps are allowed while paused and the rule is not deleted), resume, and verify auto-expiry.
- [ ] **Strict Mode & Realme UI Self-Protection:** Enable Device Admin (verify the grace window does not lock you out during activation), enable Strict Mode + password protection, then open Realme Settings → Accessibility, Device Admin, and App Info → Curbstomp. Verify Curbstomp blocks tampering while normal apps open with zero sluggishness.
- [ ] **App Picker (`SelectAppsActivity`):** Rapidly scroll through all installed apps, type search filters, select/deselect apps, rotate/close, and verify icons match each row with no recycled-view flicker.
- [ ] **Realme UI Background Persistence:** Leave Curbstomp idle in the background for 30+ minutes with screen off; confirm the service survives Realme UI memory management and triggers the next scheduled rule on time.

### On-Device Profiling Commands (Once Connected via USB/ADB)
```bash
# 1. Installed size on device
adb shell pm path com.mhm.curbstomp
adb shell dumpsys diskstats | grep -A 5 com.mhm.curbstomp

# 2. Cold start time (repeat 10x after force-stop)
adb shell am force-stop com.mhm.curbstomp
adb shell am start -W -n com.mhm.curbstomp/.ui.activity.MainActivity

# 3. RAM (PSS) — idle (service only) and active (MainActivity open)
adb shell dumpsys meminfo com.mhm.curbstomp

# 4. Idle CPU & Accessibility wakeup check (screen on, no blocked apps open)
adb shell top -b -n 5 -d 2 | grep curbstomp

# 5. Frame jank during scrolling in SelectAppsActivity / MainActivity
adb shell dumpsys gfxinfo com.mhm.curbstomp reset
# (scroll lists on device)
adb shell dumpsys gfxinfo com.mhm.curbstomp
```
