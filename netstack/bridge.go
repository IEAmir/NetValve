// Package bridge is the gomobile-bound boundary between NetValve's Kotlin app
// and gVisor's userspace netstack. It intentionally contains NO product logic
// (no throttling, no rules, no stats). Its single job is protocol correctness:
//
//   - own the TUN fd and drive a gVisor stack over it (IPv4 + IPv6, TCP + UDP);
//   - accept every flow the controlled apps generate (promiscuous + spoofing,
//     because the TUN sees arbitrary destination addresses);
//   - for each accepted flow, hand Kotlin the ORIGINAL 4-tuple plus a byte-stream
//     handle ([TCPConn]/[UDPConn]).
//
// Kotlin then dials the protected upstream socket and copies bytes through the
// token bucket, so throttling lives entirely on the Kotlin side (see
// docs/THROTTLING.md). We deliberately do NOT forward upstream in Go, precisely
// so the shaping insertion point stays in one place.
//
// NOTE ON gVISOR API DRIFT: gVisor has no stable API and its identifiers move
// between commits. This file targets the commit pinned in go.mod; if you bump
// it, expect to adjust a few call sites (forwarder signatures, ProtocolAddress
// construction). build-aar.sh compiles this before binding so breakage is caught
// early.
package bridge

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/gvisor/pkg/tcpip/link/fdbased"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv6"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/udp"
	"github.com/sagernet/gvisor/pkg/waiter"
)

const nicID tcpip.NICID = 1

// IPv6 handling modes, mirrored from Kotlin's Ipv6Mode.
const (
	ipv6Relay      = "RELAY"
	ipv6FastReject = "FAST_REJECT"
)

// Handler is implemented on the Kotlin side (gomobile generates a Java
// interface). gVisor calls these when it accepts a new flow on the TUN.
type Handler interface {
	HandleTCP(srcIP string, srcPort int, dstIP string, dstPort int, conn TCPConn)
	HandleUDP(srcIP string, srcPort int, dstIP string, dstPort int, conn UDPConn)
	Log(level int, msg string)
}

// TCPConn / UDPConn are the byte-stream handles handed to Kotlin. They are Go
// interfaces implemented by the adapters in conn.go; gomobile exposes them to
// Java as callable proxies. Multi-return (int, error) maps to `int m() throws`.
type TCPConn interface {
	Read(p []byte) (int, error)
	Write(p []byte) (int, error)
	Close() error
}

type UDPConn interface {
	Receive() ([]byte, error)
	Send(p []byte) error
	Close() error
}

// Log levels mirrored from Kotlin's LogLevel ordinals (DEBUG..ERROR).
const (
	logDebug = 0
	logInfo  = 1
	logWarn  = 2
	logError = 3
)

// Tunnel owns the running stack. Created by NewTunnel, torn down by Stop.
type Tunnel struct {
	stack    *stack.Stack
	handler  Handler
	ipv6Mode string
	mu       sync.Mutex
	closed   bool
	stopCh   chan struct{}
	tcpFlows int64
	udpFlows int64
}

// logf forwards a formatted message to the Kotlin logger (never panics).
func (t *Tunnel) logf(level int, format string, a ...interface{}) {
	defer func() { _ = recover() }()
	if t.handler != nil {
		t.handler.Log(level, fmt.Sprintf(format, a...))
	}
}

// NewTunnel builds a gVisor stack over the given TUN fd and starts processing.
// mtu is the tunnel MTU; ipv6Mode is "RELAY" or "FAST_REJECT".
func NewTunnel(tunFd int, mtu int, ipv6Mode string, handler Handler) (*Tunnel, error) {
	if handler == nil {
		return nil, errors.New("nil handler")
	}

	linkEP, err := fdbased.New(&fdbased.Options{
		FDs: []int{tunFd},
		MTU: uint32(mtu),
	})
	if err != nil {
		return nil, fmt.Errorf("fdbased.New: %w", err)
	}

	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		HandleLocal:        false,
	})

	if tErr := s.CreateNIC(nicID, linkEP); tErr != nil {
		return nil, fmt.Errorf("CreateNIC: %v", tErr)
	}
	// The TUN carries traffic for arbitrary destinations, so the NIC must accept
	// packets not addressed to a configured local address, and originate replies
	// with spoofed source addresses.
	if tErr := s.SetPromiscuousMode(nicID, true); tErr != nil {
		return nil, fmt.Errorf("SetPromiscuousMode: %v", tErr)
	}
	if tErr := s.SetSpoofing(nicID, true); tErr != nil {
		return nil, fmt.Errorf("SetSpoofing: %v", tErr)
	}
	// Default routes for both families so every dst is routed to our NIC.
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	t := &Tunnel{stack: s, handler: handler, ipv6Mode: ipv6Mode, stopCh: make(chan struct{})}
	t.installTCPForwarder()
	t.installUDPForwarder()
	t.logf(logInfo, "netstack started (mtu=%d ipv6=%s)", mtu, ipv6Mode)
	// Liveness beacon: if these lines stop appearing in logcat, the Go runtime
	// or dispatcher died; if they continue while traffic is dead, the stall is on
	// the Kotlin/relay side. Invaluable for diagnosing "stops after a few seconds".
	go t.livenessLoop()
	return t, nil
}

func (t *Tunnel) livenessLoop() {
	defer func() { _ = recover() }()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-t.stopCh:
			return
		case <-ticker.C:
			t.logf(logDebug, "netstack alive: tcpFlows=%d udpFlows=%d",
				atomic.LoadInt64(&t.tcpFlows), atomic.LoadInt64(&t.udpFlows))
		}
	}
}

// Stop tears down the stack and releases the TUN. Idempotent.
func (t *Tunnel) Stop() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return nil
	}
	t.closed = true
	close(t.stopCh)
	t.logf(logInfo, "netstack stopping")
	t.stack.Close()
	return nil
}

func (t *Tunnel) rejectV6(dst tcpip.Address) bool {
	return t.ipv6Mode == ipv6FastReject && dst.Len() == 16
}

// installTCPForwarder wires an accept-all TCP forwarder. For each SYN, gVisor
// completes the handshake lazily; we create the endpoint, wrap it as a
// net.Conn-like TCPConn, and hand it to Kotlin.
func (t *Tunnel) installTCPForwarder() {
	fwd := tcp.NewForwarder(t.stack, 0 /*rcvWnd: default*/, 2048 /*maxInFlight*/, func(r *tcp.ForwarderRequest) {
		// A panic here (e.g. a bad flow, a gomobile callback error) would otherwise
		// crash the entire Go runtime and kill the tunnel. Recover + log instead.
		defer func() {
			if rec := recover(); rec != nil {
				t.logf(logError, "recovered panic in TCP forwarder: %v", rec)
			}
		}()
		id := r.ID()
		// Fast-reject IPv6 when configured so apps fall back to IPv4 immediately.
		if t.rejectV6(id.LocalAddress) {
			r.Complete(true) // send RST
			return
		}
		var wq waiter.Queue
		ep, tErr := r.CreateEndpoint(&wq)
		if tErr != nil {
			t.logf(logWarn, "tcp CreateEndpoint %s:%d->%s:%d failed: %v",
				id.RemoteAddress.String(), id.RemotePort, id.LocalAddress.String(), id.LocalPort, tErr)
			r.Complete(true) // RST on failure
			return
		}
		r.Complete(false)
		atomic.AddInt64(&t.tcpFlows, 1)
		conn := gonet.NewTCPConn(&wq, ep)
		t.handler.HandleTCP(
			id.RemoteAddress.String(), int(id.RemotePort),
			id.LocalAddress.String(), int(id.LocalPort),
			&tcpConn{Conn: conn},
		)
	})
	t.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)
}

// installUDPForwarder wires an accept-all UDP forwarder. Each new association is
// wrapped and delivered to Kotlin, which paces + relays it upstream.
func (t *Tunnel) installUDPForwarder() {
	// udp.ForwarderHandler returns (handled bool): true if we took the flow,
	// false to let the stack reject it (e.g. emit ICMP unreachable).
	fwd := udp.NewForwarder(t.stack, func(r *udp.ForwarderRequest) (handled bool) {
		defer func() {
			if rec := recover(); rec != nil {
				t.logf(logError, "recovered panic in UDP forwarder: %v", rec)
				handled = false
			}
		}()
		id := r.ID()
		if t.rejectV6(id.LocalAddress) {
			return false // drop; stack may emit ICMPv6 unreachable
		}
		var wq waiter.Queue
		ep, tErr := r.CreateEndpoint(&wq)
		if tErr != nil {
			t.logf(logWarn, "udp CreateEndpoint failed: %v", tErr)
			return false
		}
		atomic.AddInt64(&t.udpFlows, 1)
		conn := gonet.NewUDPConn(&wq, ep)
		t.handler.HandleUDP(
			id.RemoteAddress.String(), int(id.RemotePort),
			id.LocalAddress.String(), int(id.LocalPort),
			&udpConn{conn: conn},
		)
		return true
	})
	t.stack.SetTransportProtocolHandler(udp.ProtocolNumber, fwd.HandlePacket)
}
