// Package tsbridge puts the app on the tailnet by itself (Tailscale's tsnet) and serves a local
// proxy to the app's server through it, so the app works whether the Tailscale VPN is on or not.
// Built with gomobile into an Android library.
package tsbridge

import (
	"context"
	"crypto/subtle"
	"errors"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// Bridge is a running tailnet node with its local proxy.
type Bridge struct {
	srv   *tsnet.Server
	ln    net.Listener
	token string
}

var (
	ifaceMu sync.Mutex
	ifaces  []netmon.Interface
)

func init() {
	// Android doesn't let apps list network interfaces over netlink; the app passes them in.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifaceMu.Lock()
		defer ifaceMu.Unlock()
		if ifaces == nil {
			return nil, errors.New("interfaces not set yet")
		}
		return ifaces, nil
	})
}

// SetInterfaces takes the device's interfaces, one per line: "name index mtu up addr/prefix ...".
func SetInterfaces(desc string) {
	var list []netmon.Interface
	for _, line := range strings.Split(strings.TrimSpace(desc), "\n") {
		f := strings.Fields(line)
		if len(f) < 4 {
			continue
		}
		index, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		flags := net.FlagMulticast
		if f[3] == "up" {
			flags |= net.FlagUp | net.FlagRunning
		}
		if f[0] == "lo" {
			flags |= net.FlagLoopback
		}
		iface := netmon.Interface{Interface: &net.Interface{Index: index, MTU: mtu, Name: f[0], Flags: flags}}
		for _, a := range f[4:] {
			if p, err := netip.ParsePrefix(a); err == nil {
				iface.AltAddrs = append(iface.AltAddrs, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
			}
		}
		list = append(list, iface)
	}
	ifaceMu.Lock()
	ifaces = list
	ifaceMu.Unlock()
}

// Start joins the tailnet as hostname (state kept in dir) and serves a proxy to target on a local
// port. Requests must carry the header X-Bridge-Token: token, so other apps can't use it.
// authKey may be empty; then the node waits for an interactive login (see AuthURL).
func Start(dir, hostname, authKey, target, token string) (*Bridge, error) {
	// No log uploads to Tailscale from this embedded node.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	u, err := url.Parse(target)
	if err != nil {
		return nil, err
	}
	srv := &tsnet.Server{Dir: dir, Hostname: hostname, AuthKey: authKey, Logf: func(string, ...any) {}}
	if err := srv.Start(); err != nil {
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		srv.Close()
		return nil, err
	}
	proxy := &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.SetURL(u)
			r.Out.Host = u.Host
			r.Out.Header.Del("X-Bridge-Token")
		},
		Transport: srv.HTTPClient().Transport,
	}
	b := &Bridge{srv: srv, ln: ln, token: token}
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Bridge-Token")), []byte(token)) != 1 {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		proxy.ServeHTTP(w, r)
	}))
	return b, nil
}

// Port is the local proxy's port on 127.0.0.1.
func (b *Bridge) Port() int { return b.ln.Addr().(*net.TCPAddr).Port }

// State is the node's state: "Running", "NeedsLogin", "Starting", … or "error: …".
func (b *Bridge) State() string {
	st, err := b.status()
	if err != nil {
		return "error: " + err.Error()
	}
	return st.BackendState
}

// AuthURL is the login link while the node waits to be signed in, else "".
func (b *Bridge) AuthURL() string {
	st, err := b.status()
	if err != nil {
		return ""
	}
	return st.AuthURL
}

// Name is the node's name on the tailnet once running, e.g. "assistant-android.example.ts.net".
func (b *Bridge) Name() string {
	st, err := b.status()
	if err != nil || st.Self == nil {
		return ""
	}
	return strings.TrimSuffix(st.Self.DNSName, ".")
}

// Login starts an interactive login (when no auth key was given) so AuthURL gets a link.
func (b *Bridge) Login() error {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// Stop leaves the tailnet (the node stays registered) and closes the proxy.
func (b *Bridge) Stop() {
	b.ln.Close()
	b.srv.Close()
}

type status struct {
	BackendState string
	AuthURL      string
	Self         *struct{ DNSName string }
}

func (b *Bridge) status() (*status, error) {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return nil, err
	}
	out := &status{BackendState: st.BackendState, AuthURL: st.AuthURL}
	if st.Self != nil {
		out.Self = &struct{ DNSName string }{st.Self.DNSName}
	}
	return out, nil
}

