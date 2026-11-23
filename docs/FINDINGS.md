# Phase 2: Audit Findings — Curbstomp

> **Target Benchmark Device:** Realme Narzo 20 (MediaTek Helio G85, 4 GB RAM, Android 11 / API 30, Realme UI 2.0, 30+ apps installed, aggressive OEM background killer).  
> **Note on estimates:** Byte/method impacts derived from Phase 1 `apkanalyzer` outputs are labeled **(measured baseline contribution)**; projected post-fix APK deltas and runtime CPU/RAM/battery effects are labeled **(unverified estimate)** until measured after each Phase 3 iteration (or on-device).

---

## Summary Priority Matrix

| ID | Category | Impact | Risk | Short Description |
|---|---|---|---|---|
| **F-001** | Size | **High** | Med | Enable R8 full-mode shrinking/obfuscation (`isMinifyEnabled`) & `isShrinkResources` |
| **F-002** | Size / Security | **High** | Low | Remove 7 unused dependencies + KSP (`Room`, `ZXing`, `Shizuku`, `MPAndroidChart`, `TimeRangePicker`, `fragment-ktx`, `lifecycle-viewmodel-ktx`) |
| **F-008** | Battery / CPU | **High** | Med | Eliminate 24/7 1-second infinite ticker & per-second disk writes in `CurbstompService` |
| **F-009** | Battery / CPU / Memory | **High** | Med | Stop walking full Accessibility node trees on every `TYPE_WINDOW_CONTENT_CHANGED` in every app |
| **F-004** | Size / Startup / Memory | **High** | Med | Replace `MultiProcessDataStoreFactory` + `Gson` with `AtomicFile`/`StateFlow` + platform `org.json` (removes all 4 `.so` libs, `okio`, `datastore`, `gson`) |
| **F-005** | Size | **High** | Low | Filter `resources.arsc` locales from 89 down to `"en"` |
| **F-003** | Size / Startup | **High** | Low | Replace 3 trivial root `ConstraintLayout`s with `LinearLayout` and remove `constraintlayout` (622 KB DEX) |
| **F-010** | CPU / Memory / Battery | **High** | Low | Fix `MainActivity` 1-second full-list `notifyDataSetChanged()` / view-rebuild storm & background collection |
| **F-006** | Size / Startup | **Med-High** | Low | Optimize 328 KB traced vector launcher icons & remove 20 unused pre-API-26 mipmap PNGs |
| **F-017** | Correctness | **High** | Low | Fix paused `InstantFocus` sessions still blocking apps and expiring in `CurbstompService` |
| **F-013** | Startup / Memory | **Med** | Low | Disable unused `androidx.startup` initializers (`EmojiCompatInitializer`, `ProcessLifecycleInitializer`) |
| **F-011** | Memory / CPU | **Med** | Low | Cache app icons in bounded `LruCache` and cancel recycled `ViewHolder` loads in `SelectAppsActivity` |
| **F-012** | Memory | **Med** | Low | Fix unbounded icon caches, leaked `CoroutineScope` in `PasswordDialog`, unused `CrashLogger` in service, and trim `AppCache` |
| **F-015** | Storage / Memory | **Med** | Low | Prune stale `YYYYMMDD` entries from `Settings.appUsageMillis` to prevent unbounded growth |
| **F-018** | Correctness | **Med** | Low | Replace locale-dependent `SimpleDateFormat` / `String.format` with `Locale.US` / integer minutes |
| **F-020** | Correctness / CPU | **Med** | Low | Fix concurrency race between `onAccessibilityEvent` and usage ticker in `CurbstompService` |
| **F-019** | Correctness | **Med** | Low | Record `deviceAdminActivationRequestedAt` when requesting Device Admin |
| **F-021** | Security / Privacy | **Med** | Low | Remove unused `INTERNET` & `PACKAGE_USAGE_STATS` permissions; harden `allowBackup` |
| **F-007** | Size | **Low** | Low | Exclude `DebugProbesKt.bin` / `kotlin/` / `META-INF` packaging bloat & remove dead code/resources |
| **F-016** | Storage | **Low** | Low | Cap `CrashLogger` `crash_log.txt` file size and remove unused `logNonFatalError` |
| **F-022** | CPU / Rendering | **Low** | Low | Fix persistent `Theme.Curbstomp.Splash` window overdraw across Activities |
| **F-014** | Startup / Battery | **Med** | **Tradeoff** | Replace GMS Downloadable Google Fonts (`Inter` / `Playfair Display`) — **requires user decision** |

---

## Detailed Findings

### Size Findings

#### F-001 — Release Minification, Resource Shrinking & R8 Full Mode Disabled
- **Category:** Size (also Startup & Memory via smaller DEX mapped into memory)
- **Location:** `app/build.gradle.kts:42-50`, `app/proguard-rules.pro`
- **Problem:** `buildTypes.release` explicitly sets `isMinifyEnabled = false` and omits `isShrinkResources = true`. As measured in Phase 1, the release APK ships **65,366 defined methods** across 2 DEX files (**4,499,511 B** raw DEX) and **877 files** in `res/` (**574,084 B**), even though Curbstomp's own code is only **1.75%** (114 KB / 984 methods) of the DEX. On a 4 GB RAM device, loading two unshrunk DEX files increases cold-start classloading, ART oat/vdex disk footprint, and memory mapped pages.
- **Fix:** Set `isMinifyEnabled = true` and `isShrinkResources = true` in `buildTypes.release` (AGP 8.7.2 uses R8 full mode by default). Ensure serialization/reflection safety (best paired with F-004 removing Gson reflection, or keep rules for `com.mhm.curbstomp.data.models.**` while Gson is present). Also configure `proguard-rules.pro` to strip any `android.util.Log` debug calls if introduced.
- **Expected impact:** **High** — estimated **−2.5 to −3.8 MB APK** (40–60% reduction from R8 + resource shrinking alone, collapsing 2 DEX files into 1 small DEX; *unverified estimate* until built).
- **Risk:** Medium if Gson reflection is still present (R8 can obfuscate field names serialized to `settings.json`). Eliminated by either explicit `-keep` rules for `data.models.**` or removing Gson (F-004).

#### F-002 — Seven Unused Dependencies (+ KSP Processor) Bundled in Release APK
- **Category:** Size / Security / Startup
- **Location:** `app/build.gradle.kts:6, 79-97`, `build.gradle.kts:5`, `gradle/libs.versions.toml:3-43`, `settings.gradle.kts:16-18`
- **Problem:** Zero references exist in `app/src/` to:
  1. `androidx.room:room-runtime:2.6.1`, `room-ktx:2.6.1`, and `ksp(room-compiler)` — **111,612 B** uncompressed DEX (measured) + merges unused `androidx.room.MultiInstanceInvalidationService` into the manifest.
  2. `com.journeyapps:zxing-android-embedded:4.3.0` (+ `com.google.zxing:core:3.4.1`) — **368,367 B** uncompressed DEX (measured) + merges `android.permission.CAMERA`, 6 `<uses-feature>` entries, and `com.journeyapps.barcodescanner.CaptureActivity` into the manifest.
  3. `dev.rikka.shizuku:api:13.1.5` & `provider:13.1.5` — **30,097 B** uncompressed DEX (measured) + merges `moe.shizuku.manager.permission.API_V23` and `V3_SUPPORT` meta-data.
  4. `com.github.PhilJay:MPAndroidChart:v3.1.0` — **203,454 B** uncompressed DEX (measured).
  5. `nl.joery.timerangepicker:timerangepicker:1.0.0` — **48,566 B** uncompressed DEX (measured).
  6. `androidx.fragment:fragment-ktx:1.8.9` — unused directly (no `Fragment` subclasses exist).
  7. `androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0` — no `ViewModel` subclasses exist; only `lifecycleScope` (provided by `androidx.lifecycle:lifecycle-runtime-ktx`) is used. Version `2.10.0` also drags in `kotlinx-serialization-core:1.7.3` (**226,442 B** uncompressed DEX, measured!) and upgrades `kotlin-stdlib` to `2.0.21`.
  8. `anychart-android` unused catalog entry in `libs.versions.toml` and `jitpack.io` repository in `settings.gradle.kts`.
- **Fix:** Remove the KSP plugin and all 7 unused dependencies; replace `lifecycle-viewmodel-ktx:2.10.0` with `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` (which provides `lifecycleScope` without pulling `kotlinx-serialization-core` or `kotlin-stdlib 2.0.21`). Clean up `libs.versions.toml` and `settings.gradle.kts`.
- **Expected impact:** **High** — removes **~988 KB** uncompressed DEX (measured baseline contribution), ~30+ transitive artifacts, unused manifest permissions/activities/services, and speeds up builds.
- **Risk:** Very Low (verified via codebase-wide grep: 0 usages).

#### F-003 — `ConstraintLayout` (622 KB DEX) Used Only as Simple Vertical Stacks
- **Category:** Size / Startup / CPU
- **Location:** `app/build.gradle.kts:77`, `app/src/main/res/layout/activity_main.xml:2`, `activity_create_rule.xml:2`, `activity_instant_focus.xml:2`
- **Problem:** `androidx.constraintlayout:constraintlayout:2.1.4` accounts for **622,640 B** uncompressed DEX / **3,826 defined methods** (measured — including `constraintlayout.core`, `motion`, `utils`, `helper`). Every single usage in all 3 layouts is just a vertical top-to-bottom stack (toolbar → divider → `0dp` ScrollView → divider → bottom bar).
- **Fix:** Replace the root `<androidx.constraintlayout.widget.ConstraintLayout>` in those 3 layouts with `<LinearLayout android:orientation="vertical">` using `android:layout_height="0dp" android:layout_weight="1"` on the scrollable middle view, and remove `libs.androidx.constraintlayout` from `app/build.gradle.kts`.
- **Expected impact:** **High** — removes **622,640 B** uncompressed DEX (measured) and eliminates ConstraintLayout's solver overhead during view inflation/measure passes.
- **Risk:** Low — identical visual layout; verify view hierarchy and IDs match ViewBinding fields.

#### F-004 — `MultiProcessDataStoreFactory` + `Gson` Pulls In 4 Native `.so` Libraries, `Okio`, and Reflection Overhead
- **Category:** Size / Startup / Memory / Reliability
- **Location:** `app/src/main/java/com/mhm/curbstomp/utils/DataStore.kt:1-78`, `app/build.gradle.kts:80, 91`
- **Problem:** Curbstomp is a **single-process app**, yet `DataStoreManager` uses `MultiProcessDataStoreFactory` + `Gson`:
  - Bundles 4 unstripped native `.so` files (`libdatastore_shared_counter.so` across `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` = **36,192 B** raw, measured) — the **only** native libraries in the entire APK!
  - Pulls in `androidx.datastore` (**74,801 B** DEX, measured) + `com.squareup.okio` (**164,279 B** DEX, measured) + `com.google.gson` (**120,499 B** DEX, measured) = **359,579 B** uncompressed DEX + JNI load at startup.
  - `Gson` uses runtime reflection, which is slower on cold start, allocates reflection metadata, and is fragile under R8 full mode.
- **Fix:** Replace `Gson` with Android platform `org.json.JSONObject` / `org.json.JSONArray` (zero dependency bytes, zero reflection, 100% R8-safe, reading/writing the exact same `filesDir/datastore/settings.json` schema with full backward compatibility for existing user data). Replace `MultiProcessDataStoreFactory` with a lean singleton backed by `android.util.AtomicFile` + `MutableStateFlow<Settings>` + `Mutex` on `Dispatchers.IO`. Remove `androidx.datastore` and `com.google.code.gson` dependencies.
- **Expected impact:** **High** — removes **359,579 B** uncompressed DEX + **36,192 B** native `.so` files (making the APK 100% architecture-independent with **zero native libraries**!), removes JNI loading on startup, and eliminates R8 reflection hazards.
- **Risk:** Medium — must strictly preserve exact JSON field names (`rules`, `instantFocusSessions`, `passwordHash`, `antiUninstallEnabled`, `extraHardenEnabled`, `deviceAdminActivationRequestedAt`, `appUsageMillis`, and nested `Rule`/`InstantFocus`/`AppLimit` fields) and handle missing/corrupt files gracefully. Easily verified with unit tests!

#### F-005 — 89 Unused Library Locales Packaged in `resources.arsc`
- **Category:** Size
- **Location:** `app/build.gradle.kts:17-29`
- **Problem:** `apkanalyzer resources configs --type string` shows **89 locale configurations** packaged into `resources.arsc` (**1,045,784 B** raw / **232,756 B** download, measured), all inherited from AndroidX and Material Components, even though Curbstomp only has default English strings (`res/values/strings.xml`).
- **Fix:** Add `androidResources { localeFilters += listOf("en") }` inside `android { defaultConfig { ... } }` (or `resourceConfigurations += listOf("en")` for AGP 8.7).
- **Expected impact:** **High** — estimated **−500 to −750 KB** raw `resources.arsc` / **−120 to −180 KB** download size (*unverified estimate* until measured).
- **Risk:** Very Low (app UI is English-only).

#### F-006 — Oversized Auto-Traced Launcher Vector Drawables (380 KB Source) & 20 Unused Pre-API-26 Mipmap PNGs
- **Category:** Size / Startup
- **Location:** `app/src/main/res/drawable/ic_launcher_foreground.xml` (327,895 B), `ic_launcher_monochrome.xml` (51,701 B), `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/*.png` (20 files, 106 KB), `app/src/main/res/mipmap-anydpi-v26/`
- **Problem:**
  1. `ic_launcher_foreground.xml` compiles to **125,799 B** in the APK (the single largest resource file!) and `ic_launcher_monochrome.xml` compiles to **24,735 B** — both are auto-traced bitmaps with 8-decimal-place coordinates triggering **68 `[VectorPath]` lint warnings**. Because `splash_background.xml` references `@mipmap/ic_launcher`, Android parses and rasterizes these massive vectors on the main thread during cold start!
  2. All 20 PNGs in `mipmap-mdpi..xxxhdpi/` (**105,989 B** total, measured) are completely dead on API 26+ because `minSdk = 26` always selects `mipmap-anydpi-v26/ic_launcher.xml` (which references `@drawable/ic_launcher_*`).
  3. `mipmap-anydpi-v26` triggers `ObsoleteSdkInt` since `minSdk = 26`.
- **Fix:**
  1. Convert the crisp existing `mipmap-xxxhdpi/ic_launcher_foreground.png` and `ic_launcher_monochrome.png` (or rasterized equivalents at high resolution) into lossless/near-lossless WebP drawables (e.g., ~5–10 KB each vs 150 KB compiled XML!) — or simplify vector precision — and point `mipmap-anydpi/ic_launcher.xml` & `ic_launcher_round.xml` to them.
  2. Delete the 20 unused `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/*.png` files and rename `mipmap-anydpi-v26` → `mipmap-anydpi`.
- **Expected impact:** **Med-High** — removes **~230–250 KB** raw APK size (measured baseline: 150.5 KB compiled vector XML + 106 KB unused mipmaps) and removes heavy vector parsing from Activity cold start.
- **Risk:** Low — preserves identical launcher icon appearance on API 26+ (including Android 11 target and Android 13+ themed monochrome icons).

#### F-007 — Packaging Metadata Bloat, Unused `buildConfig`, Dead Source Files & Unused Resources
- **Category:** Size
- **Location:** `app/build.gradle.kts:67-70`, `Constants.kt`, `utils/AppFilter.kt`, `utils/ColorUtils.kt`, `utils/PackageFinderTools.kt`, `utils/TimeTools.kt`, `res/drawable/stippled_dot.png`, `res/values/colors.xml:34-50`, `res/values/themes.xml:70-78`
- **Problem:**
  - `DebugProbesKt.bin` (777 B), `kotlin-tooling-metadata.json` (283 B), `kotlin/` metadata (10,468 B), and 64 `META-INF/` files (11,487 B) are packaged into the release APK.
  - `buildConfig = true` generates an unused `BuildConfig` class.
  - 5 Kotlin files (`Constants.kt`, `AppFilter.kt`, `ColorUtils.kt`, `PackageFinderTools.kt`, `TimeTools.kt`) have zero live references.
  - 10 unused resources (`stippled_dot.png`, 8 colors in `colors.xml`, `Theme.Curbstomp.TransparentDialog` in `themes.xml`).
- **Fix:** Add `packaging { resources { excludes += setOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "kotlin/**", "META-INF/*.version", "META-INF/androidx/**") } }`, set `buildConfig = false`, delete dead source files and unused resources.
- **Expected impact:** **Low** — ~25–35 KB raw APK size + cleaner codebase & fewer lint warnings.
- **Risk:** Very Low (verified unused).

---

### Background, Battery & CPU Findings (Critical for Realme Narzo 20 / Helio G85)

#### F-008 — Unconditional 24/7 1-Second Ticker + Per-Second Full Disk Writes in `CurbstompService`
- **Category:** Battery / CPU / Storage
- **Location:** `app/src/main/java/com/mhm/curbstomp/services/CurbstompService.kt:71-86, 118-246`
- **Problem:**
  1. `startUsageTicker()` runs `while (isActive) { delay(1000); handleTick() }` **forever** — 3,600 wakeups/hour, 86,400/day — even when the screen is OFF, when the user is on the home screen, or when no scheduled rules have finite (`> 0`) per-app usage limits!
  2. Every single second while any non-Curbstomp app is in foreground, `handleTick()` calls `checkLimits()`:
     - Calls `TelecomManager.defaultDialerPackage` and `Telephony.Sms.getDefaultSmsPackage(this)` over Binder IPC.
     - Allocates a new `setOf(...)` of 12 fallback packages, `Calendar.getInstance()`, two `SimpleDateFormat` objects, two `Date()` objects, and copies `currentSettings.appUsageMillis.toMutableMap()`.
     - When the user is in a limited app, calls `dataStoreManager.updateAppUsageMillis(usageMap)` **every single second**, serializing and rewriting the ENTIRE `settings.json` to flash storage every second (and triggering `collectLatest` in both `CurbstompService` and `MainActivity` every second!).
  3. On Realme UI 2.0 (Android 11), continuous background CPU wakeups & disk I/O make the app a prime target for OEM battery-optimization / background-process killing!
- **Fix:**
  1. Register a dynamic `BroadcastReceiver` in `CurbstompService` for `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`, and `ACTION_USER_PRESENT` (unregistered in `onDestroy()`). Immediately stop tracking and flush in-memory usage when the screen turns off; re-check current foreground app on screen on.
  2. **Zero-wakeup idle design:** Do NOT run any periodic ticker unless the current foreground app actually has an active scheduled rule with a finite `maxUsageMillis > 0` (or an active rule/focus needs countdown monitoring). When an app with a finite limit is in the foreground, record `sessionStartElapsed = SystemClock.elapsedRealtime()` and schedule a single coroutine `delay(remainingMillis)` (or coarse check at rule-boundary / limit expiry) instead of waking every 1 second! When the user switches apps or turns off the screen, compute `elapsed = SystemClock.elapsedRealtime() - sessionStartElapsed` and flush the accumulated usage once!
     - *Detail for instant rules & cross-midnight / rule-start boundaries:* If an app is already open when a rule's `startTime` arrives, schedule a single exact `delay(millisUntilNextRuleTransition)` ONLY while the screen is ON and a non-critical app is in the foreground (never while screen is OFF or on launcher!). When screen is OFF or on launcher/SystemUI/Curbstomp, **0 coroutines waking up (0 ticks/hour)**!
  3. For live UI updates while `MainActivity` is open, `CurbstompService` isn't even tracking a blocked app because `Curbstomp` itself is in the foreground; flushing on every app switch guarantees `MainActivity` immediately sees up-to-the-second usage when opened.
- **Expected impact:** **High (CRITICAL)** — reduces idle CPU wakeups from **3,600/hour → ~0/hour**, eliminates per-second flash writes (reducing disk I/O by >95%), and eliminates per-second GC churn (*unverified runtime estimate*; static loop completely eliminated).
- **Risk:** Medium — must carefully handle: (a) user already inside a restricted app when a rule window starts, (b) user reaching a per-app time limit while staying in the app, (c) cross-midnight rule transitions, and (d) flushing usage before service destruction. All cases are straightforward to unit-test with pure Kotlin time-window logic!

#### F-009 — Full Accessibility Node-Tree Traversal on Every `TYPE_WINDOW_CONTENT_CHANGED` Across Almost All Apps
- **Category:** Battery / CPU / Memory / Jank
- **Location:** `app/src/main/java/com/mhm/curbstomp/services/CurbstompService.kt:41-46, 263-367`, `app/src/main/res/xml/curbstomp_service_config.xml:3-6`
- **Problem:**
  1. `CurbstompService` subscribes to `TYPE_WINDOW_CONTENT_CHANGED` globally AND sets `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` in `onServiceConnected()`.
  2. On **every** content change event (every scroll frame, chat bubble, video timer tick, typing keystroke, animation) in **every** app except 7 hardcoded packages (`chrome`, `firefox`, `brave`, `opera`, `whatsapp`, `telegram`, `discord`), `handleSelfProtection()` invokes `rootInActiveWindow` (Binder IPC), recursively walks the entire view tree (`collectScreenTexts`), allocates a `MutableList<String>` containing every `text` and `contentDescription` on screen, and runs 5 full-list `.lowercase(Locale.ROOT).contains(...)` passes!
  3. On a MediaTek Helio G85 with 30+ apps installed, this executes dozens of times per second during normal phone use (Instagram, YouTube, Play Store, Files, Games, etc.), burning battery, heating the SoC, and causing frame drops system-wide.
  4. Furthermore, `pressHome()` has no cooldown/debounce (`BaseBlockingService.isDelayOver` is defined but never called), so rapid content events can fire dozens of `GLOBAL_ACTION_HOME` calls and stack `Toast`s.
- **Fix:**
  1. **Restrict self-protection tree scanning strictly to system/installer/settings/OEM-management packages**: dynamically build a cached set of protected target packages (e.g., `com.android.settings`, package installers resolved via `ACTION_UNINSTALL_PACKAGE` / `ACTION_DELETE`, accessibility/device-admin handlers, `com.android.vending` Play Store uninstall screen, plus OEM Realme/ColorOS/Oplus package manager/security apps like `com.oplus.*` / `com.coloros.*` / `com.heytap.*` / `com.miui.*` / `com.samsung.*`, or simply check if the package is a system app / Play Store / matches known installer/settings intents). Normal user apps (YouTube, games, browsers, social apps, launchers) skip `rootInActiveWindow` completely — **0 Binder IPCs and 0 tree walks during normal phone usage!**
  2. Remove `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` from `CurbstompService.kt:45` (system Settings/Installer buttons & titles are always important for accessibility).
  3. Debounce `TYPE_WINDOW_CONTENT_CHANGED` self-protection checks (e.g., 200ms per window) and enforce a `pressHome()` cooldown (e.g., 500ms via `isDelayOver`).
  4. Stream-check node text/contentDescription directly during recursion using `contains(..., ignoreCase = true)` without allocating a `MutableList<String>` or `.lowercase()` copies, short-circuiting immediately once a block condition is matched.
  5. Cache `defaultDialerPackage`, `defaultSmsPackage`, and `defaultLauncherPackage` (refreshing on package/role changes or when stale) instead of calling `TelecomManager` and `Telephony.Sms` Binder IPC on every event; move `fallbacks` set to a `companion object` constant.
- **Expected impact:** **High (CRITICAL)** — eliminates >95% of Accessibility Binder IPC calls, node allocations, and CPU wakeups across all everyday apps (*unverified runtime estimate*).
- **Risk:** Medium — must make sure Realme UI 2.0 / ColorOS / AOSP Settings, Package Installer, Device Admin deactivation, Accessibility settings, and Play Store uninstall screens are ALL still included in the self-protection target filter.

#### F-010 — `MainActivity` Rebuilds Entire Adapter & Active-Focus Views on Every Settings Emission (Even in Background)
- **Category:** CPU / Memory / Battery / Jank
- **Location:** `app/src/main/java/com/mhm/curbstomp/ui/activity/MainActivity.kt:55-120, 140-248, 360-550`
- **Problem:**
  1. `lifecycleScope.launch { dataStoreManager.settings.collectLatest { ... } }` runs for the entire `CREATED`–`DESTROYED` lifetime (not gated to `STARTED`), so background emissions still allocate lists, query `PackageManager`, and mutate views.
  2. Every emission calls `ruleAdapter.submitList(...)` → `notifyDataSetChanged()` (full rebind of every visible row) AND calls `updateActiveFocusUI(activeFocus)` which removes and re-inflates up to 8 `ImageView`s via synchronous `packageManager.getApplicationIcon()` on the main thread and cancels/restarts `activeFocusJob`.
  3. `RuleAdapter.onBindViewHolder` calls `packageManager.getApplicationInfo()` + `getApplicationIcon()` synchronously on the main thread with **no caching** every time a rule is expanded or rebound, and allocates 3 `SimpleDateFormat` + 2 `Calendar` instances per bound rule!
- **Fix:**
  1. Gate collection with `repeatOnLifecycle(Lifecycle.State.STARTED)` (or start/stop job in `onStart`/`onStop`).
  2. Skip rebuilding `llActiveFocusApps` when the active focus session's ID, mode, pause state, end time, and app list haven't changed.
  3. Use a bounded icon/label cache (loaded off main thread or cached once) and `DiffUtil` (or skip `notifyDataSetChanged()` when items/usage haven't changed).
  4. Hoist date/time computation out of `onBindViewHolder` (compute once in `submitList`).
- **Expected impact:** **High** for active UI smoothness and memory churn on Helio G85 (*unverified runtime estimate*).
- **Risk:** Low.

---

### Memory & Lifecycle Findings

#### F-011 — `SelectAppsActivity` Launches Uncancellable Icon-Loading Coroutines per Bind With No Cache
- **Category:** Memory / CPU / Jank
- **Location:** `app/src/main/java/com/mhm/curbstomp/ui/activity/SelectAppsActivity.kt:95-113`
- **Problem:** Every `onBindViewHolder` launches a new coroutine on `Dispatchers.Default` calling `packageManager.getApplicationIcon(app.packageName)` with no cache and no cancellation of previous jobs on recycled `ViewHolder`s. Scrolling through 50–100 apps repeatedly decodes icons and spikes heap allocations.
- **Fix:** Add an activity-scoped bounded `LruCache<String, Drawable>` (e.g., max 48 entries, evicted in `onDestroy()`) and store a `var iconJob: Job?` on `ViewHolder`, cancelling it before launching a new load.
- **Expected impact:** **Med** — smoother scrolling, lower peak heap and GC pauses (*unverified runtime estimate*).
- **Risk:** Very Low.

#### F-012 — Unbounded Icon Caches, Leaked `CoroutineScope`, Unused `CrashLogger` Field, and Retained `AppCache`
- **Category:** Memory
- **Location:**
  - `app/src/main/java/com/mhm/curbstomp/utils/PasswordDialog.kt:85-91`
  - `app/src/main/java/com/mhm/curbstomp/ui/activity/CreateRuleActivity.kt:36-37, 294-309`
  - `app/src/main/java/com/mhm/curbstomp/ui/activity/InstantFocusActivity.kt:33-34, 244-259`
  - `app/src/main/java/com/mhm/curbstomp/services/CurbstompService.kt:23, 35-38, 260, 344`
  - `app/src/main/java/com/mhm/curbstomp/utils/AppCache.kt:10-33`
- **Problem:**
  1. `PasswordDialog.kt:85` uses an unmanaged `CoroutineScope(Dispatchers.IO).launch` capturing `context` and `dialog`; if the host Activity finishes before write completes, it leaks the Activity and can crash on `dialog.dismiss()`.
  2. `CreateRuleActivity` and `InstantFocusActivity` keep unbounded `mutableMapOf<String, Drawable>()` and load icons synchronously on the main thread.
  3. `CurbstompService` allocates an unused `crashLogger = CrashLogger(this)` in `onCreate()` (retaining a Service context) and creates a new `Handler(Looper.getMainLooper())` on every `Toast`.
  4. `AppCache.cachedApps` retains the full installed-app list statically forever in the process (even when only `CurbstompService` is running in background!) and never refreshes when apps are installed/uninstalled.
- **Fix:**
  1. Use `lifecycleScope` (via `ComponentActivity` / `LifecycleOwner`) in `PasswordDialog`, guard `dialog.isShowing` before `dismiss()`.
  2. Clear `appIconCache`/`appLabelCache` in `onDestroy()` (or use shared bounded `LruCache`) and clear `AppCache` on `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` in `Curbstomp` (`Application`).
  3. Remove unused `crashLogger` from `CurbstompService` and reuse a single `mainHandler = Handler(Looper.getMainLooper())` field.
- **Expected impact:** **Med** — lower idle PSS RAM when UI is closed and zero dialog/coroutine leaks (*unverified runtime estimate*).
- **Risk:** Very Low.

---

### Startup Findings

#### F-013 — Eager Unused `androidx.startup` Initializers Run on Every Process Start
- **Category:** Startup / Memory
- **Location:** Merged `AndroidManifest.xml` (`androidx.startup.InitializationProvider`), `app/src/main/AndroidManifest.xml`
- **Problem:** Every time the OS starts the app process — including background restarts of `CurbstompService` after Realme UI kills it — `androidx.startup.InitializationProvider` eagerly runs:
  - `androidx.emoji2.text.EmojiCompatInitializer` (spawns background thread & queries GMS font/emoji provider; unnecessary since Curbstomp uses plain ASCII/English UI text).
  - `androidx.lifecycle.ProcessLifecycleInitializer` (unused — app does not observe `ProcessLifecycleOwner`).
- **Fix:** Remove `EmojiCompatInitializer` and `ProcessLifecycleInitializer` via `<provider android:name="androidx.startup.InitializationProvider" ...>` with `tools:node="remove"` on those `<meta-data>` entries in `app/src/main/AndroidManifest.xml` (keep `ProfileInstallerInitializer` so ART baseline profiles remain active).
- **Expected impact:** **Med** — faster cold start and lower background-service-only process RAM/threads, plus allows R8 to strip `androidx.emoji2` and `lifecycle-process` classes (~80+ KB DEX!) (*unverified estimate*).
- **Risk:** Low — standard Android system emojis still render normally via platform font fallback; only backported EmojiCompat rendering for pre-API-29 emojis is disabled.

#### F-014 — Downloadable Google Fonts (`Inter`, `Playfair Display`) Trigger GMS IPC on Launch *(Requires User Decision)*
- **Category:** Startup / Battery / Size
- **Location:** `app/src/main/res/font/inter.xml`, `playfair_display.xml`, `app/src/main/res/values/font_certs.xml` (3.7 KB), `themes.xml:38`, and 58 layout references
- **Problem:** Every Activity theme and 58 TextViews reference `@font/inter` or `@font/playfair_display` via `com.google.android.gms.fonts`. On cold start this triggers synchronous/asynchronous IPC to Google Play Services (`com.google.android.gms`) to fetch font file descriptors, adding startup latency and waking GMS.
- **Fix (Optional Tradeoff):** Replace downloadable Google Fonts with built-in Android system fonts (`sans-serif` / `serif`), eliminating GMS IPC and `font_certs.xml`.
- **Expected impact:** **Med** on cold start & offline reliability, **Low** on APK size (~5 KB).
- **Risk / Tradeoff:** Changes UI typography slightly from Inter/Playfair Display to Roboto/Noto Serif. **Will NOT be applied without explicit user approval.**

---

### Storage Findings

#### F-015 — Unbounded Growth of `Settings.appUsageMillis` Map
- **Category:** Storage / Memory / CPU
- **Location:** `app/src/main/java/com/mhm/curbstomp/data/models/Settings.kt:12`, `CurbstompService.kt:200-235`
- **Problem:** `appUsageMillis` stores keys of the form `"${rule.id}_${YYYYMMDD}_${packageName}"` and **never deletes old dates or deleted rule IDs**. Over weeks/months, `settings.json` grows linearly forever, slowing every read/write and bloating heap memory.
- **Fix:** Prune any `appUsageMillis` keys whose date is older than yesterday (preserving today + yesterday for cross-midnight rules) or whose `ruleId` no longer exists in `rules`, whenever saving usage or rules.
- **Expected impact:** **Med** — keeps `settings.json` bounded to a few KB indefinitely.
- **Risk:** Very Low (old day counters are never displayed or checked anywhere in the app).

#### F-016 — Unbounded `CrashLogger` File Growth & Dead Method
- **Category:** Storage / Size
- **Location:** `app/src/main/java/com/mhm/curbstomp/CrashLogger.kt:12-38`
- **Problem:** `crash_log.txt` is opened in append mode forever with no size cap. `logNonFatalError()` is never called anywhere.
- **Fix:** If `logFile.length() > 64 * 1024`, overwrite/truncate before appending; remove dead `logNonFatalError()`; use `Locale.US` for timestamp formatting; ensure `FileWriter` is closed cleanly via `.use {}`.
- **Expected impact:** **Low** — prevents unbounded internal storage growth.
- **Risk:** Very Low.

---

### Correctness, Security & Rendering Findings

#### F-017 — Paused Instant Focus Sessions Still Block Apps & Still Expire
- **Category:** Correctness
- **Location:** `app/src/main/java/com/mhm/curbstomp/services/CurbstompService.kt:64-68, 139-149`
- **Problem:**
  1. `checkLimits()` checks `if (focus.isActive && currentTimeMillis in focus.startTimeMillis..focus.endTimeMillis)` without checking `!focus.isPaused`. Pausing a focus session in `MainActivity` fails to stop blocking!
  2. `cleanupExpiredInstantFocus()` removes any session where `currentTime > it.endTimeMillis` even if `it.isPaused == true`, silently deleting paused sessions!
- **Fix:** Add `!focus.isPaused` guard in both `checkLimits()` and `cleanupExpiredInstantFocus()`.
- **Expected impact:** **High** (fixes broken core Pause/Resume Instant Focus flow).
- **Risk:** Very Low.

#### F-018 — Locale-Dependent `SimpleDateFormat` & `String.format` Break Time Comparisons on Non-Latin Locales
- **Category:** Correctness / CPU
- **Location:** `CurbstompService.kt:129-131, 187`, `MainActivity.kt:65, 250, 263, 442-449`, `CreateRuleActivity.kt:175, 194, 203-204`
- **Problem:** Using `Locale.getDefault()` in `SimpleDateFormat("HH:mm", ...)` and `"yyyyMMdd"` produces Arabic/Bengali/Persian digits on those locales, breaking `"HH:mm"` comparisons against ASCII `"09:00"` and breaking `YYYYMMDD` keys. Also allocates new `SimpleDateFormat` objects on hot paths.
- **Fix:** Use pure integer minute-of-day arithmetic (`hour * 60 + minute`) and `Locale.US` for formatting/date keys (also eliminates 8 `DefaultLocale` lint warnings and hot-path `SimpleDateFormat` allocations).
- **Expected impact:** **Med** (correctness + fewer allocations).
- **Risk:** Very Low.

#### F-019 — `deviceAdminActivationRequestedAt` Never Set When Requesting Device Admin
- **Category:** Correctness
- **Location:** `app/src/main/java/com/mhm/curbstomp/ui/activity/MainActivity.kt:252-260`, `CurbstompService.kt:322`
- **Problem:** `CurbstompService` checks `System.currentTimeMillis() - currentSettings.deviceAdminActivationRequestedAt < 180000` to avoid blocking the user while activating Device Admin, but `MainActivity` never updates `deviceAdminActivationRequestedAt` before launching `PermissionUtils.requestDeviceAdmin(...)`.
- **Fix:** Persist `dataStoreManager.updateDeviceAdminActivationRequestedAt(System.currentTimeMillis())` before calling `PermissionUtils.requestDeviceAdmin(...)`.
- **Expected impact:** **Med** (prevents false self-protection lockouts during Device Admin activation).
- **Risk:** Very Low.

#### F-020 — Concurrency Race Condition in `CurbstompService`
- **Category:** Correctness / CPU
- **Location:** `app/src/main/java/com/mhm/curbstomp/services/CurbstompService.kt:24, 52-57, 79-86, 267-276`
- **Problem:** `currentSettings` and `currentForegroundPackage` are accessed concurrently from the main thread (`onAccessibilityEvent`) and `Dispatchers.Default` (`serviceScope` coroutines) without `@Volatile` or synchronization, and rapid window-state events launch overlapping `checkLimits()` coroutines that can overwrite each other's `appUsageMillis` updates.
- **Fix:** Mark shared fields `@Volatile` and serialize state/usage mutations through a single `Mutex` (or single-threaded actor/dispatcher) alongside F-008.
- **Expected impact:** **Med** (prevents lost usage updates and race crashes).
- **Risk:** Low.

#### F-021 — Unused Permissions (`INTERNET`, `PACKAGE_USAGE_STATS`) & `android:allowBackup="true"`
- **Category:** Security / Privacy
- **Location:** `app/src/main/AndroidManifest.xml:5-19`
- **Problem:**
  1. `android.permission.INTERNET` is declared despite zero network calls (downloadable fonts are served via GMS IPC, not app sockets).
  2. `android.permission.PACKAGE_USAGE_STATS` is declared despite zero `UsageStatsManager` usage.
  3. `android:allowBackup="true"` permits ADB/cloud backup & restore of `settings.json` (which contains `passwordHash` and active rules — potential anti-tamper bypass).
- **Fix:** Remove `INTERNET` and `PACKAGE_USAGE_STATS` permissions from `AndroidManifest.xml`. (Note: flipping `allowBackup` to `false` disables cloud backup of rules — confirm if user wants `allowBackup="false"` or keep `true`.)
- **Expected impact:** **Med** (security & privacy posture; genuine offline permission surface).
- **Risk:** Very Low for removing unused permissions; minor behavioral tradeoff for `allowBackup`.

#### F-022 — Persistent `Theme.Curbstomp.Splash` Window Background Causes Overdraw Across 3 Activities
- **Category:** CPU / Rendering / Memory
- **Location:** `app/src/main/AndroidManifest.xml:27, 41, 46`, `app/src/main/res/layout/activity_*.xml`
- **Problem:** `MainActivity`, `CreateRuleActivity`, and `InstantFocusActivity` all use `android:theme="@style/Theme.Curbstomp.Splash"` in the manifest and never reset the theme/windowBackground after launch, causing `splash_background.xml` (containing `@mipmap/ic_launcher`) to remain drawn behind the opaque root layout (5 `[Overdraw]` lint warnings).
- **Fix:** Only use `Theme.Curbstomp.Splash` on `MainActivity` and call `setTheme(R.style.Theme_Curbstomp)` before `super.onCreate(savedInstanceState)` (or clear window background after content inflation); set `CreateRuleActivity` and `InstantFocusActivity` to `Theme.Curbstomp` directly and remove redundant root backgrounds where the window already paints `#09090B`.
- **Expected impact:** **Low** (reduces GPU overdraw & avoids inflating launcher icon drawable on sub-activity launches).
- **Risk:** Very Low.

---

## Tradeoffs Requiring User Decision Before Phase 3

Per Section 5 Rule 6 and Section 8 ("Don't change app behavior, UI, or features without asking"):
1. **F-014 (Downloadable Google Fonts):** Should we keep `Inter` and `Playfair Display` (via GMS Downloadable Fonts), or switch to built-in system fonts (`sans-serif` / `serif`) to eliminate GMS IPC at cold start?
2. **F-021 (`android:allowBackup`):** Should we set `android:allowBackup="false"` (hardens password/rules against backup-restore bypass) or keep `android:allowBackup="true"`?
3. **F-006 (Launcher Icon Format):** Converting the 328 KB traced vector `ic_launcher_foreground.xml` (and 52 KB monochrome) to crisp high-res WebP (from the existing `mipmap-xxxhdpi` assets) preserves the exact visual icon while saving ~250 KB and eliminating 68 `VectorPath` warnings + cold-start vector parsing. Confirm if WebP adaptive icon layers are OK (they work identically on API 26+).
