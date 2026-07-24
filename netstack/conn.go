package bridge

import (
	"net"
	"time"

	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
)

// tcpConn adapts gonet.TCPConn to the gomobile-friendly TCPConn interface.
// Read/Write are blocking; the Kotlin relay runs them on an IO dispatcher.
type tcpConn struct {
	Conn *gonet.TCPConn
}

func (c *tcpConn) Read(p []byte) (int, error)  { return c.Conn.Read(p) }
func (c *tcpConn) Write(p []byte) (int, error) { return c.Conn.Write(p) }
func (c *tcpConn) Close() error                { return c.Conn.Close() }

// udpConn adapts gonet.UDPConn. Receive returns one datagram's payload; a read
// deadline bounds idle associations so goroutines don't leak.
type udpConn struct {
	conn *gonet.UDPConn
}

func (c *udpConn) Receive() ([]byte, error) {
	buf := make([]byte, 64*1024)
	_ = c.conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	n, err := c.conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

func (c *udpConn) Send(p []byte) error {
	_, err := c.conn.Write(p)
	return err
}

func (c *udpConn) Close() error { return c.conn.Close() }

// compile-time interface checks.
var (
	_ TCPConn  = (*tcpConn)(nil)
	_ UDPConn  = (*udpConn)(nil)
	_ net.Conn = (*gonet.TCPConn)(nil)
)
