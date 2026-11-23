# Phase 0: Project Analysis — Curbstomp

## 1. Purpose

**Curbstomp** (`com.mhm.curbstomp`, v1.1.0 / versionCode 50) is a 100% on-device Android app blocker and digital self-control tool. It is designed for users who want strict, tamper-resistant control over distracting apps without accounts, telemetry, or cloud dependencies.

### Target Audience
- Users seeking distraction control via recurring app-blocking schedules, per-app daily usage caps, or on-demand focus sessions.
- Users who want friction against bypassing their own blocks (password-gated weakening/disabling, Device Admin anti-uninstall, and accessibility-based self-protection).

### Core User Flows
1. **Onboarding & Permissions (`MainActivity`)**
   - Enable the `CurbstompService` Accessibility Service (required to detect foreground apps, enforce blocks via `GLOBAL_ACTION_HOME`, and run self-protection).
   - Optionally enable Device Admin (`CurbstompDeviceAdminReceiver`) for anti-uninstall protection.
   - Optionally enable **Extra Harden**, which blocks `com.android.settings` altogether.
   - Optionally set or update a SHA-256 hashed **Password** (bottom-sheet dialog) that gates relaxing or disabling restrictions.
2. **Scheduled Rules (`MainActivity` → `CreateRuleActivity` → `SelectAppsActivity`)**
   - Create or edit a named rule with a start/end time window (supports overnight/cross-midnight ranges and 24-hour rules when `startTime == endTime`), repeating days of the week (`Calendar.SUNDAY..SATURDAY`), mode (**Block These** blacklist or **Allow Only These** whitelist), and a list of apps with optional per-app daily time limits (`0 min` = immediate block).
   - View rules split into **Active** and **Inactive** sections on the home screen, expand each rule to inspect per-app usage progress bars (`used` / `remaining`), toggle active state, edit, or delete.
   - Weakening an active rule (toggling off, deleting, shortening its window, switching blacklist/whitelist mode, removing a blocked app, adding a whitelisted app, or increasing a per-app limit) requires password verification if a password is set. Tightening a rule or creating a new one does not.
3. **Instant Focus Sessions (`MainActivity` → `InstantFocusActivity` → `SelectAppsActivity`)**
   - Start a timed focus session via quick-start presets (`30m`, `1h`) or custom duration (`InstantFocusActivity`), selecting target apps and choosing **Allow Only These** or **Block These**.
   - While active, `MainActivity` displays a live `HH:MM:SS` countdown card with app icons and controls to **Pause/Resume**, **Stop**, **Edit Apps**, or **Add Time** (`+10m`, `+30m`, `+1h`, `+2h`, or custom).
   - Pausing, stopping, shortening duration, changing mode, or making the app list less strict requires password verification (if set); resuming, extending time, or tightening the app list does not.
4. **Background Enforcement & Self-Protection (`CurbstompService`)**
   - Continuously observes foreground window changes and tracks per-second usage against active scheduled rules and instant focus sessions.
   - Immediately sends the user to the home screen (`GLOBAL_ACTION_HOME`) with a `Toast` explanation when a blocked/disallowed app is opened or reaches its limit, while exempting critical system apps (Curbstomp itself, SystemUI, default launcher, default dialer/SMS, and hardcoded dialer/contacts/messaging fallbacks).
   - Monitors window/content events on non-exempt apps for UI text indicating attempts to uninstall, force-stop, clear data, disable Accessibility for Curbstomp, or deactivate Device Admin, and kicks the user to Home.

> **Note:** `README.md` mentions a configurable warning screen, and `Constants.kt` contains unused `WARNING_SCREEN_MODE_*` constants, but no warning screen UI is implemented in the current codebase; blocking immediately triggers `GLOBAL_ACTION_HOME` + `Toast`.

---

## 2. Architecture

### Modules
Single-module Android application:
- `:app` (`com.mhm.curbstomp`)

### Entry Points & Manifest Components (`app/src/main/AndroidManifest.xml`)
| Component | Type | Exported | Purpose |
|---|---|---|---|
| `Curbstomp` | `Application` | N/A | Installs `CrashLogger` as the default `Thread.UncaughtExceptionHandler` (`filesDir/crash_log.txt`). |
| `MainActivity` | `Activity` (`MAIN`/`LAUNCHER`) | `true` | Dashboard: permission/hardening switches, Instant Focus quick-start / active session banner, password management, and expandable Active/Inactive rules list. |
| `CreateRuleActivity` | `Activity` | `false` | Create/edit scheduled rules (name, 12-hour wheel time pickers, day chips, blacklist/whitelist radio cards, per-app time limits). |
| `InstantFocusActivity` | `Activity` | `false` | Create/edit timed Instant Focus sessions (duration picker, app selection, mode dialog). |
| `SelectAppsActivity` | `Activity` | `false` | Searchable multi-select picker of launcher-visible installed apps, backed by `AppCache`. |
| `CurbstompService` (extends `BaseBlockingService`) | `AccessibilityService` | `true` (`BIND_ACCESSIBILITY_SERVICE`) | Core enforcement engine: listens to `TYPE_WINDOW_STATE_CHANGED` & `TYPE_WINDOW_CONTENT_CHANGED`, runs a 1-second coroutine ticker for usage tracking/limits, cleans up expired focus sessions, and performs screen-text self-protection. |
| `CurbstompDeviceAdminReceiver` | `DeviceAdminReceiver` | `true` (`BIND_DEVICE_ADMIN`) | Empty policy device admin receiver used solely to block uninstall/force-stop; shows toast on enable/disable. |

- **Workers / JobServices / ContentProviders (declared by app):** None. (Note: transitive libraries such as `androidx.startup` / `Shizuku` may merge providers into the final manifest — to be verified in Phase 1.)
- **Declared Permissions:**
  - `android.permission.INTERNET` (no direct app network calls; downloadable Google Fonts in `res/font/` rely on Google Play Services font provider rather than direct app sockets).
  - `android.permission.QUERY_ALL_PACKAGES` (used by `AppCache` / `PackageManager` to list installed apps and fetch labels/icons).
  - `android.permission.PACKAGE_USAGE_STATS` (declared in manifest, not referenced in Kotlin code).

### Data & Storage Layer
- **Models (`com.mhm.curbstomp.data.models`)**: `Settings`, `Rule`, `InstantFocus`, `AppLimit`.
- **Persistence (`com.mhm.curbstomp.utils.DataStoreManager`)**:
  - Uses Jetpack `MultiProcessDataStoreFactory` storing a single JSON file at `filesDir/datastore/settings.json`, serialized/deserialized via `Gson`.
  - All state (rules, instant focus sessions, password hash, hardening flags, and the `appUsageMillis` map keyed by `RuleId_YYYYMMDD_PackageName`) lives in this single `Settings` document.
- **In-memory caches**:
  - `AppCache` singleton holds `List<AppItem>` of installed launcher apps for the process lifetime.
  - `CreateRuleActivity` and `InstantFocusActivity` each keep local `appIconCache` / `appLabelCache` maps; `MainActivity` queries `PackageManager` directly on bind.
- **Crash logs**: `CrashLogger` appends stack traces to `filesDir/crash_log.txt` without rotation/size cap.

### Main Dependencies (`app/build.gradle.kts` & `gradle/libs.versions.toml`)
| Dependency | Version | Actually Used in App Code? |
|---|---|---|
| `androidx.core:core-ktx` | `1.13.1` | Yes |
| `androidx.appcompat:appcompat` | `1.7.0` | Yes (`AppCompatActivity`, `AlertDialog`, `SearchView`) |
| `com.google.android.material:material` | `1.12.0` | Yes (Material3 theme, `BottomSheetDialog`, `MaterialButton`, `MaterialCardView`, `Chip`, `SwitchMaterial`, `TextInputLayout`, `MaterialToolbar`, `MaterialCheckBox`) |
| `androidx.activity:activity` | `1.9.2` | Yes (`ActivityResultContracts`) |
| `androidx.constraintlayout:constraintlayout` | `2.1.4` | Yes (`activity_select_apps.xml` / layouts) |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | `2.10.0` | Transitive `lifecycleScope` (no `ViewModel` subclasses exist) |
| `androidx.fragment:fragment-ktx` | `1.8.9` | No `Fragment` classes in codebase |
| `androidx.datastore:datastore` | `1.2.1` | Yes (`MultiProcessDataStoreFactory`) |
| `com.google.code.gson:gson` | `2.11.0` | Yes (`GsonSerializer` in `DataStore.kt`) |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` (KSP) | `2.6.1` | **No** (no `@Entity`, `@Dao`, or `@Database` in codebase) |
| `com.journeyapps:zxing-android-embedded` | `4.3.0` | **No** (no QR scanning/generation in codebase) |
| `dev.rikka.shizuku:api` & `dev.rikka.shizuku:provider` | `13.1.5` | **No** (no Shizuku imports or calls in codebase) |
| `com.github.PhilJay:MPAndroidChart` | `v3.1.0` | **No** (no chart views or imports in codebase) |
| `nl.joery.timerangepicker:timerangepicker` | `1.0.0` | **No** (custom `NumberPicker` bottom sheets are used instead) |
| `junit`, `androidx.test.ext:junit`, `espresso-core` | `4.13.2` / `1.2.1` / `3.6.1` | Test-only (currently no `src/test` or `src/androidTest` files exist) |

### Unused / Dormant Source Files Observed
- `Constants.kt`, `utils/AppFilter.kt`, `utils/ColorUtils.kt`, `utils/PackageFinderTools.kt`, `utils/TimeTools.kt` have no live call sites in the app.

---

## 3. Core Functionality List (Regression Contract)

Every optimization in subsequent phases MUST preserve all of the following behaviors on Android 11 (API 30) and above:

1. **Scheduled Rule Management**
   - Create, edit, toggle (ON/OFF), and delete scheduled rules with name, start/end time (including cross-midnight e.g. `22:00`–`06:00` and 24-hour `startTime == endTime`), repeating days of week, blacklist ("Block These") or whitelist ("Allow Only These") mode, and per-app daily usage limits (`0 min` = full block, or `> 0 min`).
   - Persist rules and per-app usage across app restarts and service restarts (including reading existing `settings.json` format cleanly without data loss, or migrating transparently).
2. **Scheduled Rule Enforcement & Usage Tracking**
   - Immediately block disallowed/over-limit apps when opened (`GLOBAL_ACTION_HOME` + user feedback toast).
   - Accurately track foreground usage time for apps with non-zero limits during active rule windows and block the app once `usedMillis >= maxUsageMillis`.
   - Separate usage counters per rule, per active rule date (handling cross-midnight rules attributed to the start day), and per package.
   - Display active vs. inactive rule groupings and expandable per-app usage progress bars in `MainActivity`.
3. **Instant Focus Sessions**
   - Start sessions via `30m`, `1h`, or custom duration with selected apps in either **Allow Only These** or **Block These** mode.
   - Enforce active sessions immediately on app launch and automatically expire sessions once `endTimeMillis` passes.
   - Support live countdown display, **Pause** (preserving `remainingMillis`), **Resume**, **Stop**, **Add Time** (`+10m`/`+30m`/`+1h`/`+2h`/custom), and **Edit Apps** from `MainActivity`.
4. **Password Protection & Anti-Cheat Rules**
   - Set and update a SHA-256 hashed password (updating requires verifying the existing password).
   - Gate all restriction-relaxing actions behind password verification when a password is set: disabling/deleting a rule, shortening a rule's window, switching mode, removing a blacklisted app, adding a whitelisted app, increasing an app's time limit, pausing/stopping/shortening/relaxing an active Instant Focus session, disabling Device Admin, and disabling Extra Harden.
   - Allow restriction-tightening actions (creating rules, enabling rules, resuming focus, adding focus time, adding blacklisted apps, removing whitelisted apps) without a password prompt.
5. **Critical System App Exemptions**
   - Never block Curbstomp itself, `com.android.systemui`, the default launcher, the default dialer, the default SMS app, or emergency/phone/contacts/messaging fallback packages.
6. **Self-Protection, Device Admin & Extra Harden**
   - Accessibility status toggle (links to system Accessibility settings) and Device Admin activation/deactivation flow (`CurbstompDeviceAdminReceiver`).
   - **Extra Harden** toggle: when enabled, blocks opening `com.android.settings`.
   - **Accessibility Self-Protection**: prevents disabling Curbstomp's Accessibility service, force-stopping, clearing data, uninstalling Curbstomp, or deactivating Device Admin (when `antiUninstallEnabled` is true and outside the activation grace window), while not breaking normal apps/browsers/chat apps.
7. **App Picker (`SelectAppsActivity`)**
   - Lists installed launcher apps (excluding Curbstomp), supports live text search by label or package name, preserves pre-selected apps, displays app icons without mis-recycling, and returns the selected package list.
8. **Crash Logging & Visual Theme**
   - Captures uncaught exceptions to local storage safely (bounded in future optimizations) and retains the dark "Obsidian Gallery" Material 3 UI look and layout behavior.

---

## 4. Tech Stack

| Dimension | Details |
|---|---|
| **Language** | Kotlin `1.9.24` (JVM target `1.8`, `sourceCompatibility`/`targetCompatibility` = Java `1.8`) |
| **UI Toolkit** | Android Views (XML layouts) with ViewBinding (`viewBinding = true`), Material Components 3 (`Theme.Material3.DayNight.NoActionBar`), `RecyclerView`, `ConstraintLayout`, `BottomSheetDialog`, `NumberPicker`. **No Jetpack Compose.** |
| **Build System** | Gradle `8.10.2` (Kotlin DSL `*.gradle.kts` + version catalog `gradle/libs.versions.toml`), AGP `8.7.2`, KSP `1.9.24-1.0.20` |
| **SDK Targets** | `minSdk = 26` (compatible with target device Android 11 / API 30), `compileSdk = 34`, `targetSdk = 34` |
| **Release Build Config** | `isMinifyEnabled = false`, `isShrinkResources` unset (defaults to `false`), ABI splits disabled (`splits.abi.isEnable = false`), `buildConfig = true`, `android.nonTransitiveRClass = true` |
| **Concurrency** | Kotlin Coroutines (`Dispatchers.Default`, `Dispatchers.IO`, `Dispatchers.Main`, `lifecycleScope`, custom `CoroutineScope` in `CurbstompService`, `Flow` / `collectLatest`) |
| **DI** | None (manual instantiation of `DataStoreManager` with a companion singleton `DataStore<Settings>`, object singletons `AppCache`, `PasswordDialog`, `PermissionUtils`) |
| **Persistence / DB** | Jetpack DataStore (`androidx.datastore:datastore:1.2.1` via `MultiProcessDataStoreFactory`) + `Gson 2.11.0` JSON serialization to `filesDir/datastore/settings.json`. Room `2.6.1` is declared as a dependency + KSP processor but unused. |
| **Networking** | None in app code. Google Fonts (`Inter`, `Playfair Display`) are configured as downloadable fonts via `com.google.android.gms.fonts` XML providers (`res/font/*.xml`, `res/values/font_certs.xml`). |
| **Image Loading** | None (Android `PackageManager.getApplicationIcon(...)` drawables loaded directly; vector drawables + PNG mipmaps + `stippled_dot.png`). |
| **CI / Release** | GitHub Actions (`.github/workflows/release.yml`): JDK 17, runs `./gradlew lint`, `./gradlew test`, `./gradlew assembleRelease` with keystore env vars and SHA-256 provenance attestation. |
