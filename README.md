<div align="center">

<img src="artwork/curbstomp.svg" alt="Curbstomp" width="120"/>

# Curbstomp

**Lightweight, event-driven Android app blocker with scheduled rules, Instant Focus, and anti-tamper protection.**

[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://android-arsenal.com/api?level=26)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## Features

- **Scheduled rules** — Blocklist or allowlist apps during custom time windows (including overnight windows) on selected days of the week.
- **Per-app usage limits** — Track daily usage per app within a rule and automatically return home when the quota expires.
- **Instant Focus** — Launch a timed focus session (30 min, 1 hr, or custom) to block or allowlist selected apps on demand, with pause/resume, time extension, and strict-mode app editing.
- **Password protection** — Lock rule weakening, focus exit, Extra Harden, and admin removal behind a salted **PBKDF2-HMAC-SHA256** password with exponential lockout (legacy SHA-256 hashes auto-upgrade transparently on first verification).
- **Anti-uninstall & Extra Harden** — Accessibility self-protection, Device Admin protection, and optional full Settings blocking.
- **Minimal footprint** — Pure Kotlin + Android platform APIs (zero reflection, zero native `.so` libraries, single DEX, zero idle background polling).

---

## Architecture & Codebase Map

Curbstomp uses a clean, zero-reflection layered architecture wired through a manual `AppContainer` in `Curbstomp.kt`:

```text
app/src/main/java/com/mhm/curbstomp/
├── Curbstomp.kt                 # Application entry point + lazy AppContainer DI + onTrimMemory cache trimming
├── CrashLogger.kt               # Bounded on-device crash logger
├── data/
│   ├── models/                  # Immutable value models: Settings, Rule, AppLimit, InstantFocus, AppInfo
│   ├── local/                   # SettingsJson (schema serializer) & SettingsStore (atomic JSON persistence + Mutex)
│   └── repository/              # SettingsRepository (reactive StateFlow API) & InstalledAppsRepository (cached launcher list)
├── domain/                      # Pure Kotlin business logic (100% JVM unit-testable, zero Android framework deps)
│   ├── RuleEngine.kt            # Schedule matching (incl. overnight), usage accounting, wake-delay computation
│   ├── StrictModePolicy.kt      # Decides when rule/focus edits weaken restrictions and require password confirmation
│   ├── PasswordHasher.kt        # Salted PBKDF2-HMAC-SHA256 hashing, constant-time compare, legacy SHA-256 upgrade
│   ├── PasswordLockoutPolicy.kt # Persistent exponential brute-force lockout with clock-jump protection
│   └── SelfProtectionDetector.kt# Locale-independent (viewId + className + text) anti-tamper bitmask detector
├── services/
│   ├── BaseBlockingService.kt   # Shared AccessibilityService helpers (GLOBAL_ACTION_HOME + kick cooldown)
│   ├── SystemPackageResolver.kt # Cached resolution of launcher, dialer, SMS, OEM Settings & PackageInstaller packages
│   └── CurbstompService.kt      # Event-driven AccessibilityService (zero idle polling, exact-deadline timers)
├── receivers/
│   └── CurbstompDeviceAdminReceiver.kt
└── ui/
    ├── activity/                # Thin Activities: MainActivity, CreateRuleActivity, InstantFocusActivity, SelectAppsActivity
    ├── adapter/                 # RuleAdapter, shared AppLimitAdapter, SelectAppsAdapter
    └── common/                  # ActiveFocusCardController, AppIconLoader (LruCache), PasswordDialog, TimeDialogs, PermissionUtils
```

### Data Flow

1. **Persistence (`data/local`)**: `SettingsStore` reads/writes `filesDir/datastore/settings.json` atomically via `AtomicFile` + a `Mutex` and exposes a hot `StateFlow<Settings>`. Backup rules (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`) back up `datastore/settings.json` while excluding local crash logs and lockout counters.
2. **Mutations (`data/repository`)**: UI and services never mutate raw JSON directly — all mutations go through semantic methods on `SettingsRepository`.
3. **Pure Rules (`domain`)**: `RuleEngine`, `StrictModePolicy`, `PasswordHasher`, `PasswordLockoutPolicy`, and `SelfProtectionDetector` have no Android dependencies and are verified by fast JVM unit tests under `app/src/test/java/`, alongside Robolectric tests covering `AtomicFileSettingsStorage`, `SystemPackageResolver`, `PasswordDialog`, and all four `Activity` lifecycles/rotations.
4. **Enforcement (`services`)**: `CurbstompService` observes `SettingsRepository.settings` and reacts to `TYPE_WINDOW_STATE_CHANGED` / screen / time broadcasts, scheduling a single exact coroutine timer only when the foreground app has a finite remaining quota or upcoming rule transition.

---

## Building, Testing & Linting

**Requirements:** JDK 17 · Android SDK 34 (`compileSdk = 34`, `minSdk = 26`)

```bash
# Run JVM + Robolectric unit tests (no device/emulator required)
./gradlew test

# Compile or run AndroidX instrumented smoke tests (connected device/emulator required to execute)
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

## License

MIT — see [LICENSE](LICENSE).
