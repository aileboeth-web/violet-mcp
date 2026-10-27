# 部署 TLS 中继并接入 Violet（中继 1.2.0 / 手机 Violet MCP 1.3.0）

```
Violet ──HTTP 127.0.0.1:8787/mcp──► violet-mcp-relay ◄══wss://VPS:8443/device（TLS，手机主动连出）══ 手机
```

- 手机主动连接 VPS，不需要手机有公网 IP，也不需要 VPN。
- 推荐把中继装在 **Violet 所在的机器**上：Violet 访问本机的 `127.0.0.1:8787`，这一段不出机器；只有手机这一段走公网，并且强制 TLS。
- 默认使用中继自动生成的自签证书，手机端填写证书指纹（certificate pinning），不需要域名。
- MCP 入口如果配置成非回环地址且没有开 TLS，中继会**拒绝启动**。

已经部署过旧版（`eve-mcp-relay` / 手机 `com.mcp.server.eve`）请看 [UPGRADE.md](UPGRADE.md)。全新部署按下面的命令依次执行，只有第 1 步需要改一处。发布文件（`violet-mcp-relay-1.2.0-linux-*`、`violet-mcp-1.3.0-release.apk`）在 GitHub Releases，或用 `tools/package_release.py` 生成到 `dist/v1.3.0/`。

## 1. 在 Windows 电脑上上传程序（PowerShell）

```powershell
$VPS = "root@你的VPS地址"   # ← 只改这一处，例如 root@203.0.113.10
scp dist\v1.3.0\violet-mcp-relay-1.2.0-linux-amd64 dist\v1.3.0\violet-mcp-relay-1.2.0-linux-arm64 relay\deploy\violet-mcp-relay.service "${VPS}:/root/"   # 在仓库目录里执行
ssh $VPS
```

## 2. 在 VPS 上安装（root）

```bash
# 确认端口没被占用（没有输出就是空闲）
ss -ltnp | grep -E ':8443 |:8787 '

mkdir -p /opt/violet-mcp-relay
ARCH=$( [ "$(uname -m)" = aarch64 ] && echo arm64 || echo amd64 )
install -m 0755 /root/violet-mcp-relay-1.2.0-linux-$ARCH /opt/violet-mcp-relay/violet-mcp-relay
install -m 0644 /root/violet-mcp-relay.service /etc/systemd/system/violet-mcp-relay.service
systemctl daemon-reload
systemctl enable --now violet-mcp-relay
journalctl -u violet-mcp-relay -n 20 --no-pager

# 确认 MCP 入口只监听本机（应显示 127.0.0.1:8787），设备入口监听 8443
ss -ltnp | grep -E ':8443 |:8787 '

# 放行手机连接端口（使用 ufw 时；云服务商的安全组也要放行 TCP 8443）
command -v ufw >/dev/null && ufw status | grep -q "Status: active" && ufw allow 8443/tcp
```

## 3. 取得手机需要填写的三项

```bash
echo "中继地址:   wss://$(curl -4s https://ifconfig.me):8443/device"
echo "设备密钥:   $(cat /var/lib/violet-mcp-relay/device.secret)"
echo "证书指纹:   $(/opt/violet-mcp-relay/violet-mcp-relay -data-dir /var/lib/violet-mcp-relay -print-pin)"
```

设备密钥只保存在 `/var/lib/violet-mcp-relay/device.secret`（权限 0600），任何日志里都不会出现。

## 4. 手机端

1. 安装 `dist/v1.3.0/violet-mcp-1.3.0-release.apk`，打开 App。
2. 设置 → 权限 → **使用情况访问权限** → 允许。“电池无限制”可选，建议开启。
3. 设置 → 远程访问：填入第 3 步的三项 → **保存并应用** → 开启“启用中继连接”。
4. 设置 → 后台运行：按需开启“开机自启”。部分品牌还需要在手机管家里允许自启动和后台运行。
5. 主页点 **启动**，确认“远程访问”显示“已连接”。
6. 打开“工具与权限”，选择要开放给 Violet 的工具（默认只开 5 个不敏感的）。
7. 打开“安全”，复制访问令牌，下一步要用。

## 5. 在 VPS 上验证

```bash
curl -s http://127.0.0.1:8787/health; echo      # deviceConnected 应为 true

TOKEN='粘贴访问令牌'                              # ← 只改这一处
curl -s -D - http://127.0.0.1:8787/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_screen_state","arguments":{}}}' | grep -E 'Server-Timing|screenOn'

# 不带令牌应返回 401
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8787/mcp -H 'Content-Type: application/json' -d '{}'
```

## 6. 接入 Violet

见 [VIOLET_INTEGRATION.md](VIOLET_INTEGRATION.md)。URL 为 `http://127.0.0.1:8787/mcp`，请求头为 `Authorization: Bearer <令牌>`。

### Violet 不在中继所在的机器上时

不能把 `-client-listen` 改成公网明文地址（中继会拒绝启动）。可选做法：

- **推荐**：把中继装到 Violet 所在的机器上。
- 给中继配正式证书（域名 + Let's Encrypt），让 MCP 入口也走 TLS：
  1. 修改 service 文件的 `ExecStart`，追加 `-client-listen :8444 -client-tls -tls-cert /etc/violet-mcp-relay/fullchain.pem -tls-key /etc/violet-mcp-relay/privkey.pem`。
  2. 把证书文件复制到 `/etc/violet-mcp-relay/`。服务以 DynamicUser 运行，读不到 `/etc/letsencrypt/live` 下 root 专属的私钥。
  3. Violet 使用 `https://你的域名:8444/mcp`。
  4. 使用正式证书时，手机端“证书指纹”可以留空；证书续期后中继会在一分钟内自动加载新证书。

## 7. 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `-device-listen` | `:8443` | 手机入口（始终 TLS） |
| `-client-listen` | `127.0.0.1:8787` | Violet 入口；非回环地址必须配 `-client-tls`，否则拒绝启动 |
| `-request-timeout` | `20s` | 等待手机响应的上限（1s–120s）。默认值小于 Python SDK 的 30 秒 HTTP 超时，Violet 能拿到明确原因 |
| `-offline-wait` | `5s` | 手机暂时离线（例如正在切换网络）时，请求最多等待多久（0–30s） |
| `-ping-interval` | `30s` | 中继向手机发心跳的间隔（10s–10m）。越短越快发现断线，熄屏时也越费手机电量 |
| `-read-timeout` | 3 × 心跳间隔 | 超过这个时间收不到手机任何数据就断开 |
| `-log-level` | `info` | `debug` / `info` / `warn`；任何级别都不记录令牌和请求内容 |
| `-print-pin` / `-pin-of 文件` | — | 打印当前证书 / 指定证书文件的公钥指纹 |

修改参数：编辑 `/etc/systemd/system/violet-mcp-relay.service` 的 `ExecStart` 行，然后执行 `systemctl daemon-reload && systemctl restart violet-mcp-relay`。

## 8. 更换证书（不中断、不盲信新证书）

手机最多可以同时保存 3 个证书指纹。换证书的顺序如下：

```bash
# 1) 在 VPS 上生成新证书（示例：新的自签证书），先不要替换正在使用的证书
cd /var/lib/violet-mcp-relay
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
  -subj "/CN=violet-mcp-relay" -keyout new.key -out new.crt
/opt/violet-mcp-relay/violet-mcp-relay -pin-of new.crt          # 记下新指纹
```

2. 在手机上：设置 → 证书指纹，在旧指纹下**另起一行**粘贴新指纹 → 保存并应用。此时新旧证书都能连接。
3. 在 VPS 上切换证书：

```bash
cd /var/lib/violet-mcp-relay
mv selfsigned.crt old.crt && mv selfsigned.key old.key
mv new.crt selfsigned.crt && mv new.key selfsigned.key
chown --reference=old.key selfsigned.crt selfsigned.key   # 服务以 DynamicUser 运行，新文件要交给同一个属主
chmod 600 selfsigned.key
systemctl restart violet-mcp-relay
journalctl -u violet-mcp-relay -n 5 --no-pager | grep "certificate pin"   # 应显示新指纹
```

4. 手机重新连上后，在设置中删掉旧指纹那一行。

如果手机提示“证书指纹不匹配”，而你**没有**更换过证书，不要更新指纹：这可能说明连接被拦截。
安全页会显示服务器出示的指纹，请先用 `-print-pin` 在 VPS 上核对。

## 9. 运维

```bash
journalctl -u violet-mcp-relay -f                 # 每个请求一行：状态码、total/rtt/phone/tool 耗时
curl -s http://127.0.0.1:8787/health; echo      # 设备是否在线、最近一次请求、最近一次断开原因
systemctl restart violet-mcp-relay                 # 重启后手机会自动重连

# 更换设备密钥（之后在手机上填新密钥）
systemctl stop violet-mcp-relay && rm /var/lib/violet-mcp-relay/device.secret && systemctl start violet-mcp-relay
cat /var/lib/violet-mcp-relay/device.secret

# 卸载
systemctl disable --now violet-mcp-relay && rm -rf /opt/violet-mcp-relay /etc/systemd/system/violet-mcp-relay.service /var/lib/private/violet-mcp-relay
```

## 安全要点

| 要求 | 实现 |
|---|---|
| 必须身份认证 | 手机→中继用设备密钥（只比较 SHA-256，常量时间）；Violet→中继→手机用 MCP Bearer 令牌：中继按手机上报的令牌哈希先拦截，手机再校验一次 |
| 未认证不暴露工具 | 中继没有任何工具或工具清单；未认证请求在中继就返回 401，不会转发到手机 |
| 不安全监听 | 非回环地址且未开 TLS 时拒绝启动；绑定后再按实际地址检查一次 |
| 日志不含令牌 | 只记录方法、路径、状态码和各阶段耗时；设备密钥只写入 0600 文件 |
| 断线重连 | 中继每 30 秒发一次心跳，90 秒收不到数据就断开；手机按 1→60 秒指数退避（±20% 抖动）重连；网络切换时立即重连；没有网络时暂停重试 |
| 超时可控 | 请求超时、离线等待、心跳、读超时都可以配置；请求超时后中继会探测连接，5 秒内没有回应就断开 |
| TLS | 手机只接受 `wss://`；自签证书靠指纹固定，正式证书按系统 CA 和主机名校验；不会自动接受未知证书 |
