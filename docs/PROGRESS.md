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
