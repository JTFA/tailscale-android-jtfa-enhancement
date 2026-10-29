// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// adBlockEngine implements DNS-level ad blocking for the local device.
//
// Every app's DNS query inside the tunnel is a UDP packet that libtailscale
// reads from the TUN device. Blocked queries are answered locally (A -> 0.0.0.0,
// AAAA -> ::, other types -> empty answer) and never reach the tunnel, which
// keeps the upstream resolver and the network out of the loop.
//
// Filtering is domain based: rules list domains (hosts-file style) or anchored
// domain suffixes (AdGuard/uBlock "||example.com^" style). Regular expression
// rules, URL paths and cosmetic filters are out of scope because this runs at
// the DNS layer.
type adBlockEngine struct {
	mu      sync.RWMutex
	enabled bool
	rules   *adBlockRuleSet

	// ignored holds package names that are never filtered.
	ignored map[string]struct{}

	lookup UIDLookup
	// appCache maps a source socket to the package that owns it, because the
	// platform lookup is comparatively expensive.
	appCache map[netip.AddrPort]appCacheEntry

	stats adBlockStats
}

type appCacheEntry struct {
	pkg     string
	expires time.Time
}

const adBlockAppCacheTTL = 60 * time.Second

var adBlock = newAdBlockEngine()

func newAdBlockEngine() *adBlockEngine {
	return &adBlockEngine{
		rules:    newAdBlockRuleSet(),
		ignored:  make(map[string]struct{}),
		appCache: make(map[netip.AddrPort]appCacheEntry),
		stats:    adBlockStats{apps: make(map[string]*adBlockAppStats)},
	}
}

func (e *adBlockEngine) setEnabled(v bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.enabled = v
}

func (e *adBlockEngine) isEnabled() bool {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.enabled
}

func (e *adBlockEngine) setRules(rs *adBlockRuleSet) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rules = rs
}

func (e *adBlockEngine) setIgnored(pkgs map[string]struct{}) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.ignored = pkgs
}

func (e *adBlockEngine) setUIDLookup(l UIDLookup) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.lookup = l
	e.appCache = make(map[netip.AddrPort]appCacheEntry)
}

// handlePacket inspects an IP packet read from the TUN device. It returns a
// reply packet when the packet is a DNS query for a blocked domain, and nil
// when the packet should be passed through unchanged.
func (e *adBlockEngine) handlePacket(pkt []byte) []byte {
	if len(pkt) < 28 || !e.isEnabled() {
		return nil
	}
	switch pkt[0] >> 4 {
	case 4:
		return e.handleIPv4(pkt)
	case 6:
		return e.handleIPv6(pkt)
	}
	return nil
}

func (e *adBlockEngine) handleIPv4(pkt []byte) []byte {
	ihl := int(pkt[0]&0x0f) * 4
	if ihl < 20 || len(pkt) < ihl+8 || pkt[9] != 17 { // UDP
		return nil
	}
	total := int(binary.BigEndian.Uint16(pkt[2:4]))
	if total < ihl+8 || total > len(pkt) {
		return nil
	}
	udp := pkt[ihl:total]
	if binary.BigEndian.Uint16(udp[2:4]) != 53 {
		return nil
	}

	src := netip.AddrFrom4([4]byte(pkt[12:16]))
	dst := netip.AddrFrom4([4]byte(pkt[16:20]))
	srcPort := binary.BigEndian.Uint16(udp[0:2])

	resp, blocked := e.inspectQuery(udp[8:], netip.AddrPortFrom(src, srcPort), netip.AddrPortFrom(dst, 53))
	if !blocked {
		return nil
	}

	replyLen := ihl + 8 + len(resp)
	out := make([]byte, replyLen)
	copy(out, pkt[:ihl])
	copy(out[12:16], pkt[16:20])
	copy(out[16:20], pkt[12:16])
	out[8] = 64 // TTL
	binary.BigEndian.PutUint16(out[2:4], uint16(replyLen))
	out[10], out[11] = 0, 0
	binary.BigEndian.PutUint16(out[10:12], ipChecksum(out[:ihl]))

	outUDP := out[ihl:]
	copy(outUDP[0:2], udp[2:4]) // reply source port = query destination port
	copy(outUDP[2:4], udp[0:2]) // reply destination port = query source port
	binary.BigEndian.PutUint16(outUDP[4:6], uint16(8+len(resp)))
	binary.BigEndian.PutUint16(outUDP[6:8], 0) // optional for IPv4
	copy(outUDP[8:], resp)
	return out
}

func (e *adBlockEngine) handleIPv6(pkt []byte) []byte {
	if len(pkt) < 48 || pkt[6] != 17 { // UDP, no extension headers
		return nil
	}
	plen := int(binary.BigEndian.Uint16(pkt[4:6]))
	if plen < 8 || 40+plen > len(pkt) {
		return nil
	}
	udp := pkt[40 : 40+plen]
	if binary.BigEndian.Uint16(udp[2:4]) != 53 {
		return nil
	}

	var srcB, dstB [16]byte
	copy(srcB[:], pkt[8:24])
	copy(dstB[:], pkt[24:40])
	src := netip.AddrFrom16(srcB)
	dst := netip.AddrFrom16(dstB)
	srcPort := binary.BigEndian.Uint16(udp[0:2])

	resp, blocked := e.inspectQuery(udp[8:], netip.AddrPortFrom(src, srcPort), netip.AddrPortFrom(dst, 53))
	if !blocked {
		return nil
	}

	replyLen := 40 + 8 + len(resp)
	out := make([]byte, replyLen)
	copy(out, pkt[:40])
	copy(out[8:24], pkt[24:40])
	copy(out[24:40], pkt[8:24])
	out[7] = 64 // hop limit
	binary.BigEndian.PutUint16(out[4:6], uint16(8+len(resp)))

	outUDP := out[40:]
	copy(outUDP[0:2], udp[2:4])
	copy(outUDP[2:4], udp[0:2])
	binary.BigEndian.PutUint16(outUDP[4:6], uint16(8+len(resp)))
	binary.BigEndian.PutUint16(outUDP[6:8], 0)
	copy(outUDP[8:], resp)

	var srcOut, dstOut [16]byte
	copy(srcOut[:], out[8:24])
	copy(dstOut[:], out[24:40])
	binary.BigEndian.PutUint16(outUDP[6:8], udpChecksumV6(srcOut, dstOut, outUDP))
	return out
}

// inspectQuery parses a DNS query and records it in the counters. It returns a
// DNS response when the queried name is blocked.
func (e *adBlockEngine) inspectQuery(payload []byte, from, to netip.AddrPort) ([]byte, bool) {
	if len(payload) < 12 {
		return nil, false
	}

	var parser dnsmessage.Parser
	hdr, err := parser.Start(payload)
	if err != nil || hdr.Response {
		return nil, false
	}
	q, err := parser.Question()
	if err != nil {
		return nil, false
	}
	name := strings.ToLower(strings.TrimSuffix(q.Name.String(), "."))
	if name == "" {
		return nil, false
	}

	pkg := e.packageFor(from, to)
	blockable := e.packageBlockable(pkg)

	e.mu.RLock()
	rules := e.rules
	e.mu.RUnlock()

	blocked := blockable && rules != nil && rules.blocked(name)
	e.stats.record(pkg, blocked)
	if !blocked {
		return nil, false
	}

	resp, err := buildBlockedResponse(hdr, q)
	if err != nil {
		return nil, false
	}
	return resp, true
}

// packageFor resolves the source socket to a package name, using a short-lived
// cache. It returns "" when attribution is unavailable.
func (e *adBlockEngine) packageFor(from, to netip.AddrPort) string {
	e.mu.RLock()
	lookup := e.lookup
	if lookup == nil {
		e.mu.RUnlock()
		return ""
	}
	if ent, ok := e.appCache[from]; ok && time.Now().Before(ent.expires) {
		e.mu.RUnlock()
		return ent.pkg
	}
	e.mu.RUnlock()

	uid := lookup.LookupUID(17, from.Addr().String(), int32(from.Port()), to.Addr().String(), int32(to.Port()))
	pkg := ""
	if uid >= 0 {
		pkg = lookup.PackageForUID(uid)
	}

	e.mu.Lock()
	if len(e.appCache) > 4096 {
		e.appCache = make(map[netip.AddrPort]appCacheEntry)
	}
	e.appCache[from] = appCacheEntry{pkg: pkg, expires: time.Now().Add(adBlockAppCacheTTL)}
	e.mu.Unlock()
	return pkg
}

func (e *adBlockEngine) packageBlockable(pkg string) bool {
	if pkg == "" {
		// Without attribution the safe default is to filter: the master switch
		// is on and the per-app list only ever narrows the set.
		return true
	}
	e.mu.RLock()
	defer e.mu.RUnlock()
	_, ignored := e.ignored[pkg]
	return !ignored
}

func buildBlockedResponse(hdr dnsmessage.Header, q dnsmessage.Question) ([]byte, error) {
	b := dnsmessage.NewBuilder(make([]byte, 0, 512), dnsmessage.Header{
		ID:                 hdr.ID,
		Response:           true,
		OpCode:             hdr.OpCode,
		RecursionDesired:   hdr.RecursionDesired,
		RecursionAvailable: true,
		RCode:              dnsmessage.RCodeSuccess,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(q); err != nil {
		return nil, err
	}
	if err := b.StartAnswers(); err != nil {
		return nil, err
	}
	h := dnsmessage.ResourceHeader{
		Name:  q.Name,
		Class: dnsmessage.ClassINET,
		TTL:   60,
	}
	switch q.Type {
	case dnsmessage.TypeA:
		h.Type = dnsmessage.TypeA
		_ = b.AResource(h, dnsmessage.AResource{})
	case dnsmessage.TypeAAAA:
		h.Type = dnsmessage.TypeAAAA
		_ = b.AAAAResource(h, dnsmessage.AAAAResource{})
	}
	return b.Finish()
}

// adBlockRuleSet is a domain matcher with allow rules taking precedence.
type adBlockRuleSet struct {
	exact       map[string]struct{}
	suffix      map[string]struct{}
	allowExact  map[string]struct{}
	allowSuffix map[string]struct{}
	count       int64
}

func newAdBlockRuleSet() *adBlockRuleSet {
	return &adBlockRuleSet{
		exact:       make(map[string]struct{}),
		suffix:      make(map[string]struct{}),
		allowExact:  make(map[string]struct{}),
		allowSuffix: make(map[string]struct{}),
	}
}

func (rs *adBlockRuleSet) blocked(name string) bool {
	if _, ok := rs.allowExact[name]; ok {
		return false
	}
	if matchDomainSuffix(rs.allowSuffix, name) {
		return false
	}
	if _, ok := rs.exact[name]; ok {
		return true
	}
	return matchDomainSuffix(rs.suffix, name)
}

func matchDomainSuffix(set map[string]struct{}, name string) bool {
	for {
		if _, ok := set[name]; ok {
			return true
		}
		i := strings.IndexByte(name, '.')
		if i < 0 {
			return false
		}
		name = name[i+1:]
	}
}

// parseAdBlockRules turns filter text into a rule set. It understands
//
//   - hosts file lines: "0.0.0.0 ads.example.com"
//   - plain domains: "ads.example.com"
//   - AdGuard/uBlock domain anchors: "||ads.example.com^"
//   - exceptions for any of the above prefixed with "@@"
//
// Comments ("!" and "#") and unsupported rules (regexes, URL paths, cosmetic
// filters, rules with options) are skipped.
func parseAdBlockRules(text string) *adBlockRuleSet {
	rs := newAdBlockRuleSet()
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		if parseAdBlockRuleLine(scanner.Text(), rs) {
			rs.count++
		}
	}
	return rs
}

func parseAdBlockRuleLine(line string, rs *adBlockRuleSet) bool {
	line = strings.TrimSpace(line)
	if line == "" || strings.HasPrefix(line, "!") || strings.HasPrefix(line, "[") {
		return false
	}

	allow := false
	if strings.HasPrefix(line, "@@") {
		allow = true
		line = strings.TrimSpace(line[2:])
		if line == "" {
			return false
		}
	}

	// Hosts file entries: address followed by one or more hostnames.
	if fields := strings.Fields(line); len(fields) >= 2 && isHostsAddress(fields[0]) {
		added := false
		for _, f := range fields[1:] {
			if i := strings.IndexByte(f, '#'); i >= 0 {
				f = f[:i]
			}
			if d, ok := normalizeRuleDomain(f); ok {
				addDomainRule(rs, d, allow, false)
				added = true
			}
		}
		return added
	}

	// Strip trailing comments on plain entries.
	if i := strings.IndexByte(line, '#'); i >= 0 {
		line = strings.TrimSpace(line[:i])
	}
	if line == "" {
		return false
	}

	// AdGuard/uBlock domain anchor. Anything with options, wildcards, paths or
	// regex syntax is not a plain domain rule and is skipped.
	if strings.HasPrefix(line, "||") {
		rest := line[2:]
		if i := strings.IndexByte(rest, '^'); i >= 0 {
			rest = rest[:i]
		}
		if rest == "" || strings.ContainsAny(rest, "*$/|") {
			return false
		}
		d, ok := normalizeRuleDomain(rest)
		if !ok {
			return false
		}
		addDomainRule(rs, d, allow, true)
		return true
	}

	if strings.HasPrefix(line, "|") || strings.ContainsAny(line, "*$/^|") {
		return false
	}

	d, ok := normalizeRuleDomain(line)
	if !ok {
		return false
	}
	addDomainRule(rs, d, allow, false)
	return true
}

func isHostsAddress(s string) bool {
	switch s {
	case "0.0.0.0", "127.0.0.1", "::", "::1":
		return true
	}
	return false
}

func addDomainRule(rs *adBlockRuleSet, domain string, allow, suffix bool) {
	switch {
	case allow && suffix:
		rs.allowSuffix[domain] = struct{}{}
	case allow:
		rs.allowExact[domain] = struct{}{}
	case suffix:
		rs.suffix[domain] = struct{}{}
	default:
		rs.exact[domain] = struct{}{}
	}
}

// normalizeRuleDomain validates a rule's domain and normalizes it.
func normalizeRuleDomain(s string) (string, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.TrimSuffix(s, ".")
	if len(s) < 3 || len(s) > 253 || !strings.Contains(s, ".") {
		return "", false
	}
	if strings.HasPrefix(s, ".") || strings.HasPrefix(s, "*.") {
		s = strings.TrimPrefix(strings.TrimPrefix(s, "*"), ".")
	}
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= '0' && r <= '9':
		case r == '-' || r == '.' || r == '_':
		default:
			return "", false
		}
	}
	return s, true
}

type adBlockAppStats struct {
	Queries int64 `json:"queries"`
	Blocked int64 `json:"blocked"`
}

type adBlockStats struct {
	mu           sync.Mutex
	day          string
	total        int64
	blocked      int64
	today        int64
	todayBlocked int64
	apps         map[string]*adBlockAppStats
}

func (s *adBlockStats) record(pkg string, blocked bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	day := time.Now().Format("2006-01-02")
	if day != s.day {
		s.day = day
		s.today = 0
		s.todayBlocked = 0
	}
	s.total++
	s.today++
	if blocked {
		s.blocked++
		s.todayBlocked++
	}
	if pkg != "" {
		a := s.apps[pkg]
		if a == nil {
			a = &adBlockAppStats{}
			s.apps[pkg] = a
		}
		a.Queries++
		if blocked {
			a.Blocked++
		}
	}
}

func (s *adBlockStats) snapshot() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	apps := make(map[string]adBlockAppStats, len(s.apps))
	for k, v := range s.apps {
		apps[k] = *v
	}
	return map[string]any{
		"total":        s.total,
		"blocked":      s.blocked,
		"today":        s.today,
		"todayBlocked": s.todayBlocked,
		"apps":         apps,
	}
}

func (e *adBlockEngine) statsJSON() string {
	b, err := json.Marshal(e.stats.snapshot())
	if err != nil {
		return "{}"
	}
	return string(b)
}

func ipChecksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(b[i])<<8 | uint32(b[i+1])
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func udpChecksumV6(src, dst [16]byte, udp []byte) uint16 {
	var sum uint32
	for _, b := range [][]byte{src[:], dst[:]} {
		for i := 0; i+1 < len(b); i += 2 {
			sum += uint32(b[i])<<8 | uint32(b[i+1])
		}
	}
	sum += uint32(len(udp))
	sum += 17 // next header
	for i := 0; i+1 < len(udp); i += 2 {
		sum += uint32(udp[i])<<8 | uint32(udp[i+1])
	}
	if len(udp)%2 == 1 {
		sum += uint32(udp[len(udp)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	c := ^uint16(sum)
	if c == 0 {
		return 0xffff
	}
	return c
}

// App methods exposed to Kotlin via gomobile.

func (a *App) SetAdBlockEnabled(enabled bool) {
	adBlock.setEnabled(enabled)
}

func (a *App) SetAdBlockRules(rules string) (int64, error) {
	rs := parseAdBlockRules(rules)
	adBlock.setRules(rs)
	return rs.count, nil
}

func (a *App) SetAdBlockIgnoredPackages(packages string) {
	set := make(map[string]struct{})
	for _, line := range strings.Split(packages, "\n") {
		if pkg := strings.TrimSpace(line); pkg != "" {
			set[pkg] = struct{}{}
		}
	}
	adBlock.setIgnored(set)
}

func (a *App) SetAdBlockUIDLookup(lookup UIDLookup) {
	adBlock.setUIDLookup(lookup)
}

func (a *App) AdBlockStatsJSON() string {
	return adBlock.statsJSON()
}
