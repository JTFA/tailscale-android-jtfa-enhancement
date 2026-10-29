// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/net/tsdial"
)

// tsDialerForProxy is populated by the backend once the engine exists. The
// proxy uses it to reach tailnet addresses; everything else goes out over the
// phone's own network, exactly like Every Proxy does.
var tsDialerForProxy atomic.Pointer[tsdial.Dialer]

// proxyConfig mirrors the user's proxy settings. It is passed from Kotlin as
// JSON because gomobile does not handle struct fields with optional semantics
// well.
type proxyConfig struct {
	BindAddr    string `json:"bindAddr"`
	HTTPPort    int    `json:"httpPort"`
	SOCKS4Port  int    `json:"socks4Port"`
	SOCKS5Port  int    `json:"socks5Port"`
	PACPort     int    `json:"pacPort"`
	Username    string `json:"username"`
	Password    string `json:"password"`
	Egress      string `json:"egress"`
	EnableHTTP  bool   `json:"enableHttp"`
	EnableSOCKS bool   `json:"enableSocks"`
	EnablePAC   bool   `json:"enablePac"`
}

// proxyServer runs an HTTP/HTTPS (CONNECT), SOCKS4/SOCKS5 and PAC server for
// the LAN and the tailnet.
type proxyServer struct {
	mu      sync.Mutex
	cfg     proxyConfig
	running bool

	httpLn  net.Listener
	socksLn net.Listener
	pacLn   net.Listener
	pacSrv  *http.Server

	conns map[net.Conn]struct{}
	wg    sync.WaitGroup

	dial func(ctx context.Context, network, address string) (net.Conn, error)

	current   atomic.Int64
	maxSeen   atomic.Int64
	total     atomic.Int64
	bytesUp   atomic.Int64
	bytesDown atomic.Int64
}

var proxy = &proxyServer{conns: make(map[net.Conn]struct{})}

func (p *proxyServer) start(cfg proxyConfig) error {
	p.stop()

	if cfg.BindAddr == "" {
		cfg.BindAddr = "0.0.0.0"
	}
	if cfg.Egress == "" {
		cfg.Egress = "tailscale"
	}

	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		if d := tsDialerForProxy.Load(); d != nil {
			if cfg.Egress == "tailscale" {
				return d.UserDial(ctx, network, address)
			}
			// Direct means "use this device's own network". A plain net.Dialer
			// here would not be VpnService.protect()ed, so the connection would
			// be routed straight back into our TUN instead of leaving the phone.
			return d.SystemDial(ctx, network, address)
		}
		var nd net.Dialer
		return nd.DialContext(ctx, network, address)
	}

	p.mu.Lock()
	p.cfg = cfg
	p.dial = dial
	p.running = true
	p.mu.Unlock()

	var errs []string
	if cfg.EnableHTTP && cfg.HTTPPort > 0 {
		ln, err := net.Listen("tcp", net.JoinHostPort(cfg.BindAddr, strconv.Itoa(cfg.HTTPPort)))
		if err != nil {
			errs = append(errs, fmt.Sprintf("HTTP: %v", err))
		} else {
			p.mu.Lock()
			p.httpLn = ln
			p.mu.Unlock()
			p.wg.Add(1)
			go func() {
				defer p.wg.Done()
				p.acceptHTTP(ln)
			}()
		}
	}
	if cfg.EnableSOCKS && cfg.SOCKS4Port > 0 && cfg.SOCKS4Port == cfg.SOCKS5Port {
		ln, err := net.Listen("tcp", net.JoinHostPort(cfg.BindAddr, strconv.Itoa(cfg.SOCKS5Port)))
		if err != nil {
			errs = append(errs, fmt.Sprintf("SOCKS: %v", err))
		} else {
			p.mu.Lock()
			p.socksLn = ln
			p.mu.Unlock()
			p.wg.Add(1)
			go func() {
				defer p.wg.Done()
				p.acceptSOCKS(ln)
			}()
		}
	} else if cfg.EnableSOCKS {
		if cfg.SOCKS4Port > 0 {
			ln, err := net.Listen("tcp", net.JoinHostPort(cfg.BindAddr, strconv.Itoa(cfg.SOCKS4Port)))
			if err != nil {
				errs = append(errs, fmt.Sprintf("SOCKS4: %v", err))
			} else {
				p.mu.Lock()
				p.socksLn = ln
				p.mu.Unlock()
				p.wg.Add(1)
				go func() {
					defer p.wg.Done()
					p.acceptSOCKS(ln)
				}()
			}
		}
		if cfg.SOCKS5Port > 0 {
			ln, err := net.Listen("tcp", net.JoinHostPort(cfg.BindAddr, strconv.Itoa(cfg.SOCKS5Port)))
			if err != nil {
				errs = append(errs, fmt.Sprintf("SOCKS5: %v", err))
			} else {
				p.mu.Lock()
				p.socksLn = ln
				p.mu.Unlock()
				p.wg.Add(1)
				go func() {
					defer p.wg.Done()
					p.acceptSOCKS(ln)
				}()
			}
		}
	}
	if cfg.EnablePAC && cfg.PACPort > 0 {
		ln, err := net.Listen("tcp", net.JoinHostPort(cfg.BindAddr, strconv.Itoa(cfg.PACPort)))
		if err != nil {
			errs = append(errs, fmt.Sprintf("PAC: %v", err))
		} else {
			srv := &http.Server{Handler: http.HandlerFunc(p.servePAC)}
			p.mu.Lock()
			p.pacLn = ln
			p.pacSrv = srv
			p.mu.Unlock()
			p.wg.Add(1)
			go func() {
				defer p.wg.Done()
				_ = srv.Serve(ln)
			}()
		}
	}

	if len(errs) > 0 {
		p.stop()
		return fmt.Errorf("%s", strings.Join(errs, "; "))
	}
	log.Printf("proxy: started on %s (http=%d socks4=%d socks5=%d pac=%d egress=%s)",
		cfg.BindAddr, cfg.HTTPPort, cfg.SOCKS4Port, cfg.SOCKS5Port, cfg.PACPort, cfg.Egress)
	return nil
}

func (p *proxyServer) stop() {
	p.mu.Lock()
	if !p.running {
		p.mu.Unlock()
		return
	}
	p.running = false
	httpLn, socksLn, pacLn, pacSrv := p.httpLn, p.socksLn, p.pacLn, p.pacSrv
	p.httpLn, p.socksLn, p.pacLn, p.pacSrv = nil, nil, nil, nil
	conns := make([]net.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.mu.Unlock()

	for _, ln := range []net.Listener{httpLn, socksLn, pacLn} {
		if ln != nil {
			_ = ln.Close()
		}
	}
	if pacSrv != nil {
		_ = pacSrv.Close()
	}
	for _, c := range conns {
		_ = c.Close()
	}
	p.wg.Wait()
	log.Printf("proxy: stopped")
}

func (p *proxyServer) isRunning() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.running
}

func (p *proxyServer) acceptHTTP(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			p.handleHTTP(c)
		}()
	}
}

func (p *proxyServer) acceptSOCKS(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			p.handleSOCKS(c)
		}()
	}
}

func (p *proxyServer) handleHTTP(c net.Conn) {
	defer c.Close()
	br := bufio.NewReader(c)
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Minute))
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	_ = c.SetReadDeadline(time.Time{})

	if !p.checkHTTPAuth(req) {
		_, _ = io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\n"+
			"Proxy-Authenticate: Basic realm=\"Every Proxy\"\r\n"+
			"Content-Length: 0\r\n\r\n")
		return
	}

	if req.Method == http.MethodConnect {
		target := req.Host
		if !hasPort(target) {
			target += ":443"
		}
		up, err := p.dialContext(target)
		if err != nil {
			_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			return
		}
		defer up.Close()
		if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		p.pipe(c, up)
		return
	}

	if req.URL == nil || req.URL.Host == "" {
		_, _ = io.WriteString(c, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
		return
	}
	target := req.URL.Host
	if !hasPort(target) {
		target += ":80"
	}
	up, err := p.dialContext(target)
	if err != nil {
		_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
		return
	}
	defer up.Close()

	req.RequestURI = ""
	req.Header.Del("Proxy-Authorization")
	req.Header.Del("Proxy-Connection")
	if err := req.Write(up); err != nil {
		return
	}
	resp, err := http.ReadResponse(bufio.NewReader(up), req)
	if err != nil {
		_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
		return
	}
	defer resp.Body.Close()
	_ = resp.Write(c)
}

func (p *proxyServer) checkHTTPAuth(req *http.Request) bool {
	p.mu.Lock()
	user, pass := p.cfg.Username, p.cfg.Password
	p.mu.Unlock()
	if user == "" {
		return true
	}
	header := req.Header.Get("Proxy-Authorization")
	const prefix = "Basic "
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(header[len(prefix):]))
	if err != nil {
		return false
	}
	gotUser, gotPass, ok := strings.Cut(string(raw), ":")
	return ok && gotUser == user && gotPass == pass
}

func (p *proxyServer) dialContext(target string) (net.Conn, error) {
	p.mu.Lock()
	dial := p.dial
	p.mu.Unlock()
	if dial == nil {
		return nil, fmt.Errorf("proxy not started")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return dial(ctx, "tcp", target)
}

// pipe copies in both directions, counting bytes and tracking the active
// connection for the status screen.
func (p *proxyServer) pipe(client, upstream net.Conn) {
	p.mu.Lock()
	p.conns[client] = struct{}{}
	p.mu.Unlock()
	cur := p.current.Add(1)
	for {
		max := p.maxSeen.Load()
		if cur <= max || p.maxSeen.CompareAndSwap(max, cur) {
			break
		}
	}
	p.total.Add(1)
	defer func() {
		_ = client.Close()
		_ = upstream.Close()
		p.mu.Lock()
		delete(p.conns, client)
		p.mu.Unlock()
		p.current.Add(-1)
	}()

	done := make(chan struct{}, 2)
	go func() {
		n, _ := io.Copy(upstream, client)
		p.bytesUp.Add(n)
		done <- struct{}{}
	}()
	go func() {
		n, _ := io.Copy(client, upstream)
		p.bytesDown.Add(n)
		done <- struct{}{}
	}()
	<-done
	<-done
}

func hasPort(hostport string) bool {
	_, port, err := net.SplitHostPort(hostport)
	return err == nil && port != ""
}

// SOCKS5 / SOCKS4

func (p *proxyServer) handleSOCKS(c net.Conn) {
	defer c.Close()
	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	br := bufio.NewReader(c)

	ver, err := br.ReadByte()
	if err != nil {
		return
	}
	switch ver {
	case 5:
		p.handleSOCKS5(c, br)
	case 4:
		p.handleSOCKS4(c, br)
	}
}

func (p *proxyServer) handleSOCKS5(c net.Conn, br *bufio.Reader) {
	nMethods, err := br.ReadByte()
	if err != nil {
		return
	}
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}

	p.mu.Lock()
	needAuth := p.cfg.Username != ""
	user, pass := p.cfg.Username, p.cfg.Password
	p.mu.Unlock()

	want := byte(0x00)
	if needAuth {
		want = 0x02
	}
	offered := false
	for _, m := range methods {
		if m == want {
			offered = true
			break
		}
	}
	if !offered {
		_, _ = c.Write([]byte{0x05, 0xFF})
		return
	}
	if _, err := c.Write([]byte{0x05, want}); err != nil {
		return
	}

	if needAuth {
		// RFC 1929 username/password sub-negotiation.
		head := make([]byte, 2)
		if _, err := io.ReadFull(br, head); err != nil || head[0] != 1 {
			return
		}
		u := make([]byte, int(head[1]))
		if _, err := io.ReadFull(br, u); err != nil {
			return
		}
		pl, err := br.ReadByte()
		if err != nil {
			return
		}
		pw := make([]byte, int(pl))
		if _, err := io.ReadFull(br, pw); err != nil {
			return
		}
		if string(u) != user || string(pw) != pass {
			_, _ = c.Write([]byte{0x01, 0x01})
			return
		}
		if _, err := c.Write([]byte{0x01, 0x00}); err != nil {
			return
		}
	}

	// Request: VER CMD RSV ATYP ...
	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil || req[0] != 5 {
		return
	}
	if req[1] != 0x01 { // CONNECT only
		_, _ = c.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	host, err := readSOCKSAddr(br, req[3])
	if err != nil {
		_, _ = c.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(br, portBuf); err != nil {
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(portBuf))))

	up, err := p.dialContext(target)
	if err != nil {
		_, _ = c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()
	if _, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	_ = c.SetReadDeadline(time.Time{})
	// Anything already buffered belongs to the tunnel.
	if n := br.Buffered(); n > 0 {
		buf := make([]byte, n)
		if _, err := io.ReadFull(br, buf); err == nil {
			_, _ = up.Write(buf)
			p.bytesUp.Add(int64(n))
		}
	}
	p.pipe(c, up)
}

func (p *proxyServer) handleSOCKS4(c net.Conn, br *bufio.Reader) {
	p.mu.Lock()
	needAuth := p.cfg.Username != ""
	p.mu.Unlock()

	head := make([]byte, 7) // CMD(1) DSTPORT(2) DSTIP(4)
	if _, err := io.ReadFull(br, head); err != nil {
		return
	}
	if head[0] != 0x01 || needAuth {
		_, _ = c.Write([]byte{0x00, 0x5B, 0, 0, 0, 0, 0, 0})
		return
	}
	port := binary.BigEndian.Uint16(head[1:3])
	ip := net.IP(head[3:7])

	// Read USERID up to NUL.
	if _, err := readCString(br); err != nil {
		return
	}
	host := ip.String()
	if ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0 {
		// SOCKS4a: the client sent a domain name.
		domain, err := readCString(br)
		if err != nil {
			return
		}
		host = domain
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))

	up, err := p.dialContext(target)
	if err != nil {
		_, _ = c.Write([]byte{0x00, 0x5B, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()
	if _, err := c.Write([]byte{0x00, 0x5A, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	_ = c.SetReadDeadline(time.Time{})
	if n := br.Buffered(); n > 0 {
		buf := make([]byte, n)
		if _, err := io.ReadFull(br, buf); err == nil {
			_, _ = up.Write(buf)
			p.bytesUp.Add(int64(n))
		}
	}
	p.pipe(c, up)
}

func readSOCKSAddr(br *bufio.Reader, atyp byte) (string, error) {
	switch atyp {
	case 0x01:
		b := make([]byte, 4)
		if _, err := io.ReadFull(br, b); err != nil {
			return "", err
		}
		return net.IP(b).String(), nil
	case 0x04:
		b := make([]byte, 16)
		if _, err := io.ReadFull(br, b); err != nil {
			return "", err
		}
		return net.IP(b).String(), nil
	case 0x03:
		l, err := br.ReadByte()
		if err != nil {
			return "", err
		}
		b := make([]byte, int(l))
		if _, err := io.ReadFull(br, b); err != nil {
			return "", err
		}
		return string(b), nil
	}
	return "", fmt.Errorf("unknown address type %d", atyp)
}

func readCString(br *bufio.Reader) (string, error) {
	var sb strings.Builder
	for {
		b, err := br.ReadByte()
		if err != nil {
			return "", err
		}
		if b == 0 {
			return sb.String(), nil
		}
		if sb.Len() > 1024 {
			return "", fmt.Errorf("string too long")
		}
		sb.WriteByte(b)
	}
}

// PAC

func (p *proxyServer) servePAC(w http.ResponseWriter, r *http.Request) {
	host := r.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}

	p.mu.Lock()
	cfg := p.cfg
	p.mu.Unlock()

	var parts []string
	if cfg.EnableHTTP && cfg.HTTPPort > 0 {
		parts = append(parts, fmt.Sprintf("PROXY %s", net.JoinHostPort(host, strconv.Itoa(cfg.HTTPPort))))
	}
	if cfg.EnableSOCKS && cfg.SOCKS5Port > 0 {
		parts = append(parts, fmt.Sprintf("SOCKS5 %s", net.JoinHostPort(host, strconv.Itoa(cfg.SOCKS5Port))))
	}
	parts = append(parts, "DIRECT")

	script := "function FindProxyForURL(url, host) {\n" +
		"  return \"" + strings.Join(parts, "; ") + "\";\n" +
		"}\n"
	w.Header().Set("Content-Type", "application/x-ns-proxy-autoconfig")
	_, _ = io.WriteString(w, script)
}

// status

func (p *proxyServer) status() map[string]any {
	p.mu.Lock()
	cfg := p.cfg
	running := p.running
	p.mu.Unlock()

	return map[string]any{
		"running":   running,
		"bindAddr":  cfg.BindAddr,
		"httpPort":  cfg.HTTPPort,
		"socksPort": cfg.SOCKS5Port,
		"pacPort":   cfg.PACPort,
		"egress":    cfg.Egress,
		"auth":      cfg.Username != "",
		"hosts":     localIPv4Addrs(),
		"current":   p.current.Load(),
		"max":       p.maxSeen.Load(),
		"total":     p.total.Load(),
		"bytesUp":   p.bytesUp.Load(),
		"bytesDown": p.bytesDown.Load(),
	}
}

func (p *proxyServer) statusJSON() string {
	b, err := json.Marshal(p.status())
	if err != nil {
		return "{}"
	}
	return string(b)
}

func localIPv4Addrs() []string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	var out []string
	for _, a := range addrs {
		ipNet, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip := ipNet.IP.To4()
		if ip == nil || ip.IsLoopback() {
			continue
		}
		out = append(out, ip.String())
	}
	return out
}

// App methods exposed to Kotlin via gomobile.

func (a *App) StartProxy(configJSON string) (string, error) {
	var cfg proxyConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "", fmt.Errorf("invalid proxy config: %w", err)
	}
	if err := proxy.start(cfg); err != nil {
		return "", err
	}
	return proxy.statusJSON(), nil
}

func (a *App) StopProxy() {
	proxy.stop()
}

func (a *App) ProxyStatusJSON() string {
	return proxy.statusJSON()
}
