package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// 帧格式与 Android 端 RelayClient 一致（JSON 文本帧）。
type frame struct {
	Type           string            `json:"type"`
	ID             string            `json:"id,omitempty"`
	Protocol       int               `json:"protocol,omitempty"`
	DeviceName     string            `json:"deviceName,omitempty"`
	AppVersion     string            `json:"appVersion,omitempty"`
	MCPTokenSHA256 string            `json:"mcpTokenSha256,omitempty"`
	Features       []string          `json:"features,omitempty"`
	PingIntervalMs int64             `json:"pingIntervalMs,omitempty"`
	T              int64             `json:"t,omitempty"`
	Method         string            `json:"method,omitempty"`
	Path           string            `json:"path,omitempty"`
	Status         int               `json:"status,omitempty"`
	Headers        map[string]string `json:"headers,omitempty"`
	Body           string            `json:"body"`
	Message        string            `json:"message,omitempty"`
}

const (
	protocolVersion = 1
	writeTimeout    = 15 * time.Second
	helloTimeout    = 10 * time.Second
	probeTimeout    = 5 * time.Second
	maxFrameBytes   = 4 << 20
	closeReplaced   = 4000
)

// 转发给手机的请求头（白名单）。其余头（Cookie、X-Forwarded-* 等）一律不转发。
var forwardRequestHeaders = []string{
	"Authorization", "Content-Type", "Accept", "Mcp-Protocol-Version", "Mcp-Session-Id", "Last-Event-Id",
}

// 回传给 Violet 的响应头（白名单）。Server-Timing 由中继重新组装。
var forwardResponseHeaders = []string{"Content-Type", "Allow", "Www-Authenticate", "Mcp-Session-Id"}

// 日志级别
const (
	LevelDebug = iota
	LevelInfo
	LevelWarn
)

// ParseLevel 解析 -log-level。
func ParseLevel(s string) (int, error) {
	switch strings.ToLower(s) {
	case "debug":
		return LevelDebug, nil
	case "info", "":
		return LevelInfo, nil
	case "warn", "warning":
		return LevelWarn, nil
	}
	return 0, fmt.Errorf("unknown log level %q (debug|info|warn)", s)
}

// Config 中继运行参数。
type Config struct {
	DeviceSecret string
	// 等待手机响应的上限。默认 20 秒，小于 Python MCP SDK 默认的 30 秒 HTTP 超时，Violet 能拿到明确的 504 原因
	RequestTimeout time.Duration
	// 手机暂时离线（例如正在切换网络重连）时，请求最多等待多久再返回 503
	OfflineWait time.Duration
	// 中继向手机发送应用层心跳的间隔；ReadTimeout 内收不到手机任何数据即断开
	PingInterval time.Duration
	ReadTimeout  time.Duration
	MaxBodyBytes int64
	Logger       *log.Logger
	LogLevel     int
}

// Relay 维护唯一的在线设备，并把 Violet 的 MCP 请求转发给它。
type Relay struct {
	cfg              Config
	deviceSecretHash [32]byte
	upgrader         websocket.Upgrader
	log              *log.Logger

	mu       sync.Mutex
	device   *deviceConn
	changed  chan struct{} // 设备变化时关闭并重建，用于等待设备上线
	lastReq  requestStat
	lastDrop string
}

type requestStat struct {
	At      time.Time
	Status  int
	TotalMs float64
}

type deviceConn struct {
	ws          *websocket.Conn
	writeMu     sync.Mutex
	tokenHash   []byte
	name        string
	version     string
	connectedAt time.Time
	remote      string

	mu       sync.Mutex
	pending  map[string]chan *frame
	lastPong time.Time
	probing  bool

	closed chan struct{}
	once   sync.Once
	reason string
}

func NewRelay(cfg Config) *Relay {
	if cfg.RequestTimeout <= 0 {
		cfg.RequestTimeout = 20 * time.Second
	}
	if cfg.OfflineWait < 0 {
		cfg.OfflineWait = 0
	}
	if cfg.PingInterval <= 0 {
		cfg.PingInterval = 30 * time.Second
	}
	if cfg.ReadTimeout <= cfg.PingInterval {
		cfg.ReadTimeout = 3 * cfg.PingInterval
	}
	if cfg.MaxBodyBytes <= 0 {
		cfg.MaxBodyBytes = 1 << 20
	}
	if cfg.Logger == nil {
		cfg.Logger = log.Default()
	}
	r := &Relay{cfg: cfg, log: cfg.Logger, deviceSecretHash: sha256.Sum256([]byte(cfg.DeviceSecret)),
		changed: make(chan struct{})}
	r.upgrader = websocket.Upgrader{
		ReadBufferSize:  32 << 10,
		WriteBufferSize: 32 << 10,
		// 手机端不带 Origin；带 Origin 的是浏览器，拒绝
		CheckOrigin: func(req *http.Request) bool { return req.Header.Get("Origin") == "" },
	}
	return r
}

func (r *Relay) debugf(format string, a ...any) {
	if r.cfg.LogLevel <= LevelDebug {
		r.log.Printf("DEBUG "+format, a...)
	}
}

func (r *Relay) infof(format string, a ...any) {
	if r.cfg.LogLevel <= LevelInfo {
		r.log.Printf(format, a...)
	}
}

func (r *Relay) warnf(format string, a ...any) { r.log.Printf("WARN "+format, a...) }

func bearer(h string) (string, bool) {
	if len(h) < 7 || !strings.EqualFold(h[:7], "Bearer ") {
		return "", false
	}
	v := strings.TrimSpace(h[7:])
	return v, v != ""
}

func randomID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func ms(d time.Duration) float64 { return float64(d.Microseconds()) / 1000 }

// ==================== 设备侧 ====================

// DeviceHandler 处理手机的 WebSocket 连接（必须挂在 TLS 监听上）。
func (r *Relay) DeviceHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		secret, ok := bearer(req.Header.Get("Authorization"))
		sum := sha256.Sum256([]byte(secret))
		if !ok || len(r.cfg.DeviceSecret) < 16 || subtle.ConstantTimeCompare(sum[:], r.deviceSecretHash[:]) != 1 {
			r.warnf("device auth failed from %s", req.RemoteAddr)
			time.Sleep(500 * time.Millisecond) // 减缓暴力尝试
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		ws, err := r.upgrader.Upgrade(w, req, nil)
		if err != nil {
			r.warnf("device upgrade failed from %s: %v", req.RemoteAddr, err)
			return
		}
		ws.SetReadLimit(maxFrameBytes)
		r.serveDevice(ws, req.RemoteAddr)
	})
}

func (r *Relay) setDevice(d *deviceConn, ifCurrent *deviceConn) (old *deviceConn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if ifCurrent != nil && r.device != ifCurrent {
		return nil
	}
	old = r.device
	r.device = d
	close(r.changed)
	r.changed = make(chan struct{})
	return old
}

func (r *Relay) serveDevice(ws *websocket.Conn, remote string) {
	_ = ws.SetReadDeadline(time.Now().Add(helloTimeout))
	var hello frame
	if err := ws.ReadJSON(&hello); err != nil || hello.Type != "hello" {
		r.warnf("device %s: invalid hello", remote)
		_ = ws.Close()
		return
	}
	tokenHash, err := hex.DecodeString(hello.MCPTokenSHA256)
	if err != nil || len(tokenHash) != sha256.Size {
		r.warnf("device %s: invalid token hash in hello", remote)
		_ = ws.WriteJSON(frame{Type: "error", Message: "invalid hello"})
		_ = ws.Close()
		return
	}
	d := &deviceConn{
		ws: ws, tokenHash: tokenHash, name: hello.DeviceName, version: hello.AppVersion,
		connectedAt: time.Now(), remote: remote, lastPong: time.Now(),
		pending: map[string]chan *frame{}, closed: make(chan struct{}),
	}
	if err := d.write(frame{Type: "hello_ack", Protocol: protocolVersion, PingIntervalMs: r.cfg.PingInterval.Milliseconds()}); err != nil {
		_ = ws.Close()
		return
	}

	if old := r.setDevice(d, nil); old != nil {
		r.infof("device replaced by new connection from %s", remote)
		old.shutdown("replaced", websocket.FormatCloseMessage(closeReplaced, "replaced"))
	}
	r.infof("device connected: version=%q from %s", d.version, remote)

	// 旧版手机（1.1.0）会发 WebSocket ping，回 pong 的同时也算作活动
	ws.SetPingHandler(func(data string) error {
		_ = ws.SetReadDeadline(time.Now().Add(r.cfg.ReadTimeout))
		d.writeMu.Lock()
		defer d.writeMu.Unlock()
		return ws.WriteControl(websocket.PongMessage, []byte(data), time.Now().Add(writeTimeout))
	})
	go r.pingLoop(d)

	reason := "read error"
	for {
		_ = ws.SetReadDeadline(time.Now().Add(r.cfg.ReadTimeout))
		var f frame
		if err := ws.ReadJSON(&f); err != nil {
			var ne interface{ Timeout() bool }
			if errors.As(err, &ne) && ne.Timeout() {
				reason = fmt.Sprintf("no data from device for %s", r.cfg.ReadTimeout)
			} else if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				reason = "closed by device"
			}
			break
		}
		switch f.Type {
		case "http_response":
			d.deliver(&f)
		case "pong":
			d.mu.Lock()
			d.lastPong = time.Now()
			d.probing = false
			d.mu.Unlock()
		}
	}

	r.setDevice(nil, d)
	d.shutdown(reason, nil)
	r.mu.Lock()
	r.lastDrop = time.Now().Format(time.RFC3339) + " " + d.reason
	r.mu.Unlock()
	r.infof("device disconnected: %s (%s, connected %s)", remote, d.reason, time.Since(d.connectedAt).Round(time.Second))
}

// pingLoop 定期发送应用层心跳；收到探测请求时立即发一次，探测超时就断开连接。
func (r *Relay) pingLoop(d *deviceConn) {
	t := time.NewTicker(r.cfg.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-d.closed:
			return
		case <-t.C:
			if err := d.write(frame{Type: "ping", T: time.Now().UnixMilli()}); err != nil {
				d.shutdown("ping write failed", nil)
				return
			}
		}
	}
}

// probe 在请求超时后检查连接是否还活着：probeTimeout 内收不到 pong 就断开，让手机重连。
func (r *Relay) probe(d *deviceConn) {
	d.mu.Lock()
	if d.probing {
		d.mu.Unlock()
		return
	}
	d.probing = true
	sent := time.Now()
	d.mu.Unlock()
	if err := d.write(frame{Type: "ping", T: sent.UnixMilli()}); err != nil {
		d.shutdown("probe write failed", nil)
		return
	}
	time.AfterFunc(probeTimeout, func() {
		d.mu.Lock()
		dead := d.probing && d.lastPong.Before(sent)
		d.mu.Unlock()
		if dead {
			r.warnf("device %s did not answer probe within %s, closing connection", d.remote, probeTimeout)
			d.shutdown("probe timeout", nil)
		}
	})
}

func (d *deviceConn) write(f frame) error {
	d.writeMu.Lock()
	defer d.writeMu.Unlock()
	_ = d.ws.SetWriteDeadline(time.Now().Add(writeTimeout))
	return d.ws.WriteJSON(f)
}

func (d *deviceConn) deliver(f *frame) {
	d.mu.Lock()
	ch := d.pending[f.ID]
	delete(d.pending, f.ID)
	d.mu.Unlock()
	if ch != nil {
		ch <- f
	}
}

func (d *deviceConn) shutdown(reason string, closeMsg []byte) {
	d.once.Do(func() {
		d.reason = reason
		close(d.closed)
		if closeMsg != nil {
			d.writeMu.Lock()
			_ = d.ws.WriteControl(websocket.CloseMessage, closeMsg, time.Now().Add(time.Second))
			d.writeMu.Unlock()
		}
		_ = d.ws.Close()
	})
}

var (
	errDeviceGone    = errors.New("device disconnected")
	errDeviceTimeout = errors.New("device response timeout")
)

func (d *deviceConn) roundTrip(f frame, timeout time.Duration) (*frame, time.Time, error) {
	ch := make(chan *frame, 1)
	d.mu.Lock()
	d.pending[f.ID] = ch
	d.mu.Unlock()
	defer func() {
		d.mu.Lock()
		delete(d.pending, f.ID)
		d.mu.Unlock()
	}()
	if err := d.write(f); err != nil {
		return nil, time.Now(), errDeviceGone
	}
	sentAt := time.Now()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case resp := <-ch:
		return resp, sentAt, nil
	case <-d.closed:
		return nil, sentAt, errDeviceGone
	case <-timer.C:
		return nil, sentAt, errDeviceTimeout
	}
}

func (r *Relay) currentDevice() (*deviceConn, chan struct{}) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.device, r.changed
}

// waitDevice 在设备暂时离线时最多等待 OfflineWait。
func (r *Relay) waitDevice() *deviceConn {
	deadline := time.Now().Add(r.cfg.OfflineWait)
	for {
		d, changed := r.currentDevice()
		if d != nil {
			return d
		}
		left := time.Until(deadline)
		if left <= 0 {
			return nil
		}
		select {
		case <-changed:
		case <-time.After(left):
		}
	}
}

// DeviceConnected 供 /health 使用。
func (r *Relay) DeviceConnected() bool {
	d, _ := r.currentDevice()
	return d != nil
}

// ==================== Violet（MCP 客户端）侧 ====================

func writeJSONError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"jsonrpc": "2.0", "id": nil, "error": map[string]any{"code": -32000, "message": msg},
	})
}

// 设备侧的暂时性错误（离线、超时、处理中断开）。
const (
	codeDeviceOffline = -32010
	codeDeviceTimeout = -32011
	codeDeviceGone    = -32012
)

// writeTransientError 把设备侧的暂时性错误作为 JSON-RPC 错误（HTTP 200，带上原请求 id）返回。
//
// Python MCP SDK 1.x 收到 4xx/5xx 时会在传输层抛异常并结束整个客户端会话；
// 用 JSON-RPC 错误只让这一次调用失败（McpError），会话可以继续使用。
// 请求体无法解析成 JSON-RPC 时退回 HTTP 状态码。返回实际写出的 HTTP 状态码。
func writeTransientError(w http.ResponseWriter, body []byte, fallbackStatus, code int, msg string) int {
	errObj := func(id json.RawMessage) map[string]any {
		return map[string]any{"jsonrpc": "2.0", "id": id,
			"error": map[string]any{"code": code, "message": msg, "data": map[string]any{"retryable": true}}}
	}
	type msgShape struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
	}
	hasID := func(m msgShape) bool { return len(m.ID) > 0 && string(m.ID) != "null" && m.Method != "" }

	var out any
	trimmed := strings.TrimSpace(string(body))
	switch {
	case strings.HasPrefix(trimmed, "{"):
		var m msgShape
		if json.Unmarshal(body, &m) != nil {
			writeJSONError(w, fallbackStatus, msg)
			return fallbackStatus
		}
		if !hasID(m) {
			w.WriteHeader(http.StatusAccepted) // 通知：无需响应
			return http.StatusAccepted
		}
		out = errObj(m.ID)
	case strings.HasPrefix(trimmed, "["):
		var arr []msgShape
		if json.Unmarshal(body, &arr) != nil {
			writeJSONError(w, fallbackStatus, msg)
			return fallbackStatus
		}
		var list []any
		for _, m := range arr {
			if hasID(m) {
				list = append(list, errObj(m.ID))
			}
		}
		if len(list) == 0 {
			w.WriteHeader(http.StatusAccepted)
			return http.StatusAccepted
		}
		out = list
	default:
		writeJSONError(w, fallbackStatus, msg)
		return fallbackStatus
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(out)
	return http.StatusOK
}

// timing 记录一个请求在中继里的各阶段（time.Time 自带单调时钟读数）。
type timing struct {
	start, authed, device, sent, received time.Time
	phone, tool                           string // 手机回传的 Server-Timing 数值
}

func (t *timing) serverTiming() string {
	parts := []string{fmt.Sprintf("relay;dur=%.1f", ms(t.received.Sub(t.start)))}
	if !t.device.IsZero() {
		parts = append(parts, fmt.Sprintf("wait_device;dur=%.1f", ms(t.device.Sub(t.authed))))
	}
	if !t.sent.IsZero() {
		parts = append(parts, fmt.Sprintf("rtt;dur=%.1f", ms(t.received.Sub(t.sent))))
	}
	if t.phone != "" {
		parts = append(parts, "phone;dur="+t.phone)
	}
	if t.tool != "" {
		parts = append(parts, "tool;dur="+t.tool)
	}
	return strings.Join(parts, ", ")
}

// parsePhoneTiming 从手机返回的 "phone;dur=3.1, tool;dur=2.0" 中取出数值（只接受数字，防止注入响应头）。
func parsePhoneTiming(h string) (phone, tool string) {
	for _, part := range strings.Split(h, ",") {
		kv := strings.SplitN(strings.TrimSpace(part), ";dur=", 2)
		if len(kv) != 2 {
			continue
		}
		if _, err := strconv.ParseFloat(kv[1], 64); err != nil {
			continue
		}
		switch kv[0] {
		case "phone":
			phone = kv[1]
		case "tool":
			tool = kv[1]
		}
	}
	return
}

// ClientHandler 是给 Violet 的 MCP 端点：/mcp（Streamable HTTP）和 /health。
func (r *Relay) ClientHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		t := &timing{start: time.Now()}
		status, detail := r.serveClient(w, req, t)
		total := ms(time.Since(t.start))
		if req.URL.Path != "/health" {
			r.mu.Lock()
			r.lastReq = requestStat{At: time.Now(), Status: status, TotalMs: total}
			r.mu.Unlock()
		}
		// 只记录方法、路径、状态、耗时；不记录请求头、令牌和请求体
		line := fmt.Sprintf("client %s %s -> %d total=%.1fms", req.Method, req.URL.Path, status, total)
		if !t.sent.IsZero() {
			line += fmt.Sprintf(" wait_device=%.1fms rtt=%.1fms phone=%sms tool=%sms",
				ms(t.device.Sub(t.authed)), ms(t.received.Sub(t.sent)), orDash(t.phone), orDash(t.tool))
		}
		if detail != "" {
			line += " (" + detail + ")"
		}
		if status >= 500 {
			r.warnf("%s", line)
		} else {
			r.infof("%s", line)
		}
	})
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func (r *Relay) health(w http.ResponseWriter) {
	d, _ := r.currentDevice()
	r.mu.Lock()
	last, drop := r.lastReq, r.lastDrop
	r.mu.Unlock()
	out := map[string]any{"ok": true, "deviceConnected": d != nil}
	if d != nil {
		out["deviceConnectedSeconds"] = int(time.Since(d.connectedAt).Seconds())
		out["deviceAppVersion"] = d.version
	}
	if drop != "" {
		out["lastDeviceDisconnect"] = drop
	}
	if !last.At.IsZero() {
		out["lastRequest"] = map[string]any{
			"secondsAgo": int(time.Since(last.At).Seconds()), "status": last.Status, "totalMs": last.TotalMs,
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(out)
}

func (r *Relay) serveClient(w http.ResponseWriter, req *http.Request, t *timing) (int, string) {
	if req.Header.Get("Origin") != "" {
		writeJSONError(w, http.StatusForbidden, "Forbidden origin")
		return http.StatusForbidden, ""
	}
	path := strings.TrimRight(req.URL.Path, "/")
	if path == "/health" {
		r.health(w)
		return http.StatusOK, ""
	}
	if path != "/mcp" && path != "/status" {
		writeJSONError(w, http.StatusNotFound, "Not Found")
		return http.StatusNotFound, ""
	}
	token, ok := bearer(req.Header.Get("Authorization"))
	if !ok {
		w.Header().Set("WWW-Authenticate", `Bearer realm="mcp"`)
		writeJSONError(w, http.StatusUnauthorized, "Unauthorized")
		return http.StatusUnauthorized, ""
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, req.Body, r.cfg.MaxBodyBytes))
	if err != nil {
		writeJSONError(w, http.StatusRequestEntityTooLarge, "Request body too large")
		return http.StatusRequestEntityTooLarge, ""
	}
	t.authed = time.Now()
	d := r.waitDevice()
	if d == nil {
		msg := fmt.Sprintf("Device offline (waited %s)", r.cfg.OfflineWait)
		return writeTransientError(w, body, http.StatusServiceUnavailable, codeDeviceOffline, msg), "device offline"
	}
	t.device = time.Now()
	// 中继先用手机上报的令牌哈希拦截未认证请求，手机端还会再校验一次
	sum := sha256.Sum256([]byte(token))
	if subtle.ConstantTimeCompare(sum[:], d.tokenHash) != 1 {
		time.Sleep(200 * time.Millisecond)
		w.Header().Set("WWW-Authenticate", `Bearer realm="mcp"`)
		writeJSONError(w, http.StatusUnauthorized, "Unauthorized")
		return http.StatusUnauthorized, ""
	}
	headers := map[string]string{}
	for _, h := range forwardRequestHeaders {
		if v := req.Header.Get(h); v != "" {
			headers[strings.ToLower(h)] = v
		}
	}
	id := randomID()
	r.debugf("forward %s to device (%d bytes)", id[:8], len(body))
	resp, sentAt, err := d.roundTrip(frame{
		Type: "http_request", ID: id, Method: req.Method, Path: path, Headers: headers, Body: string(body),
	}, r.cfg.RequestTimeout)
	t.sent = sentAt
	t.received = time.Now()
	w.Header().Set("Server-Timing", t.serverTiming())
	if err != nil {
		if errors.Is(err, errDeviceTimeout) {
			go r.probe(d)
			msg := fmt.Sprintf("Device did not respond within %s (connection is being checked)", r.cfg.RequestTimeout)
			return writeTransientError(w, body, http.StatusGatewayTimeout, codeDeviceTimeout, msg), "device timeout"
		}
		return writeTransientError(w, body, http.StatusBadGateway, codeDeviceGone,
			"Device disconnected while handling the request"), "device disconnected"
	}
	for k, v := range resp.Headers {
		if strings.EqualFold(k, "Server-Timing") {
			t.phone, t.tool = parsePhoneTiming(v)
		}
	}
	w.Header().Set("Server-Timing", t.serverTiming())
	for _, h := range forwardResponseHeaders {
		for k, v := range resp.Headers {
			if strings.EqualFold(k, h) {
				w.Header().Set(h, v)
			}
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	st := resp.Status
	if st < 100 || st > 599 {
		st = http.StatusBadGateway
	}
	w.WriteHeader(st)
	if st != http.StatusAccepted && st != http.StatusNoContent && req.Method != http.MethodHead {
		_, _ = io.WriteString(w, resp.Body)
	}
	return st, ""
}
