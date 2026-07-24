# NetValve

<div align="center">

**کنترل‌کننده ترافیک per-app بدون نیاز به root برای Android.**

شکل‌دهی، مسدودسازی، زمان‌بندی و نظارت بر ترافیک شبکه اپلیکیشن‌ها با استفاده از `VpnService` اندروید.
بدون root. بدون سرور خارجی. بدون ردیاب.

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/10)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache--2.0-green.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-29-blue.svg)](https://developer.android.com/about/versions/10)
[![Target SDK](https://img.shields.io/badge/targetSdk-35-blue.svg)](https://developer.android.com/about/versions/15)

</div>

---

## 📑 فهرست مطالب

1. [NetValve چیست؟](#-netvalve-چیست)
2. [چرا NetValve؟](#-چرا-netvalve)
3. [ویژگی‌ها](#-ویژگی‌ها)
4. [تصاویر](#-تصاویر)
5. [چگونه کار می‌کند؟](#-چگونه-کار-میکند)
6. [معماری](#-معماری)
7. [نصب](#-نصب)
8. [ساخت از سورس](#-ساخت-از-سورس)
9. [ساختار پروژه](#-ساختار-پروژه)
10. [کارایی](#-کارایی)
11. [محدودیت‌ها](#-محدودیتها)
12. [نقشه راه](#-نقشه-راه)
13. [مشارکت](#-مشارکت)
14. [مجوز](#-مجوز)

---

## 🎯 NetValve چیست؟

**NetValve** یک اپلیکیشن اندروید بدون نیاز به root است که یک **تونل VPN محلی** ایجاد می‌کند تا ترافیک شبکه اپلیکیشن‌ها را رهگیری، شکل‌دهی و نظارت کند. برخلاف فایروال‌های سنتی که فقط اتصالات را **مسدود** می‌کنند، NetValve بر **شکل‌دهی ترافیک** تمرکز دارد — کنترل دقیق بر:

- 📊 **محدودیت پهنای باند per-app** (دانلود/آپلود)
- 🚫 **مسدودسازی اپلیکیشن** (قطع کامل دسترسی به شبکه)
- ⏰ **زمان‌بندی** (مثلاً: مسدودسازی بعد از نیمه‌شب)
- 📱 **قوانین پس‌زمینه** (کاهش سرعت وقتی اپ در foreground نیست)
- 📈 **آمار زنده ترافیک** (throughput، اتصالات، DNS)
- 🔋 **سیاست‌های شرطی** (Wi-Fi/موبایل/Roaming/شارژ/باتری/صفحه)

همه چیز **روی دستگاه** از طریق API `VpnService` اندروید اجرا می‌شود. ترافیک توسط یک شبکه استک userspace پایان می‌یابد، در Kotlin شکل‌دهی می‌شود، و مستقیماً از طریق سوکت‌های محافظت‌شده به اینترنت ارسال می‌شود. **NetValve یک VPN خارجی نیست** — داده‌های شما هرگز از طریق شخص ثالث از گوشی شما خارج نمی‌شود.

---

## 💡 چرا NetValve؟

شاید بپرسید:

> وقتی NetGuard، RethinkDNS یا NoRoot Firewall وجود دارند، چرا این پروژه را بسازیم؟

سؤال خوبی است. اینجاست که NetValve متفاوت است:

| جنبه | NetValve | فایروال معمولی اندروید |
|--------|----------|--------------------------|
| **تمرکز** | **شکل‌دهی** ترافیک (محدودیت پهنای باند) | **مسدودسازی** ترافیک (مجاز/غیرمجاز) |
| **دقت** | محدودیت per-app و per-direction با pacing توکن‌باکت | دودویی روشن/خاموش |
| **مدیریت UDP** | **Paced، نه dropped** — حفظ VoIP/بازی | اغلب dropped (باعث خرابی اپ می‌شود) |
| **موتور سیاست** | DSL شرط→عمل عمومی (قابل توسعه) | قوانین فایروال hard-coded |
| **آمار** | Throughput زنده + مجموع per-app + DNS + تأخیر | شمارنده‌های ابتدایی |
| **معماری** | gVisor netstack (TCP/IP درجه تولید) | iptables-style یا tun2socks |
| **قابلیت توسعه** | خط لوله پلاگین (API `TrafficModule`) | یکپارچه |

### تفاوت اصلی

اکثر فایروال‌های اندروید به این سؤال پاسخ می‌دهند: **"آیا این اپ باید به شبکه دسترسی داشته باشد؟"**
NetValve پاسخ می‌دهد: **"این اپ چقدر پهنای باند بگیرد، و کِی؟"**

این به یک **روتر شکل‌دهی ترافیک** (مانند `tc` در لینوکس) نزدیک‌تر است تا یک فایروال. اگر می‌خواهید:
- YouTube را بین ۹ صبح تا ۵ عصر به ۲ Mbps محدود کنید
- تلگرام را در دیتای موبایل مسدود کنید ولی در Wi-Fi مجاز کنید
- یک بازی را در پس‌زمینه به ۱۰۰ KB/s کاهش دهید
- دقیقاً نظارت کنید که هر اپ چقدر داده در هر session مصرف می‌کند

...NetValve برای این کار ساخته شده است.

---

## ✨ ویژگی‌ها

### هسته
- [x] **بدون Root** — روی Android خام کار می‌کند، بدون Magisk/KernelSU
- [x] **محدودیت پهنای باند per-app** — کپ دانلود و آپلود
- [x] **محدودیت آپلود** — مستقل از دانلود
- [x] **محدودیت دانلود** — مستقل از آپلود
- [x] **مسدودسازی اپلیکیشن** — قطع کامل دسترسی به شبکه per app
- [x] **زمان‌بندی قوانین** — بازه‌های زمانی و روز هفته
- [x] **قوانین Foreground/Background** — کپ متفاوت بر اساس وضعیت اپ
- [x] **آمار زنده ترافیک** — throughput، اتصالات، DNS
- [x] **لاگ‌گیری** — نمایشگر لاگ سطح‌بندی‌شده با خروجی
- [x] **رابط کاربری Material 3** — Jetpack Compose، تم‌های تاریک/روشن

### پیشرفته
- [x] **سیاست‌های شرطی** — Wi-Fi/موبایل/Roaming/شارژ/باتری/صفحه
- [x] **Pacing توکن‌باکت** — نرخ پایدار + تحمل burst
- [x] **Pacing UDP** (نه dropping) — محافظت از VoIP/بازی/استریم
- [x] **معافیت DNS** — name resolution هرگز throttle نمی‌شود
- [x] **موتور سیاست عمومی** — DSL شرط→عمل قابل توسعه
- [x] **معماری پلاگین** — افزودن ماژول بدون دستکاری engine
- [x] **پشتیبانی IPv6** — routed + shaped (RELAY یا FAST_REJECT)
- [x] **آگاه از باتری** — معافیت Doze، راهنمای OEM-specific
- [x] **ماندگاری پس از بوت** — بازفعال‌سازی تونل پس از ریبوت (اختیاری)
- [x] **همزیستی VPN** — مدیریت graceful `onRevoke`

---

## 📸 تصاویر

> **توجه**: تصاویر در `docs/screenshots/` اضافه خواهند شد. اپ ۵ صفحه اصلی دارد:

### داشبورد
پنل کنترل اصلی — شروع/توقف تونل، مشاهده آمار زنده، تغییر سریع اپ‌ها.

### انتخاب اپلیکیشن
مرور اپ‌های نصب‌شده، جستجو، فیلتر اپ‌های سیستمی، انتخاب اپ‌های قابل کنترل.

### جزئیات per-app
تنظیم کپ دانلود/آپلود (KB/s, MB/s, kbps, Mbps)، مسدودسازی، فقط پس‌زمینه، شرایط و زمان‌بندی.

### آمار
Throughput زنده/میانگین/پیک، مجموع per-app، تعداد اتصالات، آمار DNS، تأخیر اتصال.

### لاگ‌ها
فیلتر بر اساس سطح (DEBUG/INFO/WARNING/ERROR)، جستجو و خروجی.

---

## 🔧 چگونه کار می‌کند؟

NetValve از API `VpnService` اندروید برای ایجاد یک **تونل محلی** استفاده می‌کند:

```
┌─────────────────────────────────────────────────────────────────┐
│  ۱. Android VpnService یک واسط TUN ایجاد می‌کند               │
│  ۲. ترافیک اپ‌های انتخاب‌شده به TUN هدایت می‌شود                │
│  ۳. موتور بسته (gVisor netstack) جریان‌های TCP/UDP را پایان می‌دهد│
│  ۴. نسبت‌دهی جریان → UID (کدام اپ مالک این اتصال است؟)        │
│  ۵. موتور سیاست → ارزیابی قوانین برای این UID + وضعیت دستگاه   │
│  ۶. مدیر throttle → اعمال کپ پهنای باند per-app                 │
│  ۷. مدیر اتصال → شماره‌گیری سوکت محافظت‌شده upstream           │
│  ۸. ارسال بایت‌ها از طریق توکن‌باکت‌ها → اینترنت                │
└─────────────────────────────────────────────────────────────────┘
```

### Throttling: Suspend-to-Pace

برخلاف فایروال‌های سنتی که برای throttle کردن بسته‌ها را **drop** می‌کنند، NetValve آن‌ها را **pace** می‌کند:

```
UPLOAD    app → VPN TUN → netstack → relay → [TokenBucket] → upstream.write() → Internet
DOWNLOAD  Internet → upstream.read() → [TokenBucket] → relay → netstack → VPN TUN → app
```

وقتی یک اپ از کپ خود فراتر می‌رود، coroutine relay **suspend** می‌شود (busy-wait نمی‌کند، drop نمی‌کند). کنترل جریان TCP به طور طبیعی ارسال‌کننده را کند می‌کند، و UDP از طریق یک صف محدود pace می‌شود. این پایداری اپ را حفظ می‌کند — بدون تماس‌های VoIP قطع‌شده، بدون بسته‌های بازی از دست رفته، بدون glitch استریم.

---

## 🏗️ معماری

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

### اصول طراحی

1. **منطق محصول framework-free است** — قوانین، throttle، آمار، لاگ‌گیری Kotlin خالص هستند، روی JVM ساده قابل تست واحد.
2. **موتور پشت یک واسط واحد است** — مرز `PacketPipeline`؛ swap بین `netstack` (gVisor) و `loopback` (stub توسعه) در زمان build.
3. **قوانین سیاست عمومی هستند** — شرایط (شبکه/roaming/شارژ/باتری/صفحه/foreground/زمان/روز) × اعمال (Allow/Block/Throttle)؛ `AppRule` کاربرپسند به `PolicyRule`های عمومی کامپایل می‌شود.
4. **قابلیت توسعه از طریق ماژول‌ها** — API پلاگین `TrafficModule` (`onFlowOpen`/`onBytes`/`onFlowClose`)؛ افزودن quota/فیلتر دامنه بدون تغییر engine.

برای طراحی کامل به [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) مراجعه کنید.

---

## 📦 نصب

### گزینه ۱: دانلود APK (توصیه‌شده)

آخرین APK از پیش ساخته‌شده را از [صفحه Releases](https://github.com/IEAmir/NetValve/releases) دانلود کنید.

> **توجه**: APK پیش‌فرض از **موتور loopback** استفاده می‌کند (UI را اجرا می‌کند ولی ترافیک را upstream ارسال نمی‌کند). برای شکل‌دهی واقعی ترافیک، باید با **موتور netstack** build کنید — به [ساخت از سورس](#-ساخت-از-سورس) مراجعه کنید.

### گزینه ۲: ساخت از سورس

به بخش بعدی مراجعه کنید.

---

## 🛠️ ساخت از سورس

### پیش‌نیازها

- **JDK 17**
- **Android SDK** (compileSdk 35)، **minSdk 29** (Android 10)
- Android Studio Ladybug+ یا ابزارهای command-line

### Build سریع (موتور Loopback — بدون ابزار Native)

این یک APK قابل نصب با استفاده از موتور **loopback** Kotlin خالص تولید می‌کند. تونل را ایجاد می‌کند، UIDها را نسبت می‌دهد، آمار را ثبت می‌کند و UI را اجرا می‌کند — ولی **ترافیک را upstream ارسال نمی‌کند** (دوبل توسعه/CI).

```bash
git clone https://github.com/IEAmir/NetValve.git
cd NetValve
./gradlew :app:assembleDebug
```

APK در `app/build/outputs/apk/debug/app-debug.apk` خواهد بود.

### Build تولیدی (ارسال واقعی از طریق gVisor Netstack)

موتور تولیدی یک bridge **gVisor netstack** است که با gomobile به AAR کامپایل شده است. این یک شکل‌دهنده ترافیک کاملاً کاربردی تولید می‌کند.

**پیش‌نیازها**:
- **Go 1.22+** با `GOTOOLCHAIN=auto` (به طور خودکار Go ≥1.25 را می‌گیرد)
- **Android NDK** (نصب از طریق Android Studio یا `sdkmanager`)

**مراحل**:

```bash
# ۱. تنظیم مسیر NDK
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>

# ۲. ساخت AAR netstack
cd netstack
./build-aar.sh                           # → app/libs/netstack.aar (arm64, ~3.8 MB)

# ۳. ساخت اپ با موتور netstack
cd ..
./gradlew :app:assembleDebug -Pnetvalve.netstack=true
```

APK در `app/build/outputs/apk/debug/app-netstack-arm64-debug.apk` خواهد بود.

برای تأیید build و پروتکل تست روی دستگاه به [`docs/NETSTACK_EVIDENCE.md`](docs/NETSTACK_EVIDENCE.md) مراجعه کنید.

### اجرای تست‌ها

```bash
# تست‌های واحد (JVM، بدون نیاز به دستگاه)
./gradlew :app:testDebugUnitTest

# تست‌های instrumentation (نیاز به emulator/دستگاه)
./gradlew :app:connectedDebugAndroidTest
```

**وضعیت فعلی**: ۳۳/۳۳ تست واحد در ۷ suite پاس می‌شوند (token bucket, pacing queue, policy evaluator, rule compiler, UID resolver, persistence, formatting).

---

## 📂 ساختار پروژه

```
NetValve/
├── app/                                اپ Android (Kotlin, Compose)
│   ├── src/
│   │   ├── main/kotlin/dev/netvalve/   کد اصلی اپلیکیشن
│   │   │   ├── ui/                     صفحات Compose + ViewModelها
│   │   │   ├── service/                VpnService, VpnController, TrafficEngine
│   │   │   ├── network/                PacketPipeline, FlowSupervisor, UidResolver
│   │   │   ├── throttle/               TokenBucket, PacingQueue, ThrottleManager
│   │   │   ├── rules/                  PolicyEngine, RuleCompiler, DeviceState
│   │   │   ├── module/                 API پلاگین TrafficModule
│   │   │   ├── stats/                  StatsCollector, ThroughputMeter
│   │   │   ├── log/                    Logger (ring buffer + خروجی Room)
│   │   │   ├── data/                   مدل‌ها، DataStore، Room
│   │   │   ├── repository/             پیاده‌سازی‌های DataStore/Room/PackageManager
│   │   │   ├── di/                     ماژول‌های Hilt
│   │   │   └── utils/                  توابع کمکی فرمت‌بندی
│   │   ├── loopback/kotlin/            موتور توسعه Kotlin خالص (پیش‌فرض)
│   │   ├── netstack/kotlin/            آداپتور gVisor (تولیدی)
│   │   ├── test/                       تست‌های واحد JVM
│   │   └── androidTest/                تست‌های instrumentation Compose
│   ├── libs/                           AARهای تولیدشده (netstack.aar)
│   └── build.gradle.kts
├── netstack/                           Bridge Go gVisor
│   ├── bridge.go                       bindings gomobile
│   ├── conn.go                         forwarderهای TCP/UDP
│   ├── build-aar.sh                    اسکریپت build AAR
│   └── go.mod
├── docs/                               معماری، throttle، محدودیت‌ها، توسعه
│   ├── ARCHITECTURE.md
│   ├── THROTTLING.md
│   ├── LIMITATIONS.md
│   ├── EXTENDING.md
│   ├── NETSTACK_EVIDENCE.md
│   └── sample-rules.json
├── gradle/                             کاتالوگ نسخه + wrapper
├── README.md                           این فایل (انگلیسی)
├── README.fa.md                        نسخه فارسی
├── HANDOFF.md                          سند تحویل توسعه‌دهنده
├── LICENSE                             Apache-2.0
└── build.gradle.kts
```

---

## ⚡ کارایی

NetValve برای سبک و کارآمد بودن طراحی شده است:

- **+۳۰۰ اتصال TCP همزمان** — یک جفت coroutine per flow روی `Dispatchers.IO`؛ netstack چندگانه می‌کند؛ بدون thread-per-connection.
- **CPU بیکار < ۲٪** — توکن‌باکت‌های lazy-refill (بدون تایمر)، pacing مبتنی بر suspend، نمونه‌گیری آمار ~۱ Hz، بدون حلقه busy.
- **سربار throughput < ۵٪ بدون throttle** — جهت نامحدود → توکن‌باکت `null` → `pace()` فوری برمی‌گردد؛ تکه‌های relay ۱۶ KB.
- **RAM < ۵۰ MB عادی** — بافرهای relay ۱۶ KB، صف محدود UDP (۲۵۶ KB)، جدول جریان + کش DNS با eviction محدود شده.
- **بدون busy waiting** — همه انتظارها `delay()`/suspension هستند.
- **بدون ANR** — بدون I/O در main thread (StrictMode در debug)؛ `startForeground` فوری در START ارسال می‌شود.

برای پروتکل تست روی دستگاه به [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md#performance-targets) مراجعه کنید.

---

## ⚠️ محدودیت‌ها

صادقانه درباره آنچه یک کنترل‌کننده ترافیک غیر-root و محلی **می‌تواند** و **نمی‌تواند** انجام دهد:

### محدودیت‌های پلتفرم

- **فقط یک VPN در یک زمان** — Android فقط یک `VpnService` فعال را مجاز می‌کند. شروع VPN دیگر NetValve را revoke می‌کند؛ NetValve با `onRevoke` به طور graceful برخورد می‌کند.
- **مجموعه اپ‌های قابل کنترل در زمان establish ثابت است** — ویرایش انتخاب در حین اجرا باعث بازسازی seamless تونل می‌شود. ویرایش کپ قوانین نیازی به بازسازی ندارد (بازخوانی زنده توکن‌باکت).
- **نسبت‌دهی per-app** از `getConnectionOwnerUid` (API 29+) استفاده می‌کند، با یک زنجیره cache + fallback. در برخی ROMهای OEM ممکن است بخشی از جریان‌ها در سطل *Unknown* قرار گیرند (با سیاست پیش‌فرض سراسری شکل‌دهی می‌شوند).
- **IPv6** همیشه به تونل هدایت می‌شود (تا نتواند از شکل‌دهی فرار کند)، با دو حالت: `RELAY` (پیش‌فرض، شکل‌دهی کامل) یا `FAST_REJECT` (RST/ICMPv6 فوری برای جلوگیری از timeout).

### Build/توزیع

- **موتور Loopback ارسال نمی‌کند** — build پیش‌فرض از یک stub Kotlin خالص برای CI/توسعه استفاده می‌کند. ارسال واقعی نیاز به ساخت AAR netstack (`./netstack/build-aar.sh`) و فلگ `-Pnetvalve.netstack=true` دارد.
- **بهینه‌سازی باتری** باید در ROMهای OEM تهاجمی غیرفعال شود (Xiaomi/MIUI, Huawei/EMUI, Oppo/ColorOS, Vivo, Samsung/One UI). NetValve این را تشخیص می‌دهد و راهنمای vendor-specific ارائه می‌دهد.

### محدوده (غیرهدف‌ها)

- **بدون VPN خارجی** — همه ترافیک روی دستگاه می‌ماند.
- **بدون فیلتر دامنه هنوز** (برنامه‌ریزی‌شده) — اگرچه `DnsCache` (IP→hostname) قبلاً جمعیت‌دهی شده و آماده `DomainFilterModule` است.
- **بدون اعلان سهمیه per-app هنوز** — زیرساخت آماده است (`warnThresholdPercent`)، اما هنوز اعلانی ارسال نمی‌شود (عمداً به عنوان اولین تمرین API پلاگین باقی مانده).

برای لیست کامل به [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) مراجعه کنید.

---

## 🗺️ نقشه راه

### کوتاه‌مدت

- [ ] **اعلان‌های آستانه هشدار** — اعلان وقتی اپ از آستانه مصرف فراتر رود
- [ ] **فیلتر دامنه** — `DomainFilterModule` با استفاده از `DnsCache` موجود
- [ ] **سهمیه‌های per-app** — کپ داده روزانه/ماهانه با اعلان
- [ ] **شمارنده‌های retransmit Netstack** — افشای `Stats()` bridge Go به UI

### میان‌مدت

- [ ] **پنجره‌های زمانی متعدد** — UI جزئیات per-app برای بازه‌های زمانی متعدد
- [ ] **پروفایل‌ها** — تغییر بین مجموعه‌های قانون (خانه/کار/سفر)
- [ ] **Throttle تطبیقی** — تنظیم خودکار کپ بر اساس شرایط شبکه
- [ ] **بهبودهای IPv6** — heuristicهای fast-reject بهتر

### بلندمدت

- [ ] **کنترل‌های والدین** — ماژول‌های زمان‌بندی + دامنه با gate PIN
- [ ] **نمودارهای تفصیلی per-app** — بصری‌سازی throughput سری زمانی
- [ ] **بومی‌سازی** — انتقال رشته‌های hard-coded به `strings.xml`

---

## 🤝 مشارکت

مشارکت‌ها خوش‌آمد هستند! چه:

- 🐛 **گزارش باگ** — issue با مراحل بازتولید باز کنید
- 💡 **درخواست ویژگی** — issue با توضیح مورد استفاده باز کنید
- 🔧 **Pull Request** — fork کنید، شاخه ویژگی بسازید، PR ارسال کنید
- 📖 **مستندات** — رفع اشتباه تایپی، روشن‌سازی، ترجمه

### راه‌اندازی توسعه

۱. Repo را fork کنید
۲. یک شاخه ویژگی بسازید (`git checkout -b feature/amazing-feature`)
۳. تغییرات خود را اعمال کنید
۴. تست اضافه کنید (در صورت لزوم)
۵. مطمئن شوید `./gradlew :app:testDebugUnitTest` پاس می‌شود
۶. Commit کنید (`git commit -m 'Add amazing feature'`)
۷. Push کنید (`git push origin feature/amazing-feature`)
۸. Pull Request باز کنید

برای نحوه افزودن شرایط، اعمال یا ماژول‌های جدید به [`docs/EXTENDING.md`](docs/EXTENDING.md) مراجعه کنید.

### سبک کد

- راهنمای سبک رسمی Kotlin
- هسته framework-free (rules, throttle, stats, log, module) باید روی JVM ساده قابل تست واحد باقی بماند
- APIهای عمومی نیاز به KDoc دارند

---

## 📄 مجوز

```
Copyright 2026 The NetValve Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

برای متن کامل به [`LICENSE`](LICENSE) مراجعه کنید.

NetValve شامل **هیچ تبلیغات، ردیاب یا analytics نمی‌شود** و به **هیچ SDK اختصاصی وابسته نیست**. مؤلفه‌های شخص ثالث (Kotlin, AndroidX/Jetpack Compose, Hilt/Dagger, Kotlin Coroutines/Serialization, gVisor, gmobile) تحت مجوزهای open-source مربوطه خود مجوز دارند.

---

## 🙏 تقدیر و تشکر

- **[gVisor](https://gvisor.dev/)** — استک TCP/IP userspace (از طریق fork [`github.com/sagernet/gvisor`](https://github.com/sagernet/gvisor))
- **[gomobile](https://github.com/golang/mobile)** — ابزار binding Go → Android
- **AndroidX & Jetpack Compose** — فریم‌ورک UI
- **Hilt** — تزریق وابستگی
- **Kotlin Coroutines** — ناهمزمانی/همزمانی

---

## 📚 مستندات اضافی

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — طراحی کامل معماری
- [`docs/THROTTLING.md`](docs/THROTTLING.md) — نحوه کار throttle توکن‌باکت (با ریاضیات)
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — محدودیت‌های پلتفرم و tradeoffs
- [`docs/EXTENDING.md`](docs/EXTENDING.md) — نحوه افزودن شرایط، اعمال یا ماژول‌های جدید
- [`docs/NETSTACK_EVIDENCE.md`](docs/NETSTACK_EVIDENCE.md) — تأیید Build و تست روی دستگاه
- [`docs/sample-rules.json`](docs/sample-rules.json) — مجموعه قانون نمونه
- [`HANDOFF.md`](HANDOFF.md) — سند تحویل توسعه‌دهنده (برای مشارکت‌کنندگان جدید)

---

## 🌟 چرا این پروژه وجود دارد

اندروید اپلیکیشن‌های فایروال عالی ارائه می‌دهد، اما پروژه‌های open-source بسیار کمی **شکل‌دهی ترافیک دقیق per-application** را بدون دسترسی root ارائه می‌دهند. NetValve برای کاوش این موضوع ساخته شد که `VpnService` اندروید تا چه حد می‌تواند پیش برود، در حالی که:

- ✅ **سبک** باقی می‌ماند — <۵۰ MB RAM، <۲٪ CPU بیکار
- ✅ **معماری ماژولار** — خط لوله پلاگین برای ویژگی‌های جدید
- ✅ **شفاف** — open-source، بدون telemetry، بدون سرور خارجی
- ✅ **بدون Root** — روی Android خام کار می‌کند
- ✅ **درجه تولید** — از gVisor netstack (TCP/IP تست‌شده در میدان) استفاده می‌کند

اگر می‌خواهید بدون به خطر انداختن حریم خصوصی یا کارایی، کنترل ترافیک شبکه دستگاه خود را به دست بگیرید، NetValve برای شماست.

---

**⭐ اگر این پروژه را مفید یافتید، ستاره بدهید!**

[🇬🇧 English Version](README.md)
