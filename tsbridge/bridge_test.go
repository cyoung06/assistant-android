package tsbridge

import (
	"os"
	"testing"
	"time"
)

// Starts like Android: no HOME in the environment, directories passed through SetEnv. Without an
// auth key the node only waits for a login, so nothing joins a tailnet.
func TestStartsWithoutHome(t *testing.T) {
	os.Unsetenv("HOME")
	os.Unsetenv("XDG_CACHE_HOME")
	SetInterfaces("lo 1 16384 up 127.0.0.1/8\nen0 2 1500 up 192.168.1.10/24")
	dir := t.TempDir()
	SetEnv("HOME", dir)
	SetEnv("TS_LOGS_DIR", dir)
	b, err := Start(dir+"/state", "assistant-test", "", "https://example.com", "token")
	if err != nil {
		t.Fatal(err)
	}
	defer b.Stop()
	deadline := time.Now().Add(20 * time.Second)
	for b.State() != "NeedsLogin" && time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
	}
	if s := b.State(); s != "NeedsLogin" {
		t.Fatalf("state %q", s)
	}
	if b.Port() == 0 {
		t.Fatal("no proxy port")
	}
}
