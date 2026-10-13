# Dependency and license inventory

## In the APK (runtime)
| Component | Version | License | Why |
|---|---|---|---|
| Kotlin standard library | 2.0.21 | Apache-2.0 | Language runtime |
| *(nothing else)* | | | Android framework APIs only. No AndroidX, no analytics, no networking libraries (enforced by `tools/verify/static_checks.py`). |

## Build-time only (never shipped in the APK)
| Tool | Version | Source | SHA-256 (tools/toolchain.lock) | License |
|---|---|---|---|---|
| Kotlin compiler | 2.0.21 | github.com/JetBrains/kotlin releases | `0352c0a4…f2a` | Apache-2.0 |
| bundletool (contains D8/R8 3.3.28 and apksig) | 1.18.2 | github.com/google/bundletool releases | `378b5434…584` | Apache-2.0; R8 BSD-3-Clause |
| Apktool (source of a static linux aapt2 2.19) | 2.11.1 | github.com/iBotPeaches/Apktool releases | `56d59c52…2db` (jar), `e9f05c38…322` (aapt2) | Apache-2.0 (aapt2: AOSP, Apache-2.0) |
| android.jar API 35 and API 26 | — | github.com/Sable/android-platforms @ `1e98db1a` | `4566663c…19b`, `cdc18463…daa` | Android SDK License Agreement — used as a compile-time library only |
| OpenJDK (javac, keytool, jar) | 21 | system | — | GPLv2 + Classpath Exception |
| Python | 3.x | system | — | PSF (static checks, Build Host) |

## Gradle path (Android Studio users)
Android Gradle Plugin 8.13.0, Kotlin Gradle plugin 2.2.20 (Apache-2.0), JUnit 5.13.4 for engine
tests (EPL-2.0, test-only). These are declared in `gradle/libs.versions.toml` but could not be
resolved in the build environment (Google Maven blocked), so the Gradle path is **unverified**.

## Third-party software PocketForge talks to (not bundled)
| Software | How | License |
|---|---|---|
| Termux (optional, user-installed) | Documented `RUN_COMMAND` intent; no code linked | GPLv3 |
| AI providers (optional) | HTTPS JSON APIs, per-request consent | Provider terms |

## Removed in v1.0 (were declared by the v0.1 Compose prototype, never resolvable here)
Jetpack Compose BOM 2025.09.00, Material3, activity-compose, lifecycle, navigation-compose,
DataStore, kotlinx-coroutines, AndroidX test. See ADR-0007.
