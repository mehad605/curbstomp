<div align="center">

<img src="artwork/curbstomp.svg" alt="Curbstomp" width="120"/>

# Curbstomp

**App blocker with scheduled rules and focus sessions.**

[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://android-arsenal.com/api?level=26)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## What it does

Curbstomp lets you define **rules** that control which apps you can use and when. A rule has a time window, a list of days, and a list of apps with per-app time limits. Each rule can be set to **block only those apps** or **only allow those apps**.

There's also **instant focus** — pick a duration and choose apps to block or allow on the spot.

All settings can be locked behind a **password**, and you can enable **Device Admin** protection to prevent uninstall, force stop, or data clear.

---

## Features

- **Time-based rules** — Block or allow specific apps during set hours on set days
- **Instant focus** — Start a timed session that blocks or allows selected apps
- **Password lock** — Gate settings, focus exit, and rule toggling behind a password
- **Anti-uninstall** — Device Admin + optional "Extra Harden" to prevent removal
- **Per-app time limits** — Set daily usage caps per app within each rule
- **Warning screen** — Configurable delay before proceeding past a block

---

## Building

```bash
git clone https://github.com/mehad605/curbstomp.git
cd curbstomp
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/Curbstomp-debug.apk`

**Requirements:** Android Studio · JDK 17 · Android SDK 34

---

## License

MIT — see [LICENSE](LICENSE).
