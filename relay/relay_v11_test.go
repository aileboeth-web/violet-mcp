package main

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 1.1.0 新增：监听安全、超时参数、离线等待、心跳、探测、分阶段计时、并发。

func TestRefusesInsecureClientListen(t *testing.T) {
	for _, addr := range []string{"0.0.0.0:8787", ":8787", "192.168.1.10:8787", "[::]:8787", "example.com:8787"} {
		if err := validateListen(addr, false); err == nil {
			t.Errorf("%s without TLS must be refused", addr)
		}
		if err := validateListen(addr, true); err != nil {
			t.Errorf("%s with TLS should be allowed: %v", addr, err)
		}
	}
	for _, addr := range []string{"127.0.0.1:8787", "[::1]:8787", "localhost:8787"} {
		if err := validateListen(addr, false); err != nil {
			t.Errorf("%s should be allowed: %v", addr, err)
		}
	}
	if err := validateListen("nonsense", false); err == nil {
		t.Error("malformed address must be refused")
	}
	ln, err := listenClient("127.0.0.1:0", false)
	if err != nil {
		t.Fatal(err)
	}
	_ = ln.Close()
	if ln, err := listenClient("0.0.0.0:0", false); err == nil {
		_ = ln.Close()
		t.Error("listenClient must refuse a non-loopback bound address without TLS")
	}
}

func TestValidateTimers(t *testing.T) {
	ok := Config{RequestTimeout: 20 * time.Second, OfflineWait: 5 * time.Second, PingInterval: 30 * time.Second}
	if err := validateTimers(ok); err != nil {
		t.Fatal(err)
	}
	bad := []Config{
		{RequestTimeout: 0, PingInterval: 30 * time.Second},
		{RequestTimeout: 10 * time.Minute, PingInterval: 30 * time.Second},
		{RequestTimeout: 20 * time.Second, PingInterval: time.Second},
		{RequestTimeout: 20 * time.Second, PingInterval: 30 * time.Second, OfflineWait: time.Hour},
		{RequestTimeout: 20 * time.Second, PingInterval: 30 * time.Second, ReadTimeout: 10 * time.Second},
	}
	for i, c := range bad {
		if validateTimers(c) == nil {
			t.Errorf("config %d should be refused", i)
		}
	}
}

func TestOfflineWaitForReconnectingDevice(t *testing.T) {
	e := newEnvCfg(t, Config{RequestTimeout: time.Second, OfflineWait: 3 * time.Second})
	go func() {
		time.Sleep(500 * time.Millisecond)
		_, _, _ = e.connectDevice(t, testSecret, testToken, echoMCP)
	}()
	start := time.Now()
	r := e.post(t, "/mcp", testToken, `{}`, nil)
	if r.StatusCode != 200 {
		t.Fatalf("expected request to wait for the device, got %d", r.StatusCode)
	}
	if time.Since(start) < 400*time.Millisecond {
		t.Fatal("request did not wait")
	}
}

func TestOfflineWaitIsBounded(t *testing.T) {
	e := newEnvCfg(t, Config{RequestTimeout: time.Second, OfflineWait: 300 * time.Millisecond})
	start := time.Now()
	r := e.post(t, "/mcp", testToken, rpcReq, nil)
	body, _ := io.ReadAll(r.Body)
	if r.StatusCode != http.StatusOK || time.Since(start) > 2*time.Second {
		t.Fatalf("got %d after %s", r.StatusCode, time.Since(start))
	}
	if !strings.Contains(string(body), "Device offline") || !strings.Contains(string(body), `"code":-32010`) {
		t.Fatalf("missing reason: %s", body)
	}
}

func TestServerTimingHeader(t *testing.T) {
	e := newEnv(t, time.Second)
	_, _, err := e.connectDevice(t, testSecret, testToken, func(f frame) frame {
		r := echoMCP(f)
		r.Headers["Server-Timing"] = "phone;dur=3.1, tool;dur=2.0"
		return r
	})
	if err != nil {
		t.Fatal(err)
	}
	r := e.post(t, "/mcp", testToken, `{}`, nil)
	st := r.Header.Get("Server-Timing")
	for _, want := range []string{"relay;dur=", "wait_device;dur=", "rtt;dur=", "phone;dur=3.1", "tool;dur=2.0"} {
		if !strings.Contains(st, want) {
			t.Errorf("Server-Timing %q missing %q", st, want)
		}
	}
	if !strings.Contains(e.logs.String(), "phone=3.1ms tool=2.0ms") {
		t.Errorf("timing not logged:\n%s", e.logs.String())
	}
	if p, tl := parsePhoneTiming("phone;dur=1\r\nX-Evil: y, tool;dur=abc"); p != "" || tl != "" {
		t.Errorf("non-numeric timing must be dropped, got %q %q", p, tl)
	}
}

func TestHeartbeatAndDeadDeviceDetection(t *testing.T) {
	e := newEnvCfg(t, Config{RequestTimeout: time.Second, PingInterval: 100 * time.Millisecond, ReadTimeout: 400 * time.Millisecond})
	d, _, err := e.connectDevice(t, testSecret, testToken, echoMCP)
	if err != nil {
		t.Fatal(err)
	}
	if d.ack.PingIntervalMs != 100 {
		t.Fatalf("hello_ack should advertise ping interval, got %d", d.ack.PingIntervalMs)
	}
	time.Sleep(time.Second)
	if !e.relay.DeviceConnected() || d.pings.Load() < 5 {
		t.Fatalf("device answering pongs must stay connected (pings=%d)", d.pings.Load())
	}
	d.noPong.Store(true)
	waitFor(t, func() bool { return !e.relay.DeviceConnected() })
}

func TestTimeoutTriggersProbe(t *testing.T) {
	e := newEnvCfg(t, Config{RequestTimeout: 200 * time.Millisecond, PingInterval: time.Hour})
	block := make(chan struct{})
	t.Cleanup(func() { close(block) })
	d, _, err := e.connectDevice(t, testSecret, testToken, func(f frame) frame { <-block; return echoMCP(f) })
	if err != nil {
		t.Fatal(err)
	}
	d.noPong.Store(true)
	r := e.post(t, "/mcp", testToken, rpcReq, nil)
	if r.StatusCode != http.StatusOK {
		t.Fatalf("expected JSON-RPC error with HTTP 200, got %d", r.StatusCode)
	}
	body, _ := io.ReadAll(r.Body)
	if !strings.Contains(string(body), "did not respond within") {
		t.Fatalf("missing timeout reason: %s", body)
	}
	// 探测 5 秒内没有 pong：中继主动断开，让手机重连
	deadline := time.Now().Add(8 * time.Second)
	for e.relay.DeviceConnected() && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if e.relay.DeviceConnected() {
		t.Fatal("unresponsive device should be disconnected after probe")
	}
}

func TestConcurrentRequestsAreNotMixedUp(t *testing.T) {
	e := newEnv(t, 5*time.Second)
	_, _, err := e.connectDevice(t, testSecret, testToken, func(f frame) frame {
		return frame{Status: 200, Headers: map[string]string{"Content-Type": "application/json"}, Body: f.Body}
	})
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	var bad atomic.Int32
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			want := fmt.Sprintf(`{"n":%d}`, i)
			r := e.post(t, "/mcp", testToken, want, nil)
			got, _ := io.ReadAll(r.Body)
			if r.StatusCode != 200 || string(got) != want {
				bad.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if bad.Load() != 0 {
		t.Fatalf("%d responses were wrong or mixed up", bad.Load())
	}
}

func TestHealthReportsLastRequestWithoutSecrets(t *testing.T) {
	e := newEnv(t, time.Second)
	if _, _, err := e.connectDevice(t, testSecret, testToken, echoMCP); err != nil {
		t.Fatal(err)
	}
	e.post(t, "/mcp", testToken, `{}`, nil)
	resp, err := http.Get(e.client.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), `"lastRequest"`) || !strings.Contains(string(body), `"status":200`) {
		t.Fatalf("health: %s", body)
	}
	if strings.Contains(string(body), testToken) || strings.Contains(string(body), testSecret) {
		t.Fatal("health leaks secrets")
	}
}

func TestTransientErrorShapes(t *testing.T) {
	e := newEnv(t, time.Second)
	// 通知：202，无响应体
	r := e.post(t, "/mcp", testToken, `{"jsonrpc":"2.0","method":"notifications/initialized"}`, nil)
	if r.StatusCode != http.StatusAccepted {
		t.Fatalf("notification while offline: %d", r.StatusCode)
	}
	// 批量：每个带 id 的请求都有对应错误
	r = e.post(t, "/mcp", testToken, `[{"jsonrpc":"2.0","id":"a","method":"ping"},{"jsonrpc":"2.0","method":"x"},{"jsonrpc":"2.0","id":2,"method":"ping"}]`, nil)
	body, _ := io.ReadAll(r.Body)
	if r.StatusCode != 200 || strings.Count(string(body), `"code":-32010`) != 2 || !strings.Contains(string(body), `"id":"a"`) {
		t.Fatalf("batch while offline: %d %s", r.StatusCode, body)
	}
	// 无法解析：退回 HTTP 503
	if r := e.post(t, "/mcp", testToken, `not json`, nil); r.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("garbage while offline: %d", r.StatusCode)
	}
	// 鉴权失败仍是 HTTP 401（SDK 应当中止）
	if _, _, err := e.connectDevice(t, testSecret, testToken, echoMCP); err != nil {
		t.Fatal(err)
	}
	if r := e.post(t, "/mcp", "wrong-token-0123456789", rpcReq, nil); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong token: %d", r.StatusCode)
	}
}
