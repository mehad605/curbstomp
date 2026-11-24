<div align="center">

<img src="artwork/curbstomp.svg" alt="Curbstomp" width="120"/>

# Curbstomp

**Lightweight, event-driven Android app blocker with scheduled rules, Instant Focus, and tamper-resistant strict mode.**

[![API](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen.svg)](https://developer.android.com/about/versions/oreo)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/mehad605/curbstomp)](https://github.com/mehad605/curbstomp/releases)

</div>

---

Curbstomp is a 100% on-device Android app blocker designed for strict distraction control without accounts, cloud sync, analytics, or network access (`INTERNET` permission is not requested).

## Features

- **Scheduled Rules (Blocklist & Allowlist)** — Block specific apps or allow *only* chosen apps during repeating weekly time windows, including overnight ranges (e.g. `22:00–07:00`) and 24-hour rules.
- **Per-App Daily Quotas** — Assign an exact daily time allowance to any blocked app inside a rule (or `0 min` for an immediate block). When the quota runs out, Curbstomp immediately returns you to the home screen.
- **Instant Focus** — Launch an on-demand focus session (`30 MIN`, `1 HR`, or custom duration) in blocklist or allowlist mode, with a live `HH:MM:SS` dashboard countdown, pause/resume, time extension, and live app editing.
- **Strict Mode Password Lock** — Protect rule weakening, rule disabling/deletion, Instant Focus stopping/pausing, and protection toggles behind a salted **PBKDF2-HMAC-SHA256** password with persisted exponential brute-force lockout (legacy SHA-256 hashes auto-upgrade transparently on first verification). Tightening rules or adding focus time remains friction-free.
- **Anti-Tamper & Extra Harden** — Combines Device Admin (**Uninstall Invincibility**), locale-independent Accessibility self-protection (guards against force-stop, uninstall, disabling Accessibility, or revoking Device Admin), and optional **Extra Harden** (blocks Android Settings across stock and OEM ROMs while any rule or focus session is active). Critical system apps (dialer, SMS, launcher, SystemUI) are always exempted.
- **Minimal Footprint & Zero Idle Polling** — Event-driven `AccessibilityService` with `elapsedRealtime()` accounting, exact single-shot deadline timers, and Screen ON/OFF awareness: **0 periodic idle background ticks**, **~1.35 MB** universal APK (**~929 KB** download), **1 DEX**, and **0 native `.so` libraries**.

---

## Installation & Setup

1. Download the latest signed APK from [**GitHub Releases**](https://github.com/mehad605/curbstomp/releases) (verify with `SHA256SUMS.txt` if desired) and install it on Android 8.0+ (API 26+).
2. Open **Curbstomp** and enable **Force Kill Engine** (grants Accessibility Service permission — required for enforcement).
3. *(Recommended on OEM ROMs such as Realme UI / ColorOS / MIUI / One UI)* In **App info → Battery**, enable **Allow background activity** (and **Auto-launch** if available) so the OS does not kill the Accessibility service in the background.
4. *(Optional)* Configure **Set Password**, **Uninstall Invincibility** (Device Admin), and **Extra Harden** once you have tested your rules.

---

## Privacy & Security

- **Offline by design** — Requests no `INTERNET` permission and sends zero telemetry.
- **Atomic local storage** — Persists state to `filesDir/datastore/settings.json` via `AtomicFile` + a coroutine `Mutex`.
- **Scoped backups** — Android backup/transfer rules (`backup_rules.xml`, `data_extraction_rules.xml`) back up user rules/settings while excluding local crash logs and password lockout counters.
- **Hardened password storage** — 120,000-iteration PBKDF2-HMAC-SHA256 with a 16-byte random salt, constant-time comparison, and persisted exponential backoff after repeated failed attempts.

---

## Architecture & Codebase Map

Curbstomp uses a clean, zero-reflection layered architecture wired through a manual `AppContainer` in `Curbstomp.kt`:

```text
app/src/main/java/com/mhm/curbstomp/
├── Curbstomp.kt                 # Application entry point, lazy AppContainer DI, and onTrimMemory cache trimming
├── CrashLogger.kt               # Bounded on-device crash logger
├── data/
│   ├── models/                  # Immutable models: Settings, Rule, AppLimit, InstantFocus, AppInfo
│   ├── local/                   # SettingsJson (schema serializer) & SettingsStore (AtomicFile persistence + Mutex)
│   └── repository/              # SettingsRepository (reactive StateFlow API) & InstalledAppsRepository (cached launcher list)
├── domain/                      # Pure Kotlin business logic (zero Android framework deps; fast JVM unit tests)
│   ├── RuleEngine.kt            # Schedule matching (incl. overnight), usage accounting, exact wake-delay computation
│   ├── StrictModePolicy.kt      # Determines when rule/focus edits weaken restrictions and require password confirmation
│   ├── PasswordHasher.kt        # Salted PBKDF2-HMAC-SHA256 hashing, constant-time compare, legacy SHA-256 upgrade
│   ├── PasswordLockoutPolicy.kt # Persisted exponential brute-force lockout with clock-jump protection
│   └── SelfProtectionDetector.kt# Locale-independent (viewId + className + text) anti-tamper bitmask detector
├── services/
│   ├── BaseBlockingService.kt   # Shared AccessibilityService helpers (GLOBAL_ACTION_HOME + kick cooldown)
│   ├── SystemPackageResolver.kt # Cached resolution of launcher, dialer, SMS, OEM Settings & PackageInstaller packages
│   └── CurbstompService.kt      # Event-driven AccessibilityService (zero idle polling, exact-deadline timers)
├── receivers/
│   └── CurbstompDeviceAdminReceiver.kt
└── ui/
    ├── activity/                # Thin Activities: MainActivity, CreateRuleActivity, InstantFocusActivity, SelectAppsActivity
    ├── adapter/                 # RuleAdapter, AppLimitAdapter, SelectAppsAdapter
    └── common/                  # ActiveFocusCardController, AppIconLoader (LruCache), PasswordDialog, TimeDialogs, PermissionUtils
```

### Data Flow

1. **Persistence (`data/local`)** — `SettingsStore` reads and writes `datastore/settings.json` atomically via `AtomicFile` + a `Mutex` and exposes a hot `StateFlow<Settings>`.
2. **Mutations (`data/repository`)** — UI and services mutate state exclusively through semantic methods on `SettingsRepository`.
3. **Pure Domain (`domain`)** — `RuleEngine`, `StrictModePolicy`, `PasswordHasher`, `PasswordLockoutPolicy`, and `SelfProtectionDetector` have no Android dependencies and are verified by fast JVM unit tests under `app/src/test/java/`, complemented by Robolectric tests covering `AtomicFileSettingsStorage`, `SystemPackageResolver`, `PasswordDialog`, and all four `Activity` lifecycles/rotations.
4. **Enforcement (`services`)** — `CurbstompService` observes `SettingsRepository.settings` and reacts to `TYPE_WINDOW_STATE_CHANGED`, screen ON/OFF, and clock-change broadcasts, scheduling a single exact coroutine timer only when the foreground app has a finite remaining quota or upcoming rule transition.

---

## Building, Testing & Linting

**Requirements:** JDK 17 · Android SDK 34 (`compileSdk = 34`, `minSdk = 26`)

```bash
# Run all 51 JVM + Robolectric unit tests across 12 suites (no device/emulator required)
./gradlew test

# Compile or run on-device AndroidX instrumented smoke tests
./gradlew compileDebugAndroidTestKotlin
./gradlew connectedDebugAndroidTest

# Run Android Lint (abortOnError = true)
./gradlew lint

# Run release-pipeline script self-tests
bash .github/scripts/test-release-scripts.sh

# Build debug APK
./gradlew assembleDebug

# Build minified & resource-shrunk release APK
./gradlew assembleRelease

# Install debug build, enable AccessibilityService via adb, and launch
./gradlew installAndGrantAccessibilityDebug
```

Build outputs:
- Debug APK: `app/build/outputs/apk/debug/Curbstomp-debug.apk`
- Release APK: `app/build/outputs/apk/release/Curbstomp-release.apk`

---

## Releasing

Pushing a SemVer tag (e.g. `v1.1.0` or `v1.2.0-rc.1`) or running **Build & Release Curbstomp** via `workflow_dispatch` triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which:

1. Validates SemVer format and computes a monotonic Android `versionCode` via `.github/scripts/resolve-release-version.sh`.
2. Runs unit tests, instrumented test compilation, Android Lint, and release script self-tests.
3. Builds the signed release APK (`-PREQUIRE_RELEASE_SIGNING=true`) and verifies its signature, package/version metadata, and zero-`.so` constraint with `.github/scripts/verify-release-apk.sh`.
4. Publishes the APK + `SHA256SUMS.txt` to GitHub Releases with SLSA build provenance attestation and archives the R8 `mapping.txt` artifact for 90 days.

---

## License

MIT — see [LICENSE](LICENSE).
