"""
生成安全的完整源码交接包。**不要直接压缩工作目录**：工作目录里有签名私钥（signing/）、.git/、
Gradle 缓存和构建产物，见 docs/HANDOFF.md。

  python tools/make_handoff.py --version 1.3.0

步骤：
  1. 输出 dist/handoff/violet-mcp-<版本>-source-handoff.zip；文件已存在则拒绝（不覆盖）。
  2. 候选文件 = git 已跟踪的文件 + 未被 .gitignore 排除的新文件（git ls-files --cached --others --exclude-standard）。
     因此 signing/、local.properties、.gradle/、build/、dist/ 等被忽略的内容根本不会成为候选。
  3. 再用两份黑名单过滤一遍（与 tests/check_release_artifacts.py 相同的规则），被排除的文件逐个打印。
  4. 写入 HANDOFF_MANIFEST.txt（每个文件的 SHA-256）。
  5. 对**生成的 ZIP 本身**运行 tests/check_release_artifacts.py 的交接包模式（递归检查嵌套归档），
     并写出 JSON 扫描报告；发现任何问题就删除 ZIP，以非零退出。
tools/package_release.py 也调用这里的 build()，发布目录里的源码包就是同一种交接包。
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_checker():
    spec = importlib.util.spec_from_file_location("checker", ROOT / "tests/check_release_artifacts.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def collect(chk) -> tuple[list[str], list[str]]:
    """候选文件 = git 跟踪的 + 未被 .gitignore 排除的新文件，再按两份黑名单过滤。返回（打包文件，被排除的文件）"""
    listed = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                            cwd=ROOT, capture_output=True, check=True).stdout.decode("utf-8").split(chr(0))
    files, excluded = [], []
    for f in sorted(set(x for x in listed if x)):
        if not (ROOT / f).is_file():
            continue  # 已暂存删除但工作区不存在的文件
        if chk.FORBIDDEN_NAME.search(f) or (chk.HANDOFF_FORBIDDEN.search(f) and not chk.HANDOFF_ALLOWED_BINARY.search(f)):
            excluded.append(f)
        else:
            files.append(f)
    return files, excluded


def build(version: str, out: Path, report: Path) -> bool:
    """写交接包并扫描 ZIP 本身；扫描不通过（或出错）时删除 ZIP，返回 False。out 已存在时拒绝。"""
    chk = load_checker()
    if out.exists():
        print(f"拒绝：{out} 已存在（不覆盖）")
        return False
    files, excluded = collect(chk)
    for f in excluded:
        print(f"排除：{f}")
    prefix = f"violet-mcp-{version}/"
    manifest = []
    try:
        with zipfile.ZipFile(out, "x", zipfile.ZIP_DEFLATED) as z:
            for f in files:
                data = (ROOT / f).read_bytes()
                manifest.append(f"{hashlib.sha256(data).hexdigest()}  {f}")
                z.writestr(prefix + f, data)
            z.writestr(prefix + "HANDOFF_MANIFEST.txt",
                       f"# Violet MCP {version} 源码交接包文件清单（SHA-256）\n" + "\n".join(manifest) + "\n")
        print(f"写入 {len(files)} 个文件 + 清单 → {out.name}")

        c = chk.Checker(handoff=True)
        c.check_path(out)
        report.write_bytes(json.dumps({
            "zip": out.name, "zipSha256": hashlib.sha256(out.read_bytes()).hexdigest(),
            "mode": "handoff", "filesInZip": len(files) + 1, "excludedCandidates": excluded,
            "filesScanned": c.files, "archivesScanned": c.archives,
            "referenceKeyFiles": len(c.hashes), "referencePasswords": len(c.passwords),
            "problemCount": len(c.problems), "problems": c.problems,
        }, ensure_ascii=False, indent=2).encode("utf-8"))
        print(f"交接包扫描：{c.files} 个文件、{c.archives} 个归档（含嵌套），"
              f"对照本机 {len(c.hashes)} 个密钥文件 / {len(c.passwords)} 个口令，问题 {len(c.problems)} 个")
        if c.problems:
            for m in c.problems:
                print("  - " + m)
            raise RuntimeError("交接包扫描未通过")
    except Exception as e:  # noqa: BLE001
        out.unlink(missing_ok=True)
        print(f"失败，已删除交接包：{e}")
        return False
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="例如 1.3.0")
    a = ap.parse_args()
    out_dir = ROOT / "dist" / "handoff"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"violet-mcp-{a.version}-source-handoff.zip"
    ok = build(a.version, out, out.with_suffix(".scan.json"))
    print("完成" if ok else "未生成交接包")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
