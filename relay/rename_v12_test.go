package main

import (
	"path/filepath"
	"strings"
	"testing"
)

// 1.2.0 renamed the relay (eve-mcp-relay -> violet-mcp-relay). A service configured with the old environment variable
// keeps its device secret; the new name wins when both are set.
func TestDeviceSecretEnvironmentNewAndOldName(t *testing.T) {
	path := filepath.Join(t.TempDir(), "device.secret")

	t.Setenv("VIOLET_RELAY_DEVICE_SECRET", "")
	t.Setenv("EVE_RELAY_DEVICE_SECRET", "old-name-secret-0123456789")
	s, created, err := loadOrCreateSecret(path)
	if err != nil || created || s != "old-name-secret-0123456789" {
		t.Fatalf("old name: got %q created=%v err=%v", s, created, err)
	}

	t.Setenv("VIOLET_RELAY_DEVICE_SECRET", "new-name-secret-0123456789")
	if s, _, err = loadOrCreateSecret(path); err != nil || s != "new-name-secret-0123456789" {
		t.Fatalf("new name should win: got %q err=%v", s, err)
	}

	t.Setenv("VIOLET_RELAY_DEVICE_SECRET", "")
	t.Setenv("EVE_RELAY_DEVICE_SECRET", "short")
	if _, _, err = loadOrCreateSecret(path); err == nil || !strings.Contains(err.Error(), "EVE_RELAY_DEVICE_SECRET") {
		t.Fatalf("a too short old-name secret must be refused and named: err=%v", err)
	}
}
