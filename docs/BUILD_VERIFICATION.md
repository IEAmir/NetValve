# Build & verification record

This records exactly what was verified during development and how, so you can
reproduce it.

## Toolchain used
- JDK 17 (Temurin 17.0.19)
- Kotlin 2.0.21
- Gradle 8.10.2 (wrapper included)
- Android Gradle Plugin 8.7.2
- Android SDK: compileSdk/target 35, build-tools 35.0.0 (AGP also pulled 34.0.0), minSdk 29
- Default (`loopback`) engine — no Go/NDK toolchain required

## What was verified ✅

| Check | Command | Result |
|---|---|---|
| **App compiles + APK builds** | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** → `app/build/outputs/apk/debug/app-debug.apk` (~60 MB). A copy is included at `prebuilt/app-debug.apk`. |
| **Unit tests pass (AGP)** | `./gradlew :app:testDebugUnitTest` | **33 tests, 0 failures** across 7 suites. |
| **Instrumentation test compiles** | `./gradlew :app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** (run on a device/emulator with `connectedDebugAndroidTest`). |
| **Framework-free core (standalone JVM)** | `kotlinc` + JUnit console | 32 core files compile; **33/33 unit tests pass** on a plain JVM (no Android), confirming the engine is decoupled and portable. |

Unit test suites (all green):
`TokenBucketTest` (5), `PacingQueueTest` (5), `PolicyEvaluatorTest` (6),
`RuleCompilerTest` (6), `UidResolverFallbackTest` (4), `PersistenceTest` (4),
`FormatTest` (3).

## What was NOT built here (and why)
- **The gVisor netstack AAR** (`-Pnetvalve.netstack=true`) — building it needs the
  **Go toolchain + Android NDK**, which were not provisioned in the build
  environment used. The Go sources, `go.mod`, and `netstack/build-aar.sh` are
  included and documented; `build-aar.sh` runs `go build ./...` before binding so
  gVisor API drift fails fast. See `netstack/README.md` and `docs/LIMITATIONS.md`.
  The default `loopback` build (verified above) exercises everything else.
- **On-device instrumentation run** — the instrumentation test *compiles*; running
  it needs an emulator/device (`./gradlew :app:connectedDebugAndroidTest`).

## Low-memory build note
For the constrained (4 GB) build host, `gradle.properties` is tuned to a single
JVM (`org.gradle.daemon=false`, `kotlin.compiler.execution.strategy=in-process`,
`-Xmx2600m`). On a normal dev machine you can re-enable the daemon/parallel builds
for speed.

## Reproduce
```bash
# 1. point to your SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties
# 2. build + test (default loopback engine)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
