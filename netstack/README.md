# netstack — the gVisor bridge

This module is the **production packet engine** for NetValve: a thin Go bridge over
[gVisor](https://gvisor.dev/)'s userspace TCP/IP stack (`netstack`), compiled to an
Android AAR with [gomobile](https://pkg.go.dev/golang.org/x/mobile). The Kotlin app
consumes it through the `PacketPipeline` interface.

## Why netstack (and not a hand-written stack / tun2socks / lwIP)

The requirements call for a **mature** networking stack rather than a bespoke
TCP/IP implementation. Evaluated options:

| Option | Verdict |
|---|---|
| **gVisor netstack** ✅ | Battle-tested (used by gVisor sandbox, Tailscale, Rethink DNS, Intra). Hands us each accepted flow with its **original 4-tuple**, which is exactly what per-app UID attribution (`getConnectionOwnerUid`) needs. Chosen. |
| tun2socks | Terminates flows behind a **SOCKS** boundary, which obscures the original source tuple → breaks per-app attribution. Rejected. |
| lwIP | Mature but C; needs substantial JNI glue and manual buffer management — approaches "writing a stack" in integration cost. Rejected. |
| Hand-written TCP | Explicitly a non-goal; error-prone protocol edge cases. Rejected. |

The bridge deliberately contains **no product logic** — no throttling, rules, or
stats. It only: owns the TUN fd, runs the stack (IPv4+IPv6, TCP+UDP), and calls
back into Kotlin per flow with the tuple + a byte stream. Kotlin dials the
protected upstream socket and applies the token bucket, so shaping stays in one
place (see `docs/THROTTLING.md`).

## Files
- `bridge.go` — public gomobile API (`Handler`, `TCPConn`/`UDPConn`, `Tunnel`,
  `NewTunnel`, `Stop`), stack setup, accept-all TCP/UDP forwarders, IPv6 mode.
- `conn.go` — adapters wrapping gVisor `gonet` connections as the exported
  interfaces.
- `go.mod` — pins gVisor + gomobile.
- `build-aar.sh` — compiles + binds to `../app/libs/netstack.aar`.

## Build
```bash
# one-time
go install golang.org/x/mobile/cmd/gomobile@latest
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>

./build-aar.sh          # → ../app/libs/netstack.aar
```
Then build the app with the engine enabled:
```bash
cd .. && ./gradlew :app:assembleDebug -Pnetvalve.netstack=true
```

## Generated Java bindings
`build-aar.sh` runs `gomobile bind -javapkg=dev.netvalve`, so the Go package
`bridge` becomes the Java package **`dev.netvalve.bridge`**. gomobile lower-cases
the first letter of methods/functions (Go `NewTunnel` → Java `Bridge.newTunnel`,
`Read` → `read`). The Kotlin adapter (`app/src/netstack/.../NetstackPacketPipeline.kt`)
matches these names; if you change the Go API, re-verify the Kotlin side against
the AAR's `classes.jar`.

## A note on gVisor API drift
gVisor has **no stable API** and moves identifiers between commits. `bridge.go`
targets the commit pinned in `go.mod`; bumping it may require small call-site
adjustments (forwarder signatures, `ProtocolAddress` construction). `build-aar.sh`
runs `go build ./...` before binding so any drift fails fast with a clear error.
This is the one component that must be built/verified on a machine with the Go +
NDK toolchain (it cannot be exercised from a pure-JVM/CI-without-Go environment) —
see `docs/LIMITATIONS.md`.
