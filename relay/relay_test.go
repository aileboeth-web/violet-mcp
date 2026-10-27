package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const (
	testSecret = "device-secret-0123456789"
	testToken  = "mcp-token-abcdefghijklmnop"
)

type env struct {
	relay  *Relay
	device *httptest.Server
	client *httptest.Server
	logs   *safeBuffer
}

func newEnv(t *testing.T, timeout time.Duration) *env {
	t.Helper()
	return newEnvCfg(t, Config{RequestTimeout: timeout})
}

// safeBuffer 供并发写日志
type safeBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *safeBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *safeBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func newEnvCfg(t *testing.T, cfg Config) *env {
	t.Helper()
	logs := &safeBuffer{}
	cfg.DeviceSecret = testSecret
	cfg.Logger = log.New(logs, "", 0)
	cfg.LogLevel = LevelDebug // 最详细的日志级别下也不能泄露凭据
	r := NewRelay(cfg)
	e := &env{relay: r, logs: logs}
	e.device = httptest.NewTLSServer(r.DeviceHandler())
	e.client = httptest.NewServer(r.ClientHandler())
	t.Cleanup(func() {
		e.device.Close()
		e.client.Close()
		for _, s := range []string{testSecret, testToken} {
			if strings.Contains(logs.String(), s) {
				t.Errorf("secret leaked into relay log:\n%s", logs.String())
			}
		}
	})
	return e
}

// fakeDevice 模拟手机：发 hello，然后对每个 http_request 调用 handler 生成响应。
type fakeDevice struct {
	ws       *websocket.Conn
	requests atomic.Int32
	lastReq  atomic.Value
	pings    atomic.Int32
	noPong   atomic.Bool
	ack      frame
}

func (e *env) connectDevice(t *testing.T, secret, token string, handler func(f frame) frame) (*fakeDevice, *http.Response, error) {
	t.Helper()
	dialer := websocket.Dialer{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	url := "wss" + strings.TrimPrefix(e.device.URL, "https") + "/device"
	h := http.Header{}
	h.Set("Authorization", "Bearer "+secret)
	ws, resp, err := dialer.Dial(url, h)
	if err != nil {
		return nil, resp, err
	}
	sum := sha256.Sum256([]byte(token))
	if err := ws.WriteJSON(frame{Type: "hello", Protocol: 1, DeviceName: "test", MCPTokenSHA256: hex.EncodeToString(sum[:])}); err != nil {
		t.Fatal(err)
	}
	var ack frame
	if err := ws.ReadJSON(&ack); err != nil || ack.Type != "hello_ack" {
		t.Fatalf("no hello_ack: %v %+v", err, ack)
	}
	d := &fakeDevice{ws: ws, ack: ack}
	go func() {
		for {
			var f frame
			if err := ws.ReadJSON(&f); err != nil {
				return
			}
			if f.Type == "ping" {
				d.pings.Add(1)
				if !d.noPong.Load() {
					_ = ws.WriteJSON(frame{Type: "pong", T: f.T})
				}
				continue
			}
			if f.Type == "http_request" {
				d.requests.Add(1)
				d.lastReq.Store(f)
				out := handler(f)
				out.Type = "http_response"
				out.ID = f.ID
				_ = ws.WriteJSON(out)
			}
		}
	}()
	waitFor(t, func() bool { return e.relay.DeviceConnected() })
	return d, resp, nil
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("condition not met")
}

func echoMCP(f frame) frame {
	return frame{Status: 200, Headers: map[string]string{"Content-Type": "application/json", "Set-Cookie": "x=y"},
		Body: `{"jsonrpc":"2.0","id":1,"result":{}}`}
}

func (e *env) post(t *testing.T, path, token, body string, extra map[string]string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("POST", e.client.URL+path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func TestDeviceWithWrongSecretRejected(t *testing.T) {
	e := newEnv(t, time.Second)
	_, resp, err := e.connectDevice(t, "wrong-secret-0123456789", testToken, echoMCP)
	if err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got err=%v resp=%v", err, resp)
	}
	if e.relay.DeviceConnected() {
		t.Fatal("device must not be registered")
	}
}

func TestOfflineAndUnauthenticated(t *testing.T) {
	e := newEnv(t, time.Second)
	if r := e.post(t, "/mcp", "", `{}`, nil); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("no token: %d", r.StatusCode)
	}
	if code := rpcErrorCode(t, e.post(t, "/mcp", testToken, rpcReq, nil)); code != codeDeviceOffline {
		t.Fatalf("offline: %d", code)
	}
	d, _, err := e.connectDevice(t, testSecret, testToken, echoMCP)
	if err != nil {
		t.Fatal(err)
	}
	if r := e.post(t, "/mcp", "wrong-token-0123456789", `{}`, nil); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong token: %d", r.StatusCode)
	}
	if r := e.post(t, "/mcp", testToken, `{}`, map[string]string{"Origin": "http://evil"}); r.StatusCode != http.StatusForbidden {
		t.Fatalf("origin: %d", r.StatusCode)
	}
	if r := e.post(t, "/tools", testToken, `{}`, nil); r.StatusCode != http.StatusNotFound {
		t.Fatalf("/tools: %d", r.StatusCode)
	}
	if d.requests.Load() != 0 {
		t.Fatal("unauthenticated requests must not reach the device")
	}
}

func TestForwarding(t *testing.T) {
	e := newEnv(t, time.Second)
	d, _, err := e.connectDevice(t, testSecret, testToken, echoMCP)
	if err != nil {
		t.Fatal(err)
	}
	r := e.post(t, "/mcp", testToken, `{"jsonrpc":"2.0","id":1,"method":"ping"}`,
		map[string]string{"Cookie": "session=1", "X-Forwarded-For": "1.2.3.4", "Mcp-Protocol-Version": "2025-06-18"})
	body, _ := io.ReadAll(r.Body)
	if r.StatusCode != 200 || string(body) != `{"jsonrpc":"2.0","id":1,"result":{}}` {
		t.Fatalf("unexpected response %d %s", r.StatusCode, body)
	}
	if r.Header.Get("Set-Cookie") != "" {
		t.Fatal("non-allowlisted response header forwarded")
	}
	f := d.lastReq.Load().(frame)
	if f.Headers["authorization"] != "Bearer "+testToken || f.Headers["mcp-protocol-version"] != "2025-06-18" {
		t.Fatalf("missing forwarded headers: %+v", f.Headers)
	}
	if _, ok := f.Headers["cookie"]; ok {
		t.Fatal("cookie must not be forwarded")
	}
	if _, ok := f.Headers["x-forwarded-for"]; ok {
		t.Fatal("x-forwarded-for must not be forwarded")
	}
	if f.Path != "/mcp" || f.Method != "POST" || f.Body != `{"jsonrpc":"2.0","id":1,"method":"ping"}` {
		t.Fatalf("bad frame %+v", f)
	}
}

func TestAcceptedHasNoBody(t *testing.T) {
	e := newEnv(t, time.Second)
	_, _, err := e.connectDevice(t, testSecret, testToken, func(frame) frame { return frame{Status: 202} })
	if err != nil {
		t.Fatal(err)
	}
	r := e.post(t, "/mcp", testToken, `{"jsonrpc":"2.0","method":"notifications/initialized"}`, nil)
	body, _ := io.ReadAll(r.Body)
	if r.StatusCode != 202 || len(body) != 0 {
		t.Fatalf("got %d %q", r.StatusCode, body)
	}
}

func TestTimeoutAndDisconnect(t *testing.T) {
	e := newEnv(t, 300*time.Millisecond)
	block := make(chan struct{})
	d, _, err := e.connectDevice(t, testSecret, testToken, func(f frame) frame { <-block; return echoMCP(f) })
	if err != nil {
		t.Fatal(err)
	}
	if code := rpcErrorCode(t, e.post(t, "/mcp", testToken, rpcReq, nil)); code != codeDeviceTimeout {
		t.Fatalf("timeout: %d", code)
	}
	close(block)
	_ = d.ws.Close()
	waitFor(t, func() bool { return !e.relay.DeviceConnected() })
	if code := rpcErrorCode(t, e.post(t, "/mcp", testToken, rpcReq, nil)); code != codeDeviceOffline {
		t.Fatalf("after disconnect: %d", code)
	}
}

func TestNewConnectionReplacesOld(t *testing.T) {
	e := newEnv(t, time.Second)
	old, _, err := e.connectDevice(t, testSecret, "old-token-0123456789abcd", echoMCP)
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = e.connectDevice(t, testSecret, testToken, echoMCP)
	if err != nil {
		t.Fatal(err)
	}
	// 旧连接被关闭，令牌哈希以新连接为准
	_ = old.ws.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := old.ws.ReadMessage(); err == nil {
		t.Fatal("old connection should be closed")
	}
	if r := e.post(t, "/mcp", "old-token-0123456789abcd", `{}`, nil); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("old token: %d", r.StatusCode)
	}
	if r := e.post(t, "/mcp", testToken, `{}`, nil); r.StatusCode != 200 {
		t.Fatalf("new token: %d", r.StatusCode)
	}
}

func TestBodyLimit(t *testing.T) {
	e := newEnv(t, time.Second)
	if _, _, err := e.connectDevice(t, testSecret, testToken, echoMCP); err != nil {
		t.Fatal(err)
	}
	big := strings.Repeat("x", (1<<20)+1)
	if r := e.post(t, "/mcp", testToken, big, nil); r.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("body limit: %d", r.StatusCode)
	}
}

func TestHealthIsMinimal(t *testing.T) {
	e := newEnv(t, time.Second)
	resp, err := http.Get(e.client.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.Contains(string(body), `"deviceConnected":false`) {
		t.Fatalf("%d %s", resp.StatusCode, body)
	}
}

func TestBrowserCannotOpenDeviceSocket(t *testing.T) {
	e := newEnv(t, time.Second)
	dialer := websocket.Dialer{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	h := http.Header{}
	h.Set("Authorization", "Bearer "+testSecret)
	h.Set("Origin", "https://evil.example")
	_, resp, err := dialer.Dial("wss"+strings.TrimPrefix(e.device.URL, "https")+"/device", h)
	if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403, got %v %v", err, resp)
	}
}

// rpcReq 是一条带 id 的 JSON-RPC 请求
const rpcReq = `{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"battery"}}`

// rpcErrorCode 断言响应是 HTTP 200 的 JSON-RPC 错误（带原 id），返回错误码
func rpcErrorCode(t *testing.T, r *http.Response) int {
	t.Helper()
	if r.StatusCode != http.StatusOK {
		t.Fatalf("expected HTTP 200 with JSON-RPC error, got %d", r.StatusCode)
	}
	var out struct {
		ID    int `json:"id"`
		Error struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.NewDecoder(r.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if out.ID != 7 {
		t.Fatalf("error must carry the request id, got %d", out.ID)
	}
	return out.Error.Code
}
