"""
组装发布目录 dist/v<版本>/，并在发布前做泄密检查。这是唯一推荐的打包方式。

  python tools/package_release.py --version 1.3.0 --relay-version 1.2.0

步骤：
  1. 目标目录已存在则拒绝（不覆盖旧版本产物）。
  2. 复制已签名的 release APK（app/build/outputs/apk/release/violet-mcp-<版本>-release.apk）；
     apksigner verify 不通过（例如忘了运行 tools/sign_release.py）就拒绝。
  3. 编译 Linux amd64 / arm64 中继（需要 go 在 PATH 中）。
  4. 源码包 = tools/make_handoff.py 生成的交接包（git 文件 + 黑名单过滤 + 文件清单），生成后立即扫描 ZIP 本身。
  5. 写 SHA256SUMS.txt（LF 换行，可直接 sha256sum -c）。
  6. 对整个输出目录运行发布产物检查（递归进入 APK / ZIP / JAR），结果写入 SCAN_REPORT-release.json；
     交接包的结构检查结果写入 SCAN_REPORT-handoff.json。
     任何一项发现问题，就删除整个输出目录并以非零退出。
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = Path(os.environ.get("ANDROID_BUILD_TOOLS", "D:/android-sdk-public-build/build-tools/35.0.0"))


def run(cmd: list[str], **kw) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, encoding="utf-8", **kw).stdout


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="App 版本，例如 1.3.0")
    ap.add_argument("--relay-version", required=True, help="中继版本，例如 1.2.0")
    a = ap.parse_args()

    out = ROOT / "dist" / f"v{a.version}"
    if out.exists():
        print(f"拒绝：{out} 已存在（不覆盖旧版本产物）")
        return 1
    apk = ROOT / f"app/build/outputs/apk/release/violet-mcp-{a.version}-release.apk"
    if not apk.is_file():
        print(f"找不到 release APK：{apk}（先运行 gradlew :app:assembleRelease，再运行 tools/sign_release.py）")
        return 1
    apksigner = BUILD_TOOLS / ("apksigner.bat" if os.name == "nt" else "apksigner")
    if subprocess.run([str(apksigner), "verify", str(apk)], capture_output=True).returncode != 0:
        print(f"拒绝：{apk.name} 未签名或签名无效（密钥已轮换时请先运行 tools/sign_release.py）")
        return 1

    spec = importlib.util.spec_from_file_location("make_handoff", ROOT / "tools/make_handoff.py")
    handoff = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(handoff)

    out.mkdir(parents=True)
    try:
        shutil.copy2(apk, out / apk.name)

        env = dict(os.environ, CGO_ENABLED="0", GOOS="linux")
        for arch in ("amd64", "arm64"):
            run(["go", "build", "-trimpath", "-ldflags=-s -w", "-o",
                 str(out / f"violet-mcp-relay-{a.relay_version}-linux-{arch}"), "."],
                cwd=ROOT / "relay", env=dict(env, GOARCH=arch))

        src = out / f"violet-mcp-{a.version}-source-handoff.zip"
        if not handoff.build(a.version, src, out / "SCAN_REPORT-handoff.json"):
            raise RuntimeError("源码交接包未通过")

        sums = []
        for p in sorted(out.iterdir()):
            if p.name.startswith("SCAN_REPORT"):
                continue
            sums.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()} *{p.name}")
        # 固定用 LF：Windows 上 write_text 会写 CRLF，sha256sum -c 无法校验
        (out / "SHA256SUMS.txt").write_bytes(("\n".join(sums) + "\n").encode("utf-8"))

        rel = out.relative_to(ROOT).as_posix()
        check = subprocess.run([sys.executable, "-I", "-X", "utf8", str(ROOT / "tests/check_release_artifacts.py"),
                                rel, "--report", f"{rel}/SCAN_REPORT-release.json"], cwd=ROOT)
        if check.returncode != 0:
            raise RuntimeError("泄密检查未通过")
    except Exception as e:  # noqa: BLE001
        shutil.rmtree(out, ignore_errors=True)
        print(f"打包失败，已删除 {out}：{e}")
        return 1
    print(f"完成：{out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
