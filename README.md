# NetValve

<div align="center">

**Root-free Android Bandwidth Controller, Per-App Internet Speed Limiter, Traffic Shaper and Firewall using Android VpnService.**

Shape, block, schedule and monitor network traffic of individual apps using Android's `VpnService`.
No root. No remote server. No trackers.

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/10)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache--2.0-green.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-29-blue.svg)](https://developer.android.com/about/versions/10)
[![Target SDK](https://img.shields.io/badge/targetSdk-35-blue.svg)](https://developer.android.com/about/versions/15)

</div>

---

## 📑 Table of Contents

1. [What is NetValve?](#-what-is-netvalve)
2. [Why NetValve?](#-why-netvalve)
3. [Features](#-features)
4. [Screenshots](#-screenshots)
5. [How It Works](#-how-it-works)
6. [Architecture](#-architecture)
7. [Installation](#-installation)
8. [Build From Source](#-build-from-source)
9. [Project Structure](#-project-structure)
10. [Performance](#-performance)
11. [Limitations](#-limitations)
12. [Roadmap](#-roadmap)
13. [Contributing](#-contributing)
14. [License](#-license)

---

## 🎯 What is NetValve?

NetValve is an open-source Android bandwidth controller and per-app internet speed limiter built with Kotlin. It allows you to limit upload and download speed, shape traffic, block applications, schedule rules, and monitor network usage without root using Android VpnService. Unlike traditional firewalls that only **block** connections, NetValve focuses on **traffic shaping** — giving you precise control over:

- 📊 **Per-app bandwidth limits** (download/upload)
- 🚫 **App blocking** (cut all network access)
- ⏰ **Time-based schedules** (e.g., block after midnight)
- 📱 **Background-only rules** (throttle when app is not in foreground)
- 📈 **Live traffic statistics** (throughput, connections, DNS)
- 🔋 **Condition-based policies** (Wi-Fi/mobile/roaming/charging/battery/screen)

Everything runs **on-device** via Android's `VpnService` API. Traffic is terminated by a userspace network stack, shaped in Kotlin, and forwarded directly to the internet from protected sockets. **NetValve is not a remote VPN** — your data never leaves your phone through a third party.

---

## 💡 Why NetValve?

You might ask:

> Why build this when NetGuard, RethinkDNS, or NoRoot Firewall already exist?

Great question. Here's what makes NetValve different:

| Aspect | NetValve | Typical Android Firewall |
|--------|----------|--------------------------|
| **Focus** | Traffic **shaping** (bandwidth limits) | Traffic **blocking** (allow/deny) |
| **Granularity** | Per-app, per-direction caps with token-bucket pacing | Binary on/off |
| **UDP handling** | **Paced, not dropped** — preserves VoIP/gaming | Often dropped (causes app breakage) |
| **Policy engine** | Generic condition→action DSL (extensible) | Hard-coded firewall rules |
| **Statistics** | Live throughput + per-app totals + DNS + latency | Basic counters |
| **Architecture** | gVisor netstack (production-grade TCP/IP) | iptables-style or tun2socks |
| **Extensibility** | Plugin pipeline (`TrafficModule` API) | Monolithic |

### The Core Difference

Most Android firewalls answer: **"Should this app have network access?"**
NetValve answers: **"How much bandwidth should this app get, and when?"**

This is closer to a **traffic shaping router** (like `tc` on Linux) than a firewall. If you want to:
- Cap YouTube to 2 Mbps between 9 AM–5 PM
- Block Telegram when on mobile data but allow on Wi-Fi
- Throttle a game to 100 KB/s while in background
- Monitor exactly how much data each app consumes per session

...NetValve is built for that.

---

## ✨ Features

### Core
- [x] **Root-free** — works on stock Android, no Magisk/KernelSU
- [x] **Per-app bandwidth limiting** — download and upload caps
- [x] **Upload limiting** — separate from download
- [x] **Download limiting** — separate from upload
- [x] **Application blocking** — cut all network access per app
- [x] **Rule scheduling** — time windows and day-of-week
- [x] **Foreground/Background rules** — different caps based on app state
- [x] **Live traffic statistics** — throughput, connections, DNS
- [x] **Logging** — leveled log viewer with export
- [x] **Material 3 UI** — Jetpack Compose, dark/light themes

### Advanced
- [x] **Condition-based policies** — Wi-Fi/mobile/roaming/charging/battery/screen
- [x] **Token-bucket pacing** — sustained rate + burst tolerance
- [x] **UDP pacing** (not dropping) — protects VoIP/gaming/streaming
- [x] **DNS exemption** — name resolution never throttled
- [x] **Generic policy engine** — extensible condition→action DSL
- [x] **Plugin architecture** — add modules without touching the engine
- [x] **IPv6 support** — routed + shaped (RELAY or FAST_REJECT)
- [x] **Battery-aware** — Doze exemption, OEM-specific guidance
- [x] **Boot persistence** — re-arm tunnel after reboot (opt-in)
- [x] **VPN coexistence** — graceful `onRevoke` handling

---

## 📸 Screenshots

> **Note**: Screenshots will be added in `docs/screenshots/`. The app has 5 main screens:

### Dashboard
Main control panel — start/stop tunnel, see live stats, quick app toggles.

### App Selection
Browse installed apps, search, filter system apps, select which to control.

### Per-App Detail
Set download/upload caps (KB/s, MB/s, kbps, Mbps), block, background-only, conditions, and schedule.

### Statistics
Live/avg/peak throughput, per-app totals, connection counts, DNS stats, connect latency.

### Logs
Filter by level (DEBUG/INFO/WARNING/ERROR), search, and export.

---

## 🔧 How It Works

NetValve uses Android's `VpnService` API to create a **local tunnel**:

```
┌─────────────────────────────────────────────────────────────────┐
│  1. Android VpnService creates a TUN interface                  │
│  2. Selected apps' traffic is routed into the TUN                │
│  3. Packet engine (gVisor netstack) terminates TCP/UDP flows    │
│  4. Flow attribution → UID (which app owns this connection?)    │
│  5. Policy engine → evaluate rules for this UID + device state  │
│  6. Throttle manager → apply per-app bandwidth caps             │
│  7. Connection manager → dial protected upstream socket         │
│  8. Relay bytes through token buckets → internet                │
└─────────────────────────────────────────────────────────────────┘
```

### Throttling: Suspend-to-Pace

Unlike traditional firewalls that **drop** packets to throttle, NetValve **paces** them:

```
UPLOAD    app → VPN TUN → netstack → relay → [TokenBucket] → upstream.write() → Internet
DOWNLOAD  Internet → upstream.read() → [TokenBucket] → relay → netstack → VPN TUN → app
```

When an app exceeds its cap, the relay coroutine **suspends** (does not busy-wait, does not drop). TCP flow-control then naturally slows the sender, and UDP is paced through a bounded queue. This preserves app stability — no broken VoIP calls, no dropped game packets, no streaming glitches.

---

## 🏗️ Architecture

```
                        ┌───────────────────────────── UI (Compose, MVVM) ─────────────────────────────┐
                        │  Dashboard · App selection · Per-app detail · Stats · Logs                    │
                        └───────────────▲───────────────────────────────────────────────▲──────────────┘
                                        │ StateFlow                                       │ commands
      ┌─────────────────────────────────┴───────────────┐                    ┌───────────┴───────────┐
      │  Repositories (DataStore / Room / PackageManager)│                    │     VpnController      │
      └───────────────▲──────────────────────▲──────────┘                    └───────────┬───────────┘
                      │                       │                                           │ intents
             SettingsRepository        StatsRepository / LogRepository                    ▼
                      │                       │                              ┌──────────────────────────┐
                      ▼                       ▼                              │   NetValveVpnService     │
           ┌───────────────────┐   ┌───────────────────┐                    │  (VpnService + FGS)      │
           │    RuleEngine      │   │  StatsCollector    │                   │  builds TUN, allow/deny  │
           │  (generic policy)  │   │  Logger            │                   └────────────┬─────────────┘
           └─────────▲─────────┘   └─────────▲──────────┘                                 │ tunFd + protect()
                     │                       │                                            ▼
                     │             ┌─────────┴───────────────────────────────────────────────────────┐
                     │             │                       TrafficEngine (per session)                │
                     │             │  builds FlowSupervisor + starts PacketPipeline + samples stats   │
                     │             └─────────┬───────────────────────────────────────────────────────┘
                     │                       │ FlowHandler callbacks (per flow: 4-tuple + byte stream)
        ┌────────────┴───────────┐   ┌───────▼───────────────────────────────────┐
        │  DeviceStateMonitor    │   │   PacketPipeline  (build-selected engine)  │
        │ net/power/screen/fg    │   │   ├─ netstack  → gVisor AAR (production)   │
        └────────────────────────┘   │   └─ loopback  → pure-Kotlin dev stub      │
                                      └───────┬───────────────────────────────────┘
                                              │ per flow
                       ┌──────────────────────▼─────────────────────────┐
                       │  FlowSupervisor: attribute UID → ModuleChain     │
                       │  verdict → block OR relay via ThrottleManager    │
                       │  token buckets ↔ ConnectionManager (protected)   │
                       └──────────────────────┬───────────────────────────┘
                                              ▼  protected upstream socket → Internet
```

### Design Principles

1. **Product logic is framework-free** — rules, throttling, stats, logging are pure Kotlin, unit-testable on plain JVM.
2. **Engine sits behind one interface** — `PacketPipeline` boundary; swap between `netstack` (gVisor) and `loopback` (dev stub) at build time.
3. **Rules are generic policy** — conditions (network/roaming/charging/battery/screen/foreground/time/day) × actions (Allow/Block/Throttle); the friendly `AppRule` is compiled to generic `PolicyRule`s.
4. **Extensibility via modules** — `TrafficModule` plugin API (`onFlowOpen`/`onBytes`/`onFlowClose`); add quotas/domain-filtering without engine changes.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design.

---

## 📦 Installation

### Option 1: Download APK (Recommended)

Download the latest pre-built APK from the [Releases page](https://github.com/IEAmir/NetValve/releases).

> **Note**: The default APK uses the **loopback engine** (drives the UI but does not forward traffic upstream). For real traffic shaping, you need to build with the **netstack engine** — see [Build From Source](#-build-from-source).

### Option 2: Build From Source

See the next section.

---

## 🛠️ Build From Source

### Prerequisites

- **JDK 17**
- **Android SDK** (compileSdk 35), **minSdk 29** (Android 10)
- Android Studio Ladybug+ or command-line tools

### Quick Build (Loopback Engine — No Native Toolchain)

This produces an installable APK using the pure-Kotlin **loopback** engine. It establishes the tunnel, attributes UIDs, records stats, and drives the UI — but **does not forward traffic upstream** (development/CI double).

```bash
git clone https://github.com/IEAmir/NetValve.git
cd NetValve
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Production Build (Real Forwarding via gVisor Netstack)

The production engine is a **gVisor netstack** bridge compiled to an AAR with gomobile. This produces a fully functional traffic shaper.

**Prerequisites**:
- **Go 1.22+** with `GOTOOLCHAIN=auto` (auto-fetches Go ≥1.25)
- **Android NDK** (installed via Android Studio or `sdkmanager`)

**Steps**:

```bash
# 1. Set NDK path
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>

# 2. Build the netstack AAR
cd netstack
./build-aar.sh                           # → app/libs/netstack.aar (arm64, ~3.8 MB)

# 3. Build the app with netstack engine
cd ..
./gradlew :app:assembleDebug -Pnetvalve.netstack=true
```

The APK will be at `app/build/outputs/apk/debug/app-netstack-arm64-debug.apk`.

See [`docs/NETSTACK_EVIDENCE.md`](docs/NETSTACK_EVIDENCE.md) for build verification and on-device testing protocol.

### Running Tests

```bash
# Unit tests (JVM, no device needed)
./gradlew :app:testDebugUnitTest

# Instrumentation tests (requires emulator/device)
./gradlew :app:connectedDebugAndroidTest
```

**Current status**: 33/33 unit tests pass across 7 suites (token bucket, pacing queue, policy evaluator, rule compiler, UID resolver, persistence, formatting).

---

## 📂 Project Structure

```
NetValve/
├── app/                                Android app (Kotlin, Compose)
│   ├── src/
│   │   ├── main/kotlin/dev/netvalve/   Core application code
│   │   │   ├── ui/                     Compose screens + ViewModels
│   │   │   ├── service/                VpnService, VpnController, TrafficEngine
│   │   │   ├── network/                PacketPipeline, FlowSupervisor, UidResolver
│   │   │   ├── throttle/               TokenBucket, PacingQueue, ThrottleManager
│   │   │   ├── rules/                  PolicyEngine, RuleCompiler, DeviceState
│   │   │   ├── module/                 TrafficModule plugin API
│   │   │   ├── stats/                  StatsCollector, ThroughputMeter
│   │   │   ├── log/                    Logger (ring buffer + Room export)
│   │   │   ├── data/                   Models, DataStore, Room
│   │   │   ├── repository/             DataStore/Room/PackageManager impls
│   │   │   ├── di/                     Hilt modules
│   │   │   └── utils/                  Formatting helpers
│   │   ├── loopback/kotlin/            Pure-Kotlin dev engine (default)
│   │   ├── netstack/kotlin/            gVisor adapter (production)
│   │   ├── test/                       JVM unit tests
│   │   └── androidTest/                Compose instrumentation tests
│   ├── libs/                           Generated AARs (netstack.aar)
│   └── build.gradle.kts
├── netstack/                           Go gVisor bridge
│   ├── bridge.go                       gomobile bindings
│   ├── conn.go                         TCP/UDP forwarders
│   ├── build-aar.sh                    AAR build script
│   └── go.mod
├── docs/                               Architecture, throttling, limitations, extending
│   ├── ARCHITECTURE.md
│   ├── THROTTLING.md
│   ├── LIMITATIONS.md
│   ├── EXTENDING.md
│   ├── NETSTACK_EVIDENCE.md
│   └── sample-rules.json
├── gradle/                             Version catalog + wrapper
├── README.md                           This file
├── README.fa.md                        Persian (Farsi) version
├── HANDOFF.md                          Developer handoff document
├── LICENSE                             Apache-2.0
└── build.gradle.kts
```

---

## ⚡ Performance

NetValve is designed to be lightweight and efficient:

- **300+ concurrent TCP connections** — one coroutine pair per flow on `Dispatchers.IO`; netstack multiplexes; no thread-per-connection.
- **Idle CPU < 2%** — lazy-refill buckets (no timers), suspend-based pacing, stats sampled ~1 Hz, no busy loops.
- **Throughput overhead < 5% unthrottled** — unlimited direction → `null` bucket → `pace()` returns immediately; 16 KB relay chunks.
- **RAM < 50 MB normal** — 16 KB relay buffers, bounded UDP queue (256 KB), flow-table + DNS cache capped with eviction.
- **No busy waiting** — all waits are `delay()`/suspension.
- **Zero ANRs** — no main-thread I/O (StrictMode in debug); `startForeground` posted immediately on START.

See [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md#performance-targets) for the on-device test protocol.

---

## ⚠️ Limitations

Honest about what a non-root, local traffic controller **can** and **cannot** do:

### Platform Constraints

- **One VPN at a time** — Android allows only one active `VpnService`. Starting another VPN app revokes NetValve; NetValve handles `onRevoke` gracefully.
- **Controlled-app set is establish-time-fixed** — editing the selection while running triggers a seamless tunnel rebuild. Editing rule caps does NOT need a rebuild (live bucket refresh).
- **Per-app attribution** uses `getConnectionOwnerUid` (API 29+), wrapped with a cache + fallback chain. On some OEM ROMs a fraction of flows may land in an *Unknown* bucket (shaped by the global default).
- **IPv6** is always routed into the tunnel (so it cannot bypass shaping), with two modes: `RELAY` (default, full shaping) or `FAST_REJECT` (immediate RST/ICMPv6 to avoid timeouts).

### Build/Distribution

- **Loopback engine does not forward** — the default build uses a pure-Kotlin stub for CI/dev. Real forwarding requires building the netstack AAR (`./netstack/build-aar.sh`) and the `-Pnetvalve.netstack=true` flag.
- **Battery optimization** should be disabled on aggressive OEM ROMs (Xiaomi/MIUI, Huawei/EMUI, Oppo/ColorOS, Vivo, Samsung/One UI). NetValve detects this and offers per-vendor guidance.

### Scope (Non-Goals)

- **No remote VPN** — all traffic stays on-device.
- **No domain filtering yet** (planned) — though `DnsCache` (IP→hostname) is already populated and ready for a `DomainFilterModule`.
- **No per-app quota notifications yet** — the infrastructure is in place (`warnThresholdPercent`), but no notification fires yet (deliberately left as the first exercise of the plugin API).

See [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) for the full list.

---

## 🗺️ Roadmap

### Near-term

- [ ] **Warning-threshold notifications** — notify when an app exceeds a usage threshold
- [ ] **Domain filtering** — `DomainFilterModule` using existing `DnsCache`
- [ ] **Per-app quotas** — daily/monthly data caps with notifications
- [ ] **Netstack retransmit counters** — expose Go bridge `Stats()` to UI

### Medium-term

- [ ] **Multiple schedule windows** — per-app detail UI for multiple time ranges
- [ ] **Profiles** — switch between rule sets (Home/Work/Travel)
- [ ] **Adaptive throttling** — auto-adjust caps based on network conditions
- [ ] **IPv6 improvements** — better fast-reject heuristics

### Long-term

- [ ] **Parental controls** — schedule + domain modules with PIN gate
- [ ] **Per-app detailed charts** — time-series throughput visualization
- [ ] **Localization** — move hardcoded strings to `strings.xml`

---

## 🤝 Contributing

Contributions are welcome! Whether it's:

- 🐛 **Bug reports** — open an issue with reproduction steps
- 💡 **Feature requests** — open an issue with use-case description
- 🔧 **Pull requests** — fork, create a feature branch, submit PR
- 📖 **Documentation** — typo fixes, clarifications, translations

### Development Setup

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Add tests (if applicable)
5. Ensure `./gradlew :app:testDebugUnitTest` passes
6. Commit (`git commit -m 'Add amazing feature'`)
7. Push (`git push origin feature/amazing-feature`)
8. Open a Pull Request

See [`docs/EXTENDING.md`](docs/EXTENDING.md) for how to add new policy conditions, actions, or modules.

### Code Style

- Kotlin official style guide
- Framework-free core (rules, throttle, stats, log, module) must remain unit-testable on plain JVM
- Public APIs need KDoc

---

## 📄 License

```
Copyright 2026 The NetValve Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

See [`LICENSE`](LICENSE) for the full text.

NetValve bundles **no ads, trackers, or analytics**, and depends on **no proprietary SDKs**. Third-party components (Kotlin, AndroidX/Jetpack Compose, Hilt/Dagger, Kotlin Coroutines/Serialization, gVisor, gomobile) are licensed under their respective open-source licenses.

---

## 🙏 Acknowledgments

- **[gVisor](https://gvisor.dev/)** — userspace TCP/IP stack (via the [`github.com/sagernet/gvisor`](https://github.com/sagernet/gvisor) fork)
- **[gomobile](https://github.com/golang/mobile)** — Go → Android binding toolchain
- **AndroidX & Jetpack Compose** — UI framework
- **Hilt** — dependency injection
- **Kotlin Coroutines** — async/concurrency

---

## 📚 Additional Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — Full architecture design
- [`docs/THROTTLING.md`](docs/THROTTLING.md) — How token-bucket throttling works (with math)
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — Platform constraints & tradeoffs
- [`docs/EXTENDING.md`](docs/EXTENDING.md) — How to add new conditions, actions, or modules
- [`docs/NETSTACK_EVIDENCE.md`](docs/NETSTACK_EVIDENCE.md) — Build verification & on-device testing
- [`docs/sample-rules.json`](docs/sample-rules.json) — Example rule set
- [`HANDOFF.md`](HANDOFF.md) — Developer handoff document (for new contributors)

---

## 🌟 Why This Project Exists

Android provides excellent firewall applications, but very few open-source projects offer **accurate per-application traffic shaping** without root access. NetValve was created to explore how far Android's `VpnService` can be pushed while remaining:

- ✅ **Lightweight** — <50 MB RAM, <2% idle CPU
- ✅ **Modular** — plugin architecture for new features
- ✅ **Transparent** — open-source, no telemetry, no remote server
- ✅ **Root-free** — works on stock Android
- ✅ **Production-grade** — uses gVisor netstack (battle-tested TCP/IP)

If you want to take control of your device's network traffic without compromising on privacy or performance, NetValve is for you.

---

**⭐ Star this repo if you find it useful!**
# Have you found a bug? any opinion do you have? let me know:
# t.me/SClAmir

[🇮🇷 نسخه فارسی (Persian)](README.fa.md)
