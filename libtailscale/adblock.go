// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"net/netip"
	"regexp"
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

	blocked := blockable && rules != nil && rules.blocked(name, q.Type, pkg)
	e.stats.record(name, pkg, blocked)
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

// ruleKind describes how a rule's pattern is matched against a hostname.
type ruleKind int

const (
	ruleSubstring ruleKind = iota // plain text, matched anywhere in the hostname
	ruleSuffix                    // ||domain^ domain anchor
	ruleExact                     // |domain| exact hostname
	ruleRegex                     // /regex/
)

// dnsRule is one parsed AdGuard filter rule. Only the parts that can be
// evaluated for a DNS query are kept; cosmetic and content filters are skipped
// while the rule list is parsed.
type dnsRule struct {
	allow     bool
	important bool
	kind      ruleKind
	domain    string
	re        *regexp.Regexp
	dnsTypes  map[dnsmessage.Type]struct{}
	denyAllow []string
	apps      map[string]struct{}
	text      string // canonical form, used by $badfilter
}

// adBlockRuleSet holds rules evaluated at the DNS layer. $important blocking
// rules win over exceptions, exceptions win over ordinary blocking rules,
// mirroring AdGuard's evaluation order.
type adBlockRuleSet struct {
	block []*dnsRule
	allow []*dnsRule
	count int64
}

func newAdBlockRuleSet() *adBlockRuleSet {
	return &adBlockRuleSet{}
}

func (rs *adBlockRuleSet) blocked(host string, qtype dnsmessage.Type, pkg string) bool {
	if rs == nil || len(rs.block)+len(rs.allow) == 0 {
		return false
	}
	for _, r := range rs.block {
		if r.important && r.matches(host, qtype, pkg) {
			return true
		}
	}
	for _, r := range rs.allow {
		if r.matches(host, qtype, pkg) {
			return false
		}
	}
	for _, r := range rs.block {
		if r.matches(host, qtype, pkg) {
			return true
		}
	}
	return false
}

func (r *dnsRule) matches(host string, qtype dnsmessage.Type, pkg string) bool {
	if len(r.dnsTypes) > 0 {
		if _, ok := r.dnsTypes[qtype]; !ok {
			return false
		}
	}
	if len(r.apps) > 0 {
		if pkg == "" {
			return false
		}
		if _, ok := r.apps[pkg]; !ok {
			return false
		}
	}
	for _, d := range r.denyAllow {
		if host == d || strings.HasSuffix(host, "."+d) {
			return false
		}
	}
	switch r.kind {
	case ruleSuffix:
		return host == r.domain || strings.HasSuffix(host, "."+r.domain)
	case ruleExact:
		return host == r.domain
	case ruleRegex:
		return r.re != nil && r.re.MatchString(host)
	default:
		return strings.Contains(host, r.domain)
	}
}

// parseAdBlockRules turns AdGuard-style filter text into a rule set. Rules that
// cannot be evaluated for a plain DNS query (cosmetic filters, URL paths,
// content-type modifiers such as $script or $image, and header/cookie/rewrite
// modifiers) are skipped instead of being treated as matches.
func parseAdBlockRules(text string) *adBlockRuleSet {
	rs := newAdBlockRuleSet()
	var parsed []*dnsRule
	badfilter := make(map[string]struct{})
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 64*1024), 8*1024*1024)
	for scanner.Scan() {
		rules, isBadfilter, ok := parseAdBlockRuleLine(scanner.Text())
		if !ok {
			continue
		}
		if isBadfilter {
			for _, r := range rules {
				badfilter[r.text] = struct{}{}
			}
			continue
		}
		parsed = append(parsed, rules...)
	}
	seen := make(map[string]struct{}, len(parsed))
	for _, r := range parsed {
		if _, bad := badfilter[r.text]; bad {
			continue
		}
		if _, dup := seen[r.text]; dup {
			continue
		}
		seen[r.text] = struct{}{}
		rs.count++
		if r.allow {
			rs.allow = append(rs.allow, r)
		} else {
			rs.block = append(rs.block, r)
		}
	}
	return rs
}

// parseAdBlockRuleLine parses one filter list line. Hosts lines can carry more
// than one hostname, hence the slice result.
func parseAdBlockRuleLine(line string) ([]*dnsRule, bool, bool) {
	line = strings.TrimSpace(strings.TrimPrefix(line, "\ufeff"))
	if line == "" || strings.HasPrefix(line, "!") || strings.HasPrefix(line, "[") {
		return nil, false, false
	}
	// Cosmetic and scriptlet rules cannot be applied at the DNS layer.
	if strings.Contains(line, "##") || strings.Contains(line, "#@#") ||
		strings.Contains(line, "#?#") || strings.Contains(line, "#$#") ||
		strings.Contains(line, "#@$#") || strings.Contains(line, "#%#") ||
		strings.Contains(line, "#@%#") {
		return nil, false, false
	}

	allow := false
	if strings.HasPrefix(line, "@@") {
		allow = true
		line = strings.TrimSpace(line[2:])
		if line == "" {
			return nil, false, false
		}
	}

	// Hosts file entries: address followed by one or more hostnames.
	if fields := strings.Fields(line); len(fields) >= 2 && isHostsAddress(fields[0]) {
		out := make([]*dnsRule, 0, len(fields)-1)
		for _, f := range fields[1:] {
			if i := strings.IndexByte(f, '#'); i >= 0 {
				f = f[:i]
			}
			d, ok := normalizeDomainName(f)
			if !ok {
				continue
			}
			out = append(out, &dnsRule{
				allow:  allow,
				kind:   ruleExact,
				domain: d,
				text:   "hosts:" + d,
			})
		}
		return out, false, len(out) > 0
	}

	pattern := line
	opts := ""
	if i := strings.LastIndexByte(line, '$'); i > 0 && i < len(line)-1 && isOptionSuffix(line[i+1:]) {
		pattern = strings.TrimSpace(line[:i])
		opts = line[i+1:]
	}
	if i := strings.IndexByte(pattern, '#'); i >= 0 {
		pattern = strings.TrimSpace(pattern[:i])
	}
	if pattern == "" {
		return nil, false, false
	}

	kind, domain, re, ok := compileRulePattern(pattern)
	if !ok {
		return nil, false, false
	}
	r := &dnsRule{allow: allow, kind: kind, domain: domain, re: re}
	r.text = ruleIdentity(allow, pattern)
	bad := false
	if opts != "" {
		supported, isBadfilter := applyRuleOptions(r, opts)
		if !supported {
			return nil, false, false
		}
		bad = isBadfilter
	}
	return []*dnsRule{r}, bad, true
}

func ruleIdentity(allow bool, pattern string) string {
	if allow {
		return "@@" + strings.ToLower(pattern)
	}
	return strings.ToLower(pattern)
}

// isOptionSuffix reports whether s looks like a comma separated modifier list,
// which keeps "$" characters inside regular expressions from being mistaken for
// a modifier separator.
func isOptionSuffix(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		case r == '_' || r == '-' || r == '=' || r == '~' || r == '.' || r == ',' ||
			r == '|' || r == ':' || r == '*':
		default:
			return false
		}
	}
	return true
}

// applyRuleOptions applies the "$..." modifiers that make sense at the DNS
// layer. It reports whether the rule is supported at all and whether it is a
// $badfilter marker.
func applyRuleOptions(r *dnsRule, opts string) (supported bool, badfilter bool) {
	for _, part := range strings.Split(opts, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if strings.HasPrefix(part, "~") {
			// Negated modifiers ($~third-party and friends) need request
			// context that a DNS query does not have.
			return false, false
		}
		name, val, hasVal := strings.Cut(part, "=")
		name = strings.ToLower(strings.TrimSpace(name))
		val = strings.TrimSpace(val)
		switch name {
		case "important":
			r.important = true
		case "badfilter":
			badfilter = true
		case "document", "all", "network":
			// Applicable to the whole request, so they apply at the DNS layer.
		case "dnstype":
			types, ok := parseDNSTypes(val)
			if !ok {
				return false, false
			}
			r.dnsTypes = types
		case "denyallow":
			r.denyAllow = splitDomainList(val)
			if len(r.denyAllow) == 0 {
				return false, false
			}
		case "app":
			r.apps = toSet(splitList(val))
			if len(r.apps) == 0 {
				return false, false
			}
		default:
			// Content-type modifiers ($script, $image, ...), $domain,
			// $path, $third-party, header, cookie, redirect and rewrite
			// modifiers cannot be evaluated for a DNS query.
			_ = hasVal
			return false, false
		}
	}
	return true, badfilter
}

func parseDNSTypes(val string) (map[dnsmessage.Type]struct{}, bool) {
	set := make(map[dnsmessage.Type]struct{})
	for _, n := range splitList(val) {
		t, ok := dnsTypeByName(n)
		if !ok {
			return nil, false
		}
		set[t] = struct{}{}
	}
	return set, len(set) > 0
}

func dnsTypeByName(s string) (dnsmessage.Type, bool) {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "A":
		return dnsmessage.TypeA, true
	case "AAAA":
		return dnsmessage.TypeAAAA, true
	case "CNAME":
		return dnsmessage.TypeCNAME, true
	case "NS":
		return dnsmessage.TypeNS, true
	case "SOA":
		return dnsmessage.TypeSOA, true
	case "PTR":
		return dnsmessage.TypePTR, true
	case "MX":
		return dnsmessage.TypeMX, true
	case "TXT":
		return dnsmessage.TypeTXT, true
	case "SRV":
		return dnsmessage.TypeSRV, true
	case "SVCB":
		return dnsmessage.TypeSVCB, true
	case "HTTPS":
		return dnsmessage.TypeHTTPS, true
	case "HINFO":
		return dnsmessage.TypeHINFO, true
	case "ANY":
		return dnsmessage.TypeALL, true
	}
	return 0, false
}

func splitList(val string) []string {
	var out []string
	for _, p := range strings.FieldsFunc(val, func(r rune) bool {
		return r == '|' || r == ',' || r == '~'
	}) {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func splitDomainList(val string) []string {
	var out []string
	for _, p := range splitList(val) {
		if d, ok := normalizeDomainName(p); ok {
			out = append(out, d)
		}
	}
	return out
}

func toSet(items []string) map[string]struct{} {
	if len(items) == 0 {
		return nil
	}
	set := make(map[string]struct{}, len(items))
	for _, it := range items {
		set[it] = struct{}{}
	}
	return set
}

func isHostsAddress(s string) bool {
	switch s {
	case "0.0.0.0", "127.0.0.1", "::", "::1":
		return true
	}
	return false
}

// compileRulePattern turns the pattern part of a rule into a matcher.
func compileRulePattern(pattern string) (ruleKind, string, *regexp.Regexp, bool) {
	pattern = strings.TrimSpace(pattern)
	if pattern == "" {
		return 0, "", nil, false
	}

	// Regular expression rule: /re/
	if strings.HasPrefix(pattern, "/") && strings.HasSuffix(pattern, "/") && len(pattern) > 2 {
		re, err := regexp.Compile("(?i)" + pattern[1:len(pattern)-1])
		if err != nil {
			return 0, "", nil, false
		}
		return ruleRegex, "", re, true
	}

	// Domain anchor: ||example.com^
	if strings.HasPrefix(pattern, "||") {
		rest := pattern[2:]
		if strings.IndexByte(rest, '/') >= 0 {
			// URL path rules cannot be evaluated for a DNS query.
			return 0, "", nil, false
		}
		if i := strings.IndexByte(rest, '|'); i >= 0 {
			rest = rest[:i]
		}
		rest = strings.TrimSuffix(rest, "^")
		if rest == "" {
			return 0, "", nil, false
		}
		if strings.Contains(rest, "*") {
			re, err := wildcardRegex(rest, true)
			if err != nil {
				return 0, "", nil, false
			}
			return ruleRegex, "", re, true
		}
		d, ok := normalizeDomainName(rest)
		if !ok {
			return 0, "", nil, false
		}
		return ruleSuffix, d, nil, true
	}

	// Exact anchor: |example.com^
	if strings.HasPrefix(pattern, "|") {
		rest := strings.Trim(pattern, "|")
		rest = strings.TrimPrefix(rest, "http://")
		rest = strings.TrimPrefix(rest, "https://")
		if strings.IndexByte(rest, '/') >= 0 {
			return 0, "", nil, false
		}
		rest = strings.TrimSuffix(rest, "^")
		d, ok := normalizeDomainName(rest)
		if !ok {
			return 0, "", nil, false
		}
		return ruleExact, d, nil, true
	}

	// Plain rule: strip the separator anchor; it matches anywhere in the host.
	plain := strings.TrimSuffix(pattern, "^")
	if strings.IndexByte(plain, '/') >= 0 {
		return 0, "", nil, false
	}
	if strings.Contains(plain, "*") {
		re, err := wildcardRegex(plain, false)
		if err != nil {
			return 0, "", nil, false
		}
		return ruleRegex, "", re, true
	}
	d, ok := normalizeDomainName(plain)
	if !ok {
		return 0, "", nil, false
	}
	return ruleSubstring, d, nil, true
}

// wildcardRegex turns a pattern containing "*" into a regular expression.
// suffixAnchor keeps the match at a label boundary for "||" rules.
func wildcardRegex(pattern string, suffixAnchor bool) (*regexp.Regexp, error) {
	var b strings.Builder
	if suffixAnchor {
		b.WriteString(`(^|\.)`)
	}
	for i, part := range strings.Split(strings.ToLower(pattern), "*") {
		if i > 0 {
			b.WriteString(".*")
		}
		b.WriteString(regexp.QuoteMeta(part))
	}
	if suffixAnchor {
		b.WriteString(`$`)
	}
	return regexp.Compile("(?i)" + b.String())
}

// normalizeDomainName validates and normalizes a hostname.
func normalizeDomainName(s string) (string, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.TrimSuffix(s, ".")
	s = strings.TrimPrefix(s, ".")
	if s == "" || len(s) > 253 || strings.Contains(s, "..") || strings.HasPrefix(s, "-") {
		return "", false
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
	lastQuery    string
	lastBlocked  string
	apps         map[string]*adBlockAppStats
}

func (s *adBlockStats) record(name, pkg string, blocked bool) {
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
	s.lastQuery = name
	if blocked {
		s.blocked++
		s.todayBlocked++
		s.lastBlocked = name
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
		"lastQuery":    s.lastQuery,
		"lastBlocked":  s.lastBlocked,
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

// CountAdBlockRules reports how many rules apply at the DNS layer without
// touching the active rule set. Used by the UI to validate a downloaded list
// before it is combined with the others.
func (a *App) CountAdBlockRules(rules string) (int64, error) {
	return parseAdBlockRules(rules).count, nil
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
