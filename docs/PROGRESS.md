# Phase 3: Iterative Optimization Progress Log — Curbstomp

> **Baseline (commit `0d821a2`):**
> - Release APK size: **6,332,773 B** (6.04 MB)
> - Download size: **5,310,043 B** (5.06 MB)
> - DEX: **2 files**, **4,499,511 B** raw in APK (**79,068** method refs / **65,366** defined methods)
> - Native libs: **4 `.so` files** (**36,192 B** raw)
> - Resources: `resources.arsc` **1,045,784 B** + `res/` **574,084 B** (877 files, 89 string locales)
> - Dependencies (`releaseRuntimeClasspath`): **18 direct** / **103 distinct resolved artifacts**
> - Lint: **0 errors, 327 warnings** | Unit tests: **0 tests**

| Iter | Finding ID | Change | Metric before → after | Delta | Regression check | Keep/Revert |
|---|---|---|---|---|---|---|
| 1 | F-002 | Remove 7 unused dependencies (`Room`, `ZXing`, `Shizuku`, `MPAndroidChart`, `TimeRangePicker`, `fragment-ktx`, `lifecycle-viewmodel-ktx:2.10.0`) + KSP plugin; add `lifecycle-runtime-ktx:2.8.7` | APK `6,332,773 → 5,691,733 B`; DL `5,310,043 → 4,684,689 B`; DEX refs `79,068 → 70,072`; Deps `18/103 → 10/79` | APK `-626.0 KB` (`-10.1%`); DL `-610.7 KB`; Deps `-24` | `lint` (0 err, 314 warn), `test`, `assembleRelease` PASS | **Keep** |
| 2 | F-001 | Enable R8 full mode (`isMinifyEnabled = true`), `isShrinkResources = true`, and safe ProGuard rules for Gson models & Log stripping | APK `5,691,733 → 2,152,380 B`; DL `4,684,689 → 1,412,255 B`; DEX `2 files (70,072 refs) → 1 file (12,983 refs)` | APK `-3,456.4 KB` (`-62.2%`); DL `-3,195.7 KB`; DEX refs `-57,089` | `lint` (0 err, 314 warn), `test`, `assembleRelease` PASS | **Keep** |
| 3 | F-004 | Replace `MultiProcessDataStoreFactory` + `Gson` with `AtomicFile` + `StateFlow` + platform `org.json` (`SettingsJson` + unit tests); remove Gson R8 keep rules | APK `2,152,380 → 2,007,958 B`; DL `1,412,255 → 1,335,800 B`; DEX refs `12,983 → 11,990`; Native `.so` `4 (36 KB) → 0 (0 B)`; Deps `10/79 → 8/67` | APK `-141.0 KB` (`-6.7%`); DL `-74.7 KB`; Native libs `-100%` | `lint` (0 err, 312 warn), `test` (3/3 PASS), `assembleRelease` PASS | **Keep** |
| 4 | F-005 | Restrict packaged locales to English (`resourceConfigurations += listOf("en")`) | APK `2,007,958 → 1,754,326 B`; DL `1,335,800 → 1,272,696 B`; `resources.arsc` `726,964 → 473,332 B`; Locales `89 → 1` | APK `-247.7 KB` (`-12.6%`); DL `-61.6 KB` | `lint` (0 err, 312 warn), `test` (3/3 PASS), `assembleRelease` PASS | **Keep** |
| 5 | F-003 | Replace 3 root `ConstraintLayout`s (`activity_main`, `activity_create_rule`, `activity_instant_focus`) with vertical `LinearLayout`s and remove direct `constraintlayout` dependency | APK `1,754,326 → 1,733,274 B`; DL `1,272,696 → 1,257,604 B`; DEX refs `11,990 → 11,949`; Uncompressed DEX `1,194,044 → 1,167,780 B` | APK `-20.6 KB` (`-1.2%`); DL `-14.7 KB`; faster layout inflation | `lint` (0 err, 309 warn), `test` (3/3 PASS), `assembleRelease` PASS | **Keep** |
