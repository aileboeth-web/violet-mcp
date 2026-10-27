"""
发布产物 / 源码交接包泄密检查。

  python tests/check_release_artifacts.py dist                         # 发布产物（含历史版本）
  python tests/check_release_artifacts.py --handoff <交接包.zip>        # 源码交接包（额外检查目录结构）
  python tests/check_release_artifacts.py --handoff x.zip --report r.json

检查内容（递归进入 zip / jar / apk / aar / tar / tgz / gz 等嵌套归档）：
  1. 文件名：signing/、*.jks、*.keystore、*.p12、*.pfx、*.key、keystore.properties、rotation.properties、
     lineage*.bin、local.properties、device.secret、.env
  2. 内容特征：PEM 私钥块、JKS 魔数、PKCS#12 密钥库结构、带字面值的 storePassword / keyPassword 等口令行，
     以及常见云服务 / API 令牌格式（AWS、GitHub、OpenAI/Anthropic、Slack、Google、JWT、长 Bearer 令牌）
  3. 与本机真实密钥逐字节比对：signing/ 下每个文件的 SHA-256；signing/ 中所有 *Password 口令的字面值
  4. --handoff 模式额外禁止：.git/、.gradle/、.kotlin/、.idea/、任意 build/ 目录、dist/、虚拟环境、
     __pycache__、测试工作目录，以及 APK / AAB / ZIP（gradle-wrapper.jar 除外）
退出码：0 未发现问题；1 发现问题；2 用法错误。
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import sys
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FORBIDDEN_NAME = re.compile(
    r"(^|/)(signing/|[^/]*\.(jks|keystore|p12|pfx|key)$|keystore\.properties$|rotation\.properties$|"
    r"lineage[^/]*\.bin$|local\.properties$|device\.secret$|\.env$|selfsigned\.key$|keystore\.properties\.v\d)",
    re.IGNORECASE,
)
# 源码交接包中不应出现的目录和文件
HANDOFF_FORBIDDEN = re.compile(
    r"(^|/)(\.git/|\.gradle/|\.kotlin/|\.idea/|build/|dist/|\.venv/|venv/|__pycache__/|\.e2e[^/]*/|captures/|\.cxx/|"
    r"[^/]*\.(apk|aab|zip|tar|tgz|gz|class|dex|so|iml)$)",
    re.IGNORECASE,
)
HANDOFF_ALLOWED_BINARY = re.compile(r"(^|/)gradle/wrapper/gradle-wrapper\.jar$")

PEM_PRIVATE = re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----")
PASSWORD_LINE = re.compile(rb"(?:storePassword|keyPassword|StorePassword|KeyPassword)\s*=\s*([^\s\"'#]+)")
# 不算泄密的值：文档占位符、从配置文件/环境变量读取的代码
PLACEHOLDER = re.compile(rb"^(\.\.\.|<[^>]*>|CHANGE_ME|.*(signingProps|getProperty|getenv|System\.|\$\{).*)$")
TOKEN_PATTERNS = {
    "AWS 访问密钥": re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub 令牌": re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "OpenAI/Anthropic 风格密钥": re.compile(rb"\bsk-(?:ant-|proj-)?[A-Za-z0-9_\-]{32,}"),
    "Slack 令牌": re.compile(rb"\bxox[abprs]-[A-Za-z0-9\-]{10,}"),
    "Google API 密钥": re.compile(rb"\bAIza[0-9A-Za-z_\-]{35}\b"),
    "JWT": re.compile(rb"\beyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"),
    "长 Bearer 令牌": re.compile(rb"Bearer\s+[A-Za-z0-9_\-]{40,}"),
}
JKS_MAGIC = b"\xfe\xed\xfe\xed"
# PKCS#12：DER SEQUENCE，版本 INTEGER 3，随后是 pkcs7-data OID 1.2.840.113549.1.7.1
PKCS12_HINT = re.compile(rb"^\x30\x82..\x02\x01\x03\x30\x82..\x06\x09\x2a\x86\x48\x86\xf7\x0d\x01\x07\x01", re.S)
ZIP_EXT = (".zip", ".jar", ".apk", ".aar", ".aab")
TAR_EXT = (".tar", ".tgz", ".tar.gz")


def local_secrets() -> tuple[set[str], list[bytes]]:
    """本机真实密钥文件的哈希和口令（只在内存中比对，不输出）"""
    hashes: set[str] = set()
    passwords: list[bytes] = []
    signing = ROOT / "signing"
    if signing.is_dir():
        for f in signing.rglob("*"):
            if not f.is_file():
                continue
            hashes.add(hashlib.sha256(f.read_bytes()).hexdigest())
            if ".properties" in f.name:
                for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                    k, _, v = line.partition("=")
                    if k.strip().lower().endswith("password") and len(v.strip()) >= 6:
                        passwords.append(v.strip().encode())
    return hashes, passwords


class Checker:
    def __init__(self, handoff: bool) -> None:
        self.handoff = handoff
        self.hashes, self.passwords = local_secrets()
        self.problems: list[str] = []
        self.files = 0
        self.archives = 0

    def add(self, msg: str) -> None:
        self.problems.append(msg)

    def check_name(self, label: str, name: str, top_level: bool) -> None:
        if FORBIDDEN_NAME.search(name):
            self.add(f"{label}: 禁止打包的文件名")
        if self.handoff and top_level and HANDOFF_FORBIDDEN.search(name) and not HANDOFF_ALLOWED_BINARY.search(name):
            self.add(f"{label}: 交接包中不应包含（版本库 / 缓存 / 构建产物 / 归档）")

    def check_bytes(self, label: str, data: bytes) -> None:
        self.files += 1
        if hashlib.sha256(data).hexdigest() in self.hashes:
            self.add(f"{label}: 与本机 signing/ 中的文件完全相同")
        if PEM_PRIVATE.search(data):
            self.add(f"{label}: 含 PEM 私钥块")
        if data[:4] == JKS_MAGIC:
            self.add(f"{label}: JKS 密钥库")
        if PKCS12_HINT.match(data[:40]):
            self.add(f"{label}: 疑似 PKCS#12 密钥库")
        for m in PASSWORD_LINE.finditer(data):
            # 真实口令是长随机串；少于 6 个字符的“值”只可能是说明文字
            if len(m.group(1)) >= 6 and not PLACEHOLDER.match(m.group(1)):
                self.add(f"{label}: 含带字面值的口令配置行")
                break
        for pw in self.passwords:
            if pw in data:
                self.add(f"{label}: 含本机密钥库口令")
                break
        # 通用令牌格式只在文本文件里查：二进制（ELF / dex / class）的字符串表会把无关常量首尾相连，
        # 例如 Go 中继里的 "Bearer " 后面紧跟 "jsonrpcmessage..."，会造成误报。二进制仍做上面的真实密钥逐字节比对。
        if b"\x00" not in data[:8192]:
            for kind, pat in TOKEN_PATTERNS.items():
                if pat.search(data):
                    self.add(f"{label}: 疑似{kind}")

    def check_member(self, label: str, name: str, data: bytes, depth: int, top_level: bool) -> None:
        self.check_name(label, name, top_level)
        self.check_bytes(label, data)
        low = name.lower()
        if depth < 4:
            if low.endswith(ZIP_EXT):
                self.check_zip(label, data, depth + 1)
            elif low.endswith(TAR_EXT):
                self.check_tar(label, data, depth + 1)
            elif low.endswith(".gz"):
                try:
                    inner = gzip.decompress(data)
                    self.check_member(label + "!(gunzip)", name[:-3], inner, depth + 1, False)
                except OSError:
                    self.add(f"{label}: 无法解压的 gz")

    def check_zip(self, label: str, data: bytes, depth: int = 0) -> None:
        self.archives += 1
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            self.add(f"{label}: 不是有效的 zip")
            return
        for info in z.infolist():
            if info.is_dir():
                self.check_name(f"{label}!{info.filename}", info.filename, depth == 0)
                continue
            self.check_member(f"{label}!{info.filename}", info.filename, z.read(info), depth, depth == 0)

    def check_tar(self, label: str, data: bytes, depth: int) -> None:
        self.archives += 1
        try:
            t = tarfile.open(fileobj=io.BytesIO(data))
        except tarfile.TarError:
            self.add(f"{label}: 不是有效的 tar")
            return
        for m in t.getmembers():
            if m.isfile():
                f = t.extractfile(m)
                self.check_member(f"{label}!{m.name}", m.name, f.read() if f else b"", depth, False)

    def check_path(self, p: Path) -> None:
        data = p.read_bytes()
        if FORBIDDEN_NAME.search(p.as_posix()):
            self.add(f"{p}: 禁止发布的文件名")
        self.check_bytes(str(p), data)
        low = p.name.lower()
        if low.endswith(ZIP_EXT):
            self.check_zip(str(p), data)
        elif low.endswith(TAR_EXT):
            self.check_tar(str(p), data, 0)


def main() -> int:
    ap = argparse.ArgumentParser(description="发布产物 / 源码交接包泄密检查")
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--handoff", action="store_true", help="源码交接包模式：额外检查目录结构")
    ap.add_argument("--report", help="把结果写成 JSON 报告")
    a = ap.parse_args()
    targets: list[Path] = []
    for arg in a.paths:
        p = Path(arg)
        if p.is_dir():
            targets += sorted(f for f in p.rglob("*") if f.is_file())
        elif p.is_file():
            targets.append(p)
        else:
            print(f"不存在：{arg}")
            return 2
    c = Checker(a.handoff)
    print(f"本机参照：signing/ 下 {len(c.hashes)} 个文件的哈希，{len(c.passwords)} 个口令（只在内存中比对，不输出）")
    results = []
    for t in targets:
        before = len(c.problems)
        c.check_path(t)
        ok = len(c.problems) == before
        results.append({"path": str(t), "sha256": hashlib.sha256(t.read_bytes()).hexdigest(), "ok": ok,
                        "problems": c.problems[before:]})
        print(f"[{'PASS' if ok else 'FAIL'}] {t}")
    for msg in c.problems:
        print("  - " + msg)
    print(f"\n模式：{'源码交接包' if a.handoff else '发布产物'}；共检查 {len(targets)} 个产物、"
          f"{c.files} 个文件、{c.archives} 个归档（含嵌套），发现问题 {len(c.problems)} 个")
    if a.report:
        Path(a.report).write_bytes(json.dumps({
            "mode": "handoff" if a.handoff else "release",
            "referenceKeyFiles": len(c.hashes), "referencePasswords": len(c.passwords),
            "filesScanned": c.files, "archivesScanned": c.archives,
            "problemCount": len(c.problems), "targets": results,
        }, ensure_ascii=False, indent=2).encode("utf-8"))
    return 1 if c.problems else 0


if __name__ == "__main__":
    sys.exit(main())
