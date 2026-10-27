// violet-mcp-relay：Violet ↔ Android 只读 MCP Server 的 TLS 中继。
//
// 手机主动通过 wss:// 连接本中继（设备入口），Violet 通过普通 Streamable HTTP 访问本中继的 /mcp（客户端入口）。
// 中继本身不提供任何工具，只在设备在线且 Bearer 令牌正确时转发请求。
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

var version = "1.2.0"

func main() {
	deviceListen := flag.String("device-listen", ":8443", "手机连接入口（TLS，wss://）监听地址")
	devicePath := flag.String("device-path", "/device", "手机连接的 WebSocket 路径")
	clientListen := flag.String("client-listen", "127.0.0.1:8787", "Violet 访问的 MCP 入口监听地址（默认只监听本机）")
	clientTLS := flag.Bool("client-tls", false, "MCP 入口也使用 TLS（Violet 不在同一台机器时开启）")
	certFile := flag.String("tls-cert", "", "TLS 证书（PEM，如 Let's Encrypt fullchain.pem）；留空则使用自签证书")
	keyFile := flag.String("tls-key", "", "TLS 私钥（PEM）")
	dataDir := flag.String("data-dir", "/var/lib/violet-mcp-relay", "保存自签证书和设备密钥的目录")
	hosts := flag.String("self-signed-hosts", "", "自签证书包含的域名/IP，逗号分隔（证书固定模式下仅作标识）")
	timeout := flag.Duration("request-timeout", 20*time.Second, "等待手机响应的超时（1s–120s）")
	offlineWait := flag.Duration("offline-wait", 5*time.Second, "手机暂时离线时请求最多等待多久再返回 503（0–30s）")
	pingInterval := flag.Duration("ping-interval", 30*time.Second, "向手机发送心跳的间隔（10s–10m）；越短越快发现断线，但更耗手机电量")
	readTimeout := flag.Duration("read-timeout", 0, "超过该时间收不到手机任何数据即断开（默认 3 倍心跳间隔）")
	logLevel := flag.String("log-level", "info", "日志级别：debug | info | warn（任何级别都不记录令牌和请求内容）")
	printPin := flag.Bool("print-pin", false, "只打印当前证书的公钥指纹后退出")
	certPinOf := flag.String("pin-of", "", "打印指定证书文件（PEM）的公钥指纹后退出，用于更换证书前预先在手机上添加新指纹")
	flag.Parse()

	logger := log.New(os.Stdout, "", log.LstdFlags)

	if *certPinOf != "" {
		pin, err := pinOfFile(*certPinOf)
		if err != nil {
			logger.Fatalf("pin-of: %v", err)
		}
		fmt.Println(pin)
		return
	}
	level, err := ParseLevel(*logLevel)
	if err != nil {
		logger.Fatalf("%v", err)
	}
	cfg := Config{RequestTimeout: *timeout, OfflineWait: *offlineWait, PingInterval: *pingInterval,
		ReadTimeout: *readTimeout, Logger: logger, LogLevel: level}
	// 不安全或不合理的配置直接拒绝启动，而不是只打印警告
	if err := validateListen(*clientListen, *clientTLS); err != nil {
		logger.Fatalf("refusing to start: %v", err)
	}
	if err := validateTimers(cfg); err != nil {
		logger.Fatalf("refusing to start: %v", err)
	}

	if err := os.MkdirAll(*dataDir, 0o700); err != nil {
		logger.Fatalf("create data dir: %v", err)
	}
	certs, err := newCertSource(*certFile, *keyFile, *dataDir, *hosts, logger)
	if err != nil {
		logger.Fatalf("tls: %v", err)
	}
	if *printPin {
		fmt.Println(certs.pin())
		return
	}

	secret, generated, err := loadOrCreateSecret(filepath.Join(*dataDir, "device.secret"))
	if err != nil {
		logger.Fatalf("device secret: %v", err)
	}

	cfg.DeviceSecret = secret
	relay := NewRelay(cfg)
	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: certs.get}

	deviceMux := http.NewServeMux()
	deviceMux.Handle(*devicePath, relay.DeviceHandler())
	deviceSrv := &http.Server{Addr: *deviceListen, Handler: deviceMux, TLSConfig: tlsCfg,
		ReadHeaderTimeout: 10 * time.Second, ErrorLog: log.New(os.Stderr, "device-http: ", log.LstdFlags)}

	clientSrv := &http.Server{Addr: *clientListen, Handler: relay.ClientHandler(),
		ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second,
		WriteTimeout: *timeout + *offlineWait + 10*time.Second, IdleTimeout: 120 * time.Second}
	if *clientTLS {
		clientSrv.TLSConfig = tlsCfg
	}

	logger.Printf("violet-mcp-relay %s", version)
	logger.Printf("device endpoint: wss://<this-host>%s%s (TLS)", portOf(*deviceListen), *devicePath)
	scheme := "http"
	if *clientTLS {
		scheme = "https"
	}
	logger.Printf("MCP endpoint for Violet: %s://%s/mcp", scheme, *clientListen)
	logger.Printf("certificate pin (SPKI SHA-256): %s", certs.pin())
	if generated {
		// 不把密钥写进日志（systemd 会把 stdout 持久化到 journald），只提示文件位置
		logger.Printf("NEW device secret generated and saved to %s (mode 0600); read it with: cat %s",
			filepath.Join(*dataDir, "device.secret"), filepath.Join(*dataDir, "device.secret"))
	} else {
		logger.Printf("device secret loaded from %s", filepath.Join(*dataDir, "device.secret"))
	}
	logger.Printf("timers: request-timeout=%s offline-wait=%s ping-interval=%s read-timeout=%s",
		relay.cfg.RequestTimeout, relay.cfg.OfflineWait, relay.cfg.PingInterval, relay.cfg.ReadTimeout)

	clientLn, err := listenClient(*clientListen, *clientTLS)
	if err != nil {
		logger.Fatalf("refusing to start: %v", err)
	}
	errCh := make(chan error, 2)
	go func() { errCh <- deviceSrv.ListenAndServeTLS("", "") }()
	go func() {
		if *clientTLS {
			errCh <- clientSrv.ServeTLS(clientLn, "", "")
		} else {
			errCh <- clientSrv.Serve(clientLn)
		}
	}()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	select {
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Fatalf("listen: %v", err)
		}
	case s := <-sig:
		logger.Printf("received %s, shutting down", s)
	}
	_ = deviceSrv.Close()
	_ = clientSrv.Close()
}

// validateListen：MCP 入口携带 Bearer 令牌，只允许“回环地址”或“启用 TLS”两种情况。
func validateListen(addr string, tlsOn bool) error {
	if _, _, err := net.SplitHostPort(addr); err != nil {
		return fmt.Errorf("invalid -client-listen %q: %v", addr, err)
	}
	if !tlsOn && !isLoopback(addr) {
		return fmt.Errorf("-client-listen %s is not a loopback address; tokens would travel in plaintext. "+
			"Use 127.0.0.1 / [::1], or add -client-tls with a certificate", addr)
	}
	return nil
}

// listenClient 绑定 MCP 入口，并按实际绑定到的地址再检查一次（防止 localhost 被解析到非回环地址）。
func listenClient(addr string, tlsOn bool) (net.Listener, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	if tcp, ok := ln.Addr().(*net.TCPAddr); !tlsOn && (!ok || !tcp.IP.IsLoopback()) {
		_ = ln.Close()
		return nil, fmt.Errorf("-client-listen %s resolved to non-loopback %s without TLS", addr, ln.Addr())
	}
	return ln, nil
}

func validateTimers(c Config) error {
	switch {
	case c.RequestTimeout < time.Second || c.RequestTimeout > 120*time.Second:
		return fmt.Errorf("-request-timeout must be within 1s..120s")
	case c.OfflineWait < 0 || c.OfflineWait > 30*time.Second:
		return fmt.Errorf("-offline-wait must be within 0..30s")
	case c.PingInterval < 10*time.Second || c.PingInterval > 10*time.Minute:
		return fmt.Errorf("-ping-interval must be within 10s..10m")
	case c.ReadTimeout != 0 && c.ReadTimeout <= c.PingInterval:
		return fmt.Errorf("-read-timeout must be longer than -ping-interval")
	}
	return nil
}

func pinOfFile(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	block, _ := pem.Decode(b)
	if block == nil || block.Type != "CERTIFICATE" {
		return "", errors.New("not a PEM certificate")
	}
	c, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return "", err
	}
	return spkiPin(c), nil
}

func portOf(addr string) string {
	_, port, err := net.SplitHostPort(addr)
	if err != nil || port == "443" {
		return ""
	}
	return ":" + port
}

func isLoopback(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func loadOrCreateSecret(path string) (string, bool, error) {
	// EVE_RELAY_DEVICE_SECRET: the name before 1.2.0 (relays deployed for "Project Eve"), still read so an existing
	// service configuration keeps working after the upgrade
	for _, name := range []string{"VIOLET_RELAY_DEVICE_SECRET", "EVE_RELAY_DEVICE_SECRET"} {
		if v := strings.TrimSpace(os.Getenv(name)); v != "" {
			if len(v) < 16 {
				return "", false, fmt.Errorf("%s must be at least 16 characters", name)
			}
			return v, false, nil
		}
	}
	if b, err := os.ReadFile(path); err == nil {
		s := strings.TrimSpace(string(b))
		if len(s) < 16 {
			return "", false, fmt.Errorf("%s: secret must be at least 16 characters", path)
		}
		return s, false, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", false, err
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", false, err
	}
	s := base64.RawURLEncoding.EncodeToString(buf)
	if err := os.WriteFile(path, []byte(s+"\n"), 0o600); err != nil {
		return "", false, err
	}
	return s, true, nil
}

// ==================== 证书 ====================

// certSource 提供 TLS 证书：外部证书文件（每分钟检查一次更新，适配 certbot 续期）或自签证书。
type certSource struct {
	certFile, keyFile string
	logger            *log.Logger

	mu        sync.Mutex
	cert      *tls.Certificate
	modTime   time.Time
	checkedAt time.Time
}

func newCertSource(certFile, keyFile, dataDir, hosts string, logger *log.Logger) (*certSource, error) {
	if (certFile == "") != (keyFile == "") {
		return nil, errors.New("-tls-cert and -tls-key must be given together")
	}
	if certFile == "" {
		certFile = filepath.Join(dataDir, "selfsigned.crt")
		keyFile = filepath.Join(dataDir, "selfsigned.key")
		if _, err := os.Stat(certFile); errors.Is(err, os.ErrNotExist) {
			if err := generateSelfSigned(certFile, keyFile, hosts); err != nil {
				return nil, err
			}
			logger.Printf("generated self-signed certificate %s", certFile)
		}
	}
	c := &certSource{certFile: certFile, keyFile: keyFile, logger: logger}
	if err := c.reload(); err != nil {
		return nil, err
	}
	return c, nil
}

func (c *certSource) reload() error {
	st, err := os.Stat(c.certFile)
	if err != nil {
		return err
	}
	cert, err := tls.LoadX509KeyPair(c.certFile, c.keyFile)
	if err != nil {
		return err
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return err
	}
	cert.Leaf = leaf
	c.cert = &cert
	c.modTime = st.ModTime()
	return nil
}

func (c *certSource) get(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if time.Since(c.checkedAt) > time.Minute {
		c.checkedAt = time.Now()
		if st, err := os.Stat(c.certFile); err == nil && st.ModTime().After(c.modTime) {
			if err := c.reload(); err != nil {
				c.logger.Printf("certificate reload failed (keeping old one): %v", err)
			} else {
				c.logger.Printf("certificate reloaded; pin is now %s", spkiPin(c.cert.Leaf))
			}
		}
	}
	return c.cert, nil
}

func (c *certSource) pin() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return spkiPin(c.cert.Leaf)
}

// spkiPin 与 Android 端 RelayClient.spkiPin 格式一致：sha256/<base64(SHA-256(SubjectPublicKeyInfo))>
func spkiPin(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return "sha256/" + base64.StdEncoding.EncodeToString(sum[:])
}

func generateSelfSigned(certFile, keyFile, hosts string) error {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "violet-mcp-relay"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	for _, h := range strings.Split(hosts, ",") {
		h = strings.TrimSpace(h)
		if h == "" {
			continue
		}
		if ip := net.ParseIP(h); ip != nil {
			tmpl.IPAddresses = append(tmpl.IPAddresses, ip)
		} else {
			tmpl.DNSNames = append(tmpl.DNSNames, h)
		}
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	keyDer, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDer}), 0o600); err != nil {
		return err
	}
	return os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644)
}
